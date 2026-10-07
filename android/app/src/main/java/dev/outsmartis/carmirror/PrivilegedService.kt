package dev.outsmartis.carmirror

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.FileOutputStream
import kotlin.concurrent.thread
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Shizuku "user service": runs in its own process as the shell user (uid 2000), the rights of
 * `adb shell`. That's what lets it create a *trusted* virtual display, start any app on it and
 * inject touches there; a normal app can only do that for its own activities.
 *
 * The techniques (a context that presents itself as com.android.shell, the display flags,
 * InputEvent.setDisplayId) follow scrcpy's server (Apache 2.0). Unlike scrcpy, nothing is
 * encoded here: the app encodes into its own MediaCodec surface and passes that surface in.
 */
class PrivilegedService : IPrivileged.Stub() {
    private val tag = "CarMirrorPriv"
    private val displays = ConcurrentHashMap<Int, VirtualDisplay>()

    init {
        if (Build.VERSION.SDK_INT >= 28) runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
    }

    override fun destroy() {
        displays.values.forEach { runCatching { it.release() } }
        exitProcess(0)
    }

    override fun uid(): Int = android.os.Process.myUid()

    override fun createDisplay(name: String, width: Int, height: Int, dpi: Int, surface: Surface): Int {
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            FLAG_SUPPORTS_TOUCH or FLAG_ROTATES_WITH_CONTENT
        if (Build.VERSION.SDK_INT >= 33) {
            flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
        }
        if (Build.VERSION.SDK_INT >= 34) flags = flags or FLAG_OWN_FOCUS or FLAG_DEVICE_DISPLAY_GROUP
        val vd = displayManager().createVirtualDisplay(name, width, height, dpi, surface, flags)
            ?: throw IllegalStateException("createVirtualDisplay returned null")
        val id = vd.display.displayId
        displays[id] = vd
        setImeLocal(id)
        Log.i(tag, "display $id ${width}x$height/$dpi")
        return id
    }

    override fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int, surface: Surface) {
        val vd = displays[displayId] ?: throw IllegalStateException("unknown display $displayId")
        vd.resize(width, height, dpi)
        vd.surface = surface
    }

    override fun releaseDisplay(displayId: Int) {
        displays.remove(displayId)?.release()
    }

    override fun launchOnDisplay(component: String, displayId: Int): Boolean {
        // `am` does the ActivityOptions.setLaunchDisplayId() dance for us, with shell rights
        val p = ProcessBuilder(
            "am", "start", "--display", displayId.toString(),
            "-f", "0x10000000", // NEW_TASK: brings a running app's task over to this display
            "-n", component,
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(10, TimeUnit.SECONDS)
        Log.i(tag, "launch $component on $displayId: ${out.trim()}")
        return !out.contains("Error")
    }

    override fun injectMotion(event: MotionEvent, displayId: Int): Boolean = inject(event, displayId)

    override fun injectKey(event: KeyEvent, displayId: Int): Boolean = inject(event, displayId, INJECT_WAIT_FOR_RESULT)

    private fun inject(event: InputEvent, displayId: Int, mode: Int = INJECT_ASYNC): Boolean = try {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId)
        val im = shellContext().getSystemService(Context.INPUT_SERVICE) as InputManager
        InputManager::class.java.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            .invoke(im, event, mode) as Boolean
    } catch (e: Exception) {
        Log.w(tag, "inject failed: $e")
        false
    }

    /**
     * Phones without per-display focus route keys to the "top focused" display (the phone's own
     * screen). Touches move focus, keys don't: pull focus to the car display before a key.
     */
    override fun focusDisplay(displayId: Int) {
        runCatching {
            val wm = serviceInterface("window", "android.view.IWindowManager")
            wm.javaClass.getMethod("moveDisplayToTopIfAllowed", Int::class.javaPrimitiveType).invoke(wm, displayId)
        }.onFailure { Log.w(tag, "moveDisplayToTopIfAllowed: $it") }
        runCatching {
            val atm = serviceInterface("activity_task", "android.app.IActivityTaskManager")
            atm.javaClass.getMethod("focusTopTask", Int::class.javaPrimitiveType).invoke(atm, displayId)
        }.onFailure { Log.w(tag, "focusTopTask: $it") }
    }

    private fun serviceInterface(name: String, iface: String): Any {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name) as IBinder
        return Class.forName("$iface\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    @Volatile private var recorder: AudioRecord? = null

    @SuppressLint("MissingPermission", "WrongConstant")
    override fun startAudioCapture(): ParcelFileDescriptor {
        stopAudioCapture()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48000)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val builder = AudioRecord.Builder()
        if (Build.VERSION.SDK_INT >= 31) builder.setContext(shellContext())
        val rec = builder
            .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min, 4096) * 4)
            .build()
        rec.startRecording()
        recorder = rec
        val pipe = ParcelFileDescriptor.createPipe()
        val out = FileOutputStream(pipe[1].fileDescriptor)
        val writeEnd = pipe[1]
        thread(name = "audio-capture") {
            val buf = ByteArray(3840) // 20 ms of 48 kHz stereo 16-bit
            try {
                while (recorder === rec) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n > 0) out.write(buf, 0, n)
                }
            } catch (e: Exception) {
                Log.i(tag, "audio capture ended: $e")
            } finally {
                runCatching { out.close() }
                runCatching { writeEnd.close() }
            }
        }
        Log.i(tag, "audio capture started")
        return pipe[0]
    }

    override fun stopAudioCapture() {
        val rec = recorder ?: return
        recorder = null
        runCatching { rec.stop() }
        runCatching { rec.release() }
    }

    /** Keyboard on the car display, not on the phone. */
    private fun setImeLocal(displayId: Int) {
        runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "window") as IBinder
            val wm = Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(wm, displayId, 0 /* DISPLAY_IME_POLICY_LOCAL */)
        }.onFailure { Log.w(tag, "IME policy: $it") }
    }

    private fun displayManager(): DisplayManager {
        val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(shellContext())
    }

    companion object {
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val FLAG_OWN_FOCUS = 1 shl 14
        private const val FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15
        private const val INJECT_ASYNC = 0
        private const val INJECT_WAIT_FOR_RESULT = 1

        @Volatile private var context: Context? = null

        /** A system context that identifies itself as com.android.shell (our uid), as system services require. */
        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        fun shellContext(): Context {
            context?.let { return it }
            val at = Class.forName("android.app.ActivityThread")
            var thread = at.getMethod("currentActivityThread").invoke(null)
            if (thread == null) {
                thread = at.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
                at.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
            }
            val system = at.getDeclaredMethod("getSystemContext").invoke(thread) as Context
            val shell = object : ContextWrapper(system) {
                override fun getPackageName() = "com.android.shell"
                override fun getOpPackageName() = "com.android.shell"
                override fun getAttributionSource(): AttributionSource =
                    AttributionSource.Builder(2000).setPackageName("com.android.shell").build()
                override fun getApplicationContext(): Context = this
            }
            context = shell
            return shell
        }
    }
}

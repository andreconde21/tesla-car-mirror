package dev.outsmartis.carmirror

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.runBlocking
import org.webrtc.DataChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * One app on its own car-sized screen (Shizuku): the privileged service creates a trusted
 * virtual display around this session's encoder surface and starts the app on it. The app
 * lays itself out for exactly the car's pane, and relayouts live when the pane changes
 * (bars hidden, fullscreen, split view). The phone screen stays free.
 */
class AppSession(
    private val context: Context,
    override val sid: Long,
    override val pkg: String,
    private var width: Int,
    private var height: Int,
    private var dpi: Int,
    private var fps: Int,
    private var bitrate: Int,
    private val onStarted: (AppSession) -> Unit,
    private val onEnded: (AppSession, String?) -> Unit,
    private val onStats: (AppSession, Long, Int) -> Unit,
) : CarSession {
    private val tag = "CarMirrorApp"
    private val channelLatch = CountDownLatch(1)
    private val sender = VideoChannelSender(tag, sid) { requestKeyFrame() }
    @Volatile private var stopped = false
    @Volatile private var restart = false
    @Volatile private var keyRequested = false
    @Volatile var startedOk = false
        private set
    @Volatile private var displayId = -1
    override var fullLog: String? = null

    override fun attachChannel(dc: DataChannel) {
        sender.channel = dc
        channelLatch.countDown()
    }

    override fun start() {
        thread(name = "app-$sid") {
            val error = try {
                run()
                null
            } catch (e: Exception) {
                Log.w(tag, "app session $sid ended: $e")
                e.message ?: e.toString()
            }
            releaseDisplay()
            onEnded(this, if (stopped) null else error ?: "The app stopped")
        }
    }

    private fun run() {
        val service = runBlocking { ShizukuBridge.awaitService(4000) } ?: throw IllegalStateException("Shizuku is not running")
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToString()
            ?: throw IllegalStateException("$pkg can't be opened")
        if (!channelLatch.await(8, TimeUnit.SECONDS)) throw IllegalStateException("video channel did not open")
        var first = true
        while (!stopped) {
            restart = false
            encodeOnce(service) {
                if (first) {
                    first = false
                    if (!service.launchOnDisplay(launch, displayId)) throw IllegalStateException("could not open $pkg on the car screen")
                    startedOk = true
                    onStarted(this)
                }
            }
        }
    }

    private fun encodeOnce(service: IPrivileged, onReady: () -> Unit) {
        val w = width
        val h = height
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        try {
            if (stopped) return
            if (displayId < 0) {
                displayId = service.createDisplay("CarMirror-$pkg", w, h, dpi, surface)
            } else {
                service.resizeDisplay(displayId, w, h, dpi, surface)
            }
            Log.i(tag, "app session $sid: $pkg on display $displayId ${w}x$h/$dpi")
            sender.sendSession(w, h)
            onReady()
            val info = MediaCodec.BufferInfo()
            var lastStats = System.currentTimeMillis()
            while (!stopped && !restart) {
                if (keyRequested) {
                    keyRequested = false
                    codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                }
                val idx = codec.dequeueOutputBuffer(info, 50_000)
                if (idx < 0) continue
                val buf = codec.getOutputBuffer(idx)
                if (buf != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(bytes)
                    val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    sender.sendPacket(config, key, if (config) 0 else info.presentationTimeUs, bytes)
                    val now = System.currentTimeMillis()
                    if (!config && now - lastStats >= 2000) {
                        lastStats = now
                        onStats(this, (System.nanoTime() / 1000 - info.presentationTimeUs) / 1000, sender.droppedFrames)
                    }
                }
                codec.releaseOutputBuffer(idx, false)
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { surface.release() }
        }
    }

    // ------------------------------------------------------------ input (real multi-touch)

    private data class Pointer(val id: Int, var x: Float, var y: Float)
    private val pointers = ArrayList<Pointer>()
    private var downTime = 0L

    @Synchronized
    override fun touch(action: Int, id: Long, x: Int, y: Int, w: Int, h: Int) {
        val service = ShizukuBridge.service ?: return
        if (displayId < 0 || w <= 0 || h <= 0) return
        // the car's coordinates are in video pixels; the display is the same size unless a resize is in flight
        val px = x.toFloat() * width / w
        val py = y.toFloat() * height / h
        val pid = if (id < 0) 0 else id.toInt()
        val now = SystemClock.uptimeMillis()
        var idx = pointers.indexOfFirst { it.id == pid }
        val motionAction: Int
        when (action) {
            0 -> { // down
                if (idx >= 0) pointers.removeAt(idx)
                pointers += Pointer(pid, px, py)
                idx = pointers.size - 1
                if (pointers.size == 1) downTime = now
                motionAction = if (pointers.size == 1) MotionEvent.ACTION_DOWN
                else MotionEvent.ACTION_POINTER_DOWN or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            2 -> { // move
                if (idx < 0) return
                pointers[idx].x = px
                pointers[idx].y = py
                motionAction = MotionEvent.ACTION_MOVE
            }
            else -> { // up
                if (idx < 0) return
                pointers[idx].x = px
                pointers[idx].y = py
                motionAction = if (pointers.size == 1) MotionEvent.ACTION_UP
                else MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
        }
        val n = pointers.size
        val props = Array(n) { i -> MotionEvent.PointerProperties().apply { this.id = pointers[i].id; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(n) { i ->
            MotionEvent.PointerCoords().apply { this.x = pointers[i].x; this.y = pointers[i].y; pressure = 1f; size = 1f }
        }
        val ev = MotionEvent.obtain(downTime, now, motionAction, n, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        runCatching { service.injectMotion(ev, displayId) }
        ev.recycle()
        if (action != 0 && action != 2) pointers.removeAt(idx)
    }

    override fun scroll(x: Int, y: Int, w: Int, h: Int, dx: Float, dy: Float) {
        // a two-step drag: wheels don't exist in a car
        val dist = (dy * height / 6f).toInt()
        touch(0, 0, x, y, w, h)
        touch(2, 0, x, y + dist / 2 * h / height, w, h)
        touch(1, 0, x, y + dist * h / height, w, h)
    }

    override fun key(name: String) {
        val service = ShizukuBridge.service ?: return
        if (displayId < 0) return
        val code = when (name) {
            "home" -> KeyEvent.KEYCODE_HOME
            "recents" -> KeyEvent.KEYCODE_APP_SWITCH
            else -> KeyEvent.KEYCODE_BACK
        }
        val now = SystemClock.uptimeMillis()
        for (a in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val ev = KeyEvent(now, now, a, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
            runCatching { service.injectKey(ev, displayId) }
        }
    }

    /** The car pane changed size (bars hidden, fullscreen, split): new display size, app relayouts. */
    override fun resize(w: Int, h: Int) {
        val nw = w and 15.inv()
        val nh = h and 15.inv()
        if (nw == width && nh == height) return
        width = nw
        height = nh
        restart = true
    }

    override fun requestKeyFrame() {
        keyRequested = true
    }

    override fun reconfigure(w: Int, h: Int, dpi: Int, fps: Int, bitrate: Int) {
        width = w and 15.inv()
        height = h and 15.inv()
        this.dpi = dpi
        this.fps = fps
        this.bitrate = bitrate
        restart = true
    }

    /**
     * Release right away (not when the encoder thread winds down): a new session for the same
     * app may launch it any moment, and a late release would drag the app back to the phone.
     */
    @Synchronized
    private fun releaseDisplay() {
        val id = displayId
        if (id < 0) return
        displayId = -1
        runCatching { ShizukuBridge.service?.releaseDisplay(id) }
    }

    override fun stop() {
        stopped = true
        releaseDisplay()
        channelLatch.countDown()
    }
}

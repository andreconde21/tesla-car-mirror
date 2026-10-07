package dev.outsmartis.carmirror

import android.util.Log
import kotlinx.coroutines.runBlocking
import org.webrtc.DataChannel
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

/** scrcpy control messages (see scrcpy server ControlMessageReader, v4.1). */
object ScrcpyControl {
    private const val TYPE_INJECT_KEYCODE = 0
    private const val TYPE_INJECT_TOUCH_EVENT = 2
    private const val TYPE_INJECT_SCROLL_EVENT = 3
    private const val TYPE_START_APP = 16
    private const val TYPE_RESET_VIDEO = 17
    private const val TYPE_RESIZE_DISPLAY = 21

    const val KEYCODE_HOME = 3
    const val KEYCODE_BACK = 4
    const val KEYCODE_APP_SWITCH = 187

    fun touch(action: Int, pointerId: Long, x: Int, y: Int, w: Int, h: Int): ByteArray =
        ByteBuffer.allocate(32).apply {
            put(TYPE_INJECT_TOUCH_EVENT.toByte())
            put(action.toByte())
            putLong(pointerId)
            putInt(x)
            putInt(y)
            putShort(w.toShort())
            putShort(h.toShort())
            putShort(if (action == 1) 0 else 0xffff.toShort()) // pressure, u16 fixed point
            putInt(0) // action button
            putInt(0) // buttons
        }.array()

    fun scroll(x: Int, y: Int, w: Int, h: Int, dx: Float, dy: Float): ByteArray {
        fun fp(v: Float): Short = ((v / 16f).coerceIn(-1f, 1f) * 0x7fff).toInt().toShort()
        return ByteBuffer.allocate(21).apply {
            put(TYPE_INJECT_SCROLL_EVENT.toByte())
            putInt(x)
            putInt(y)
            putShort(w.toShort())
            putShort(h.toShort())
            putShort(fp(dx))
            putShort(fp(dy))
            putInt(0)
        }.array()
    }

    fun key(action: Int, keycode: Int): ByteArray = ByteBuffer.allocate(14).apply {
        put(TYPE_INJECT_KEYCODE.toByte())
        put(action.toByte())
        putInt(keycode)
        putInt(0) // repeat
        putInt(0) // meta state
    }.array()

    fun startApp(pkg: String): ByteArray {
        val name = pkg.toByteArray(Charsets.UTF_8)
        require(name.size < 256)
        return ByteBuffer.allocate(2 + name.size).apply {
            put(TYPE_START_APP.toByte())
            put(name.size.toByte())
            put(name)
        }.array()
    }

    fun resetVideo(): ByteArray = byteArrayOf(TYPE_RESET_VIDEO.toByte())

    fun resizeDisplay(w: Int, h: Int): ByteArray = ByteBuffer.allocate(5).apply {
        put(TYPE_RESIZE_DISPLAY.toByte())
        putShort(w.toShort())
        putShort(h.toShort())
    }.array()
}

/**
 * One app mirrored to the car: a scrcpy server on its own virtual display, its H.264
 * stream forwarded to a WebRTC data channel, and the car's input sent back.
 */
class MirrorSession(
    override val sid: Long,
    override val pkg: String,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val onStarted: (MirrorSession) -> Unit,
    private val onEnded: (MirrorSession, String?) -> Unit,
    private val onStats: (MirrorSession, Long, Int) -> Unit = { _, _, _ -> },
) : CarSession {
    private val tag = "CarMirrorSession"
    private var scid = Random.nextInt(1, 0x7fffffff)
    private val attemptLogs = StringBuilder()
    @Volatile private var channel: DataChannel? = null
    private val channelLatch = CountDownLatch(1)
    @Volatile private var socket: Socket? = null
    private var control: OutputStream? = null
    @Volatile private var stopped = false
    @Volatile private var appStarted = false
    @Volatile private var flexDisplay = true
    private var pendingResize: Pair<Int, Int>? = null

    // backpressure
    private var waitKey = false
    private var lastKeyRequest = 0L
    var droppedFrames = 0
        private set
    private var packets = 0L
    private var lastStats = 0L
    /** The scrcpy server output when the session failed, for remote debugging. */
    @Volatile override var fullLog: String? = null

    override fun attachChannel(dc: DataChannel) {
        channel = dc
        channelLatch.countDown()
    }

    override fun start() {
        thread(name = "session-$sid") {
            val error = try {
                run()
                null
            } catch (e: Exception) {
                Log.w(tag, "session $sid ended: $e")
                e.message ?: e.toString()
            }
            // stopped on purpose (car closed the app, link went down): nothing to report
            if (!stopped) Thread.sleep(800) // let the server's last lines and exit code land in its log
            val reason = if (stopped) null else listOfNotNull(error, serverError()).joinToString(": ").ifEmpty { "The app stopped" }
            fullLog = if (reason != null) attemptLogs.toString() + (runCatching { ShizukuBridge.service?.sessionLog(scid) }.getOrNull() ?: "") else null
            stop()
            onEnded(this, reason)
        }
    }

    private fun run() {
        val service = runBlocking { ShizukuBridge.awaitService() }
            ?: throw IllegalStateException("Shizuku isn't running on the phone. Open Shizuku and tap Start.")
        ShizukuBridge.ensureServerInstalled(service)

        var lastError: Exception? = null
        var crashCollected = false
        for (profile in ScrcpyProfiles.ordered(service)) {
            if (stopped) return
            try {
                attempt(service, profile)
                return
            } catch (e: Exception) {
                // only a server that dies before its first frame is worth retrying differently
                if (stopped || appStarted) throw e
                lastError = e
                Thread.sleep(500)
                val log = runCatching { service.sessionLog(scid) }.getOrDefault("")
                attemptLogs.append("--- profile ${profile.name}: $e\n").append(log.takeLast(1500)).append('\n')
                if (!crashCollected) {
                    crashCollected = true
                    val crash = runCatching { service.crashLog(120) }.getOrDefault("")
                    attemptLogs.append("--- crash buffer\n").append(crash.takeLast(5000)).append('\n')
                }
                Log.w(tag, "session $sid: profile ${profile.name} failed ($e), trying the next one")
                runCatching { service.stopSession(scid) }
                runCatching { socket?.close() }
                control = null
                scid = Random.nextInt(1, 0x7fffffff)
            }
        }
        throw lastError ?: IllegalStateException("no scrcpy profile worked")
    }

    private fun attempt(service: IPrivileged, profile: ScrcpyProfiles.Profile) {
        val secret = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }.substring(0, 32)
        val options = linkedMapOf(
            "scid" to "%08x".format(scid),
            "log_level" to "info",
            "video" to "true",
            "audio" to "false", // sound stays on the phone -> car Bluetooth, perfectly in sync
            "control" to "true",
            "tunnel_forward" to "false",
            "video_codec" to "h264", // hardware-decodable in every browser
            "video_bit_rate" to "$bitrate",
            "max_fps" to "$fps",
            "new_display" to "${width}x$height/$dpi",
            "flex_display" to "true", // lets the car resize the display (split view, bars hidden)
            "vd_destroy_content" to "false", // when the car leaves, apps move back to the phone screen
            "vd_system_decorations" to "false",
            "display_ime_policy" to "local", // keyboard shows on the car screen
            "keep_active" to "true",
            "send_device_meta" to "false",
            "send_dummy_byte" to "false",
            "send_stream_meta" to "true",
            "send_frame_meta" to "true",
            "clipboard_autosync" to "false",
            "power_off_on_close" to "false",
            "show_touches" to "false",
            "stay_awake" to "false",
            "cleanup" to "true",
        )
        options.putAll(profile.overrides)
        if (profile.overrides["max_fps"] != null) options["max_fps"] = minOf(fps, profile.overrides["max_fps"]!!.toInt()).toString()
        flexDisplay = options["flex_display"] == "true"
        val args = arrayOf(BuildConfig.SCRCPY_VERSION) + options.map { (k, v) -> "$k=$v" }
        Log.i(tag, "session $sid: starting with profile ${profile.name}")
        val port = service.startSession(scid, args, secret)
        val s = Socket()
        s.tcpNoDelay = true
        s.receiveBufferSize = 1 shl 20
        s.connect(InetSocketAddress("127.0.0.1", port), 5000)
        socket = s
        val out = s.getOutputStream()
        val sb = secret.toByteArray()
        out.write(sb.size)
        out.write(sb)
        out.flush()
        control = out

        if (!channelLatch.await(8, TimeUnit.SECONDS)) throw IllegalStateException("video channel did not open")

        val din = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 18))
        val codecId = din.readInt()
        if (codecId == 0 || codecId == 1) throw IllegalStateException("The phone could not capture this app")

        val header = ByteArray(12)
        val hb = ByteBuffer.wrap(header)
        while (!stopped) {
            din.readFully(header)
            val first = hb.getLong(0)
            if (first < 0) {
                // session packet: [flags:u32 (bit 31 set)][w:u32][h:u32]
                val w = hb.getInt(4)
                val h = hb.getInt(8)
                Log.i(tag, "session $sid video ${w}x$h")
                sendSession(w, h)
                if (!appStarted) {
                    ScrcpyProfiles.remember(profile)
                    sendControl(ScrcpyControl.startApp(pkg))
                    synchronized(this) {
                        appStarted = true
                        pendingResize?.takeIf { flexDisplay }?.let { (pw, ph) -> sendControl(ScrcpyControl.resizeDisplay(pw and 7.inv(), ph and 7.inv())) }
                        pendingResize = null
                    }
                    onStarted(this)
                }
                continue
            }
            val size = hb.getInt(8)
            if (size < 0 || size > 32 * 1024 * 1024) throw IllegalStateException("bad packet size $size")
            val payload = ByteArray(size)
            din.readFully(payload)
            val config = (first and PACKET_FLAG_CONFIG) != 0L
            val key = (first and PACKET_FLAG_KEY_FRAME) != 0L
            val pts = if (config) 0L else first and PTS_MASK
            sendPacket(config, key, pts, payload)
            packets++
            val now = System.currentTimeMillis()
            if (!config && now - lastStats >= 2000) {
                lastStats = now
                // pts is the capture time on the monotonic clock (µs): this is the encoder's latency
                val lagMs = (System.nanoTime() / 1000 - pts) / 1000
                Log.i(tag, "session $sid: ${packets} packets, encoder lag ${lagMs} ms, buffered ${channel?.bufferedAmount()} B, dropped $droppedFrames")
                onStats(this, lagMs, droppedFrames)
            }
        }
    }

    private fun sendSession(w: Int, h: Int) {
        val b = ByteBuffer.allocate(9).put(1).putInt(w).putInt(h).array()
        waitKey = true // the new encoder session starts with config + key frame
        send(b)
    }

    private fun sendPacket(config: Boolean, key: Boolean, pts: Long, payload: ByteArray) {
        val dc = channel ?: return
        if (!config) {
            if (!key && dc.bufferedAmount() > MAX_BUFFERED_BYTES) {
                // the link can't keep up: drop until the next key frame instead of building latency
                if (!waitKey) Log.w(tag, "session $sid congested (${dc.bufferedAmount()} B buffered), dropping to next key frame")
                waitKey = true
                droppedFrames++
                requestKeyFrame()
                return
            }
            if (waitKey && !key) {
                droppedFrames++
                return
            }
            if (key) waitKey = false
        }
        val flags = (if (config) 1 else 0) or (if (key) 2 else 0)
        var off = 0
        var first = true
        while (first || off < payload.size) {
            val headerLen = if (first) 14 else 1
            val n = minOf(CHUNK - headerLen, payload.size - off)
            val bb = ByteBuffer.allocate(headerLen + n)
            if (first) {
                bb.put(2).put(flags.toByte()).putLong(pts).putInt(payload.size)
            } else {
                bb.put(3)
            }
            bb.put(payload, off, n)
            send(bb.array())
            off += n
            first = false
        }
    }

    private fun send(bytes: ByteArray) {
        val dc = channel ?: return
        if (dc.state() != DataChannel.State.OPEN) throw IllegalStateException("video channel closed")
        dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
    }

    override fun requestKeyFrame() {
        val now = System.currentTimeMillis()
        if (now - lastKeyRequest < 1000) return
        lastKeyRequest = now
        sendControl(ScrcpyControl.resetVideo())
    }

    fun sendControl(bytes: ByteArray) {
        val out = control ?: return
        try {
            synchronized(out) {
                out.write(bytes)
                out.flush()
            }
        } catch (e: Exception) {
            Log.w(tag, "control write failed: $e")
        }
    }

    override fun touch(action: Int, id: Long, x: Int, y: Int, w: Int, h: Int) = sendControl(ScrcpyControl.touch(action, id, x, y, w, h))
    override fun scroll(x: Int, y: Int, w: Int, h: Int, dx: Float, dy: Float) = sendControl(ScrcpyControl.scroll(x, y, w, h, dx, dy))
    override fun key(name: String) = key(
        when (name) {
            "home" -> ScrcpyControl.KEYCODE_HOME
            "recents" -> ScrcpyControl.KEYCODE_APP_SWITCH
            else -> ScrcpyControl.KEYCODE_BACK
        },
    )

    fun key(keycode: Int) {
        sendControl(ScrcpyControl.key(0, keycode))
        sendControl(ScrcpyControl.key(1, keycode))
    }
    /** scrcpy's controller crashes on a resize before its display exists: hold it until then. */
    @Synchronized
    override fun resize(w: Int, h: Int) {
        if (!flexDisplay) return // fixed-size display (fallback profile): the car letterboxes instead
        if (appStarted) sendControl(ScrcpyControl.resizeDisplay(w and 7.inv(), h and 7.inv()))
        else pendingResize = w to h
    }

    /** The most useful lines of the scrcpy server output, if it failed. */
    private fun serverError(): String? {
        val log = runCatching { ShizukuBridge.service?.sessionLog(scid) }.getOrNull() ?: return null
        val lines = log.lines().filter {
            it.contains("ERROR") || it.contains("Exception") || it.contains("WARN") || it.startsWith("server exited")
        }
        return lines.takeLast(3).joinToString(" | ").take(500).ifEmpty { null }
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        runCatching { ShizukuBridge.service?.stopSession(scid) }
        runCatching { socket?.close() }
        channelLatch.countDown()
    }

    companion object {
        private const val PACKET_FLAG_CONFIG = 1L shl 62
        private const val PACKET_FLAG_KEY_FRAME = 1L shl 61
        private const val PTS_MASK = (1L shl 61) - 1
        private const val CHUNK = 64 * 1024
        private const val MAX_BUFFERED_BYTES = 768L * 1024
    }
}

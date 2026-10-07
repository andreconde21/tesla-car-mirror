package dev.outsmartis.carmirror

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.Display
import org.webrtc.DataChannel
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The phone's own screen mirrored to the car, with no special privileges: MediaProjection
 * for the picture (the screen-recording API), [TouchService] for input. The chosen app is
 * opened on the phone; turning the phone sideways gives a landscape picture.
 *
 * The encoder is configured like an ordinary screen recorder (no vendor extensions), because
 * that path works on every phone, including ones where scrcpy's native setup crashes.
 */
class ScreenSession(
    private val context: Context,
    override val sid: Long,
    override val pkg: String,
    private var carW: Int,
    private var carH: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val onStarted: (ScreenSession) -> Unit,
    private val onEnded: (ScreenSession, String?) -> Unit,
    private val onStats: (ScreenSession, Long, Int) -> Unit,
    /** The phone's system bars as fractions of the screen (left, top, right, bottom), for cropping in the car. */
    private val onBars: (ScreenSession, FloatArray) -> Unit = { _, _ -> },
) : CarSession {
    private var lastBars: Rect? = null
    private val tag = "CarMirrorScreen"
    @Volatile private var channel: DataChannel? = null
    private val channelLatch = CountDownLatch(1)
    @Volatile private var stopped = false
    @Volatile private var restart = false
    @Volatile private var keyRequested = false
    @Volatile private var started = false
    override var fullLog: String? = null

    // current capture size and the phone display size it maps to (for touches)
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0
    @Volatile private var dispW = 0
    @Volatile private var dispH = 0

    private val sender = VideoChannelSender(tag, sid) { requestKeyFrame() }

    override fun attachChannel(dc: DataChannel) {
        channel = dc
        sender.channel = dc
        channelLatch.countDown()
    }

    override fun start() {
        thread(name = "screen-$sid") {
            val wake = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                @Suppress("DEPRECATION")
                newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "CarMirror:screen")
            }
            val error = try {
                wake.acquire(6 * 60 * 60 * 1000L)
                run()
                null
            } catch (e: Exception) {
                Log.w(tag, "screen session $sid ended: $e")
                e.message ?: e.toString()
            } finally {
                runCatching { wake.release() }
            }
            onEnded(this, if (stopped) null else error ?: "The screen mirror stopped")
        }
    }

    private fun run() {
        if (TouchService.instance == null) {
            AppState.lastError.value = "Turn on CarMirror in Accessibility to control the phone from the car"
        }
        if (!Projection.active) {
            Projection.requestConsent(context)
            if (Projection.await(45_000) == null) {
                throw IllegalStateException("Tap \"Start now\" on the phone to share its screen with the car")
            }
        }
        launchApp()
        if (!channelLatch.await(8, TimeUnit.SECONDS)) throw IllegalStateException("video channel did not open")

        while (!stopped) {
            restart = false
            encodeOnce()
        }
    }

    /** Bring another app to the front without restarting the stream. */
    fun launch(newPkg: String) = launchApp(newPkg)

    private fun launchApp(target: String = pkg) {
        val intent = context.packageManager.getLaunchIntentForPackage(target) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        // the accessibility service may start activities from the background; fall back to the app context
        val ctx: Context = TouchService.instance ?: context
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.w(tag, "could not open $target: $e")
            AppState.lastError.value = "Could not open the app from the background: allow \"Display over other apps\""
        }
    }

    private fun display(): Display =
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

    /** Size that fits the phone screen (current orientation) inside the car pane. */
    private fun captureSize(): Pair<Int, Int> {
        val p = Point()
        @Suppress("DEPRECATION")
        display().getRealSize(p)
        dispW = p.x
        dispH = p.y
        val maxW = carW.coerceAtMost(1920)
        val maxH = carH.coerceAtMost(1920)
        val scale = minOf(maxW.toDouble() / p.x, maxH.toDouble() / p.y, 1.0)
        val w = ((p.x * scale).toInt() / 16) * 16
        val h = ((p.y * scale).toInt() / 16) * 16
        return w.coerceAtLeast(160) to h.coerceAtLeast(160)
    }

    private fun encodeOnce() {
        val (w, h) = captureSize()
        val rotation = display().rotation
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            // a static screen still gets a frame now and then (lets a late decoder catch up)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        try {
            if (!Projection.attach(surface, w, h, context.resources.displayMetrics.densityDpi)) {
                throw IllegalStateException("Screen capture permission was lost: tap Start on the phone again")
            }
            videoW = w
            videoH = h
            Log.i(tag, "screen session $sid: ${w}x$h (phone ${dispW}x$dispH)")
            sender.sendSession(w, h)
            lastBars = null
            reportBars()
            if (!started) {
                started = true
                onStarted(this)
            }
            val info = MediaCodec.BufferInfo()
            var lastStats = System.currentTimeMillis()
            var lastRotationCheck = 0L
            while (!stopped && !restart) {
                if (keyRequested) {
                    keyRequested = false
                    codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                }
                val now = System.currentTimeMillis()
                if (now - lastRotationCheck > 300) {
                    lastRotationCheck = now
                    if (display().rotation != rotation) {
                        Log.i(tag, "phone rotated: restarting capture")
                        break
                    }
                    reportBars()
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
                    if (!config && now - lastStats >= 2000) {
                        lastStats = now
                        val lagMs = (System.nanoTime() / 1000 - info.presentationTimeUs) / 1000
                        onStats(this, lagMs, sender.droppedFrames)
                    }
                }
                codec.releaseOutputBuffer(idx, false)
            }
        } finally {
            Projection.detach(surface)
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { surface.release() }
        }
    }

    private fun reportBars() {
        val t = TouchService.instance ?: return
        if (dispW == 0) return
        val r = t.systemBars(dispW, dispH)
        if (r == lastBars) return
        lastBars = r
        onBars(this, floatArrayOf(r.left.toFloat() / dispW, r.top.toFloat() / dispH, r.right.toFloat() / dispW, r.bottom.toFloat() / dispH))
    }

    // ------------------------------------------------------------ input

    override fun touch(action: Int, id: Long, x: Int, y: Int, w: Int, h: Int) {
        val t = TouchService.instance ?: return
        if (id > 0) return // one finger: accessibility gestures can't track independent pointers
        if (w <= 0 || h <= 0 || dispW == 0) return
        val px = x.toFloat() * dispW / w
        val py = y.toFloat() * dispH / h
        when (action) {
            0 -> t.down(px, py)
            2 -> t.move(px, py)
            else -> t.up(px, py)
        }
    }

    override fun scroll(x: Int, y: Int, w: Int, h: Int, dx: Float, dy: Float) {
        // mouse wheels don't exist in a car; a swipe stands in for it
        val t = TouchService.instance ?: return
        if (w <= 0 || h <= 0 || dispW == 0) return
        val px = x.toFloat() * dispW / w
        val py = y.toFloat() * dispH / h
        val dist = (dy * dispH / 6f).coerceIn(-dispH / 3f, dispH / 3f)
        t.down(px, py)
        t.move(px, py + dist / 2)
        t.up(px, py + dist)
    }

    override fun key(name: String) {
        TouchService.instance?.key(name)
    }

    override fun resize(w: Int, h: Int) {
        if (w == carW && h == carH) return
        carW = w
        carH = h
        restart = true
    }

    override fun requestKeyFrame() {
        keyRequested = true
    }

    override fun stop() {
        stopped = true
        channelLatch.countDown()
    }
}

/** Phone -> car video framing on a data channel (see the car's player.js), with backpressure. */
class VideoChannelSender(private val tag: String, private val sid: Long, private val onNeedKeyFrame: () -> Unit) {
    @Volatile var channel: DataChannel? = null
    private var waitKey = false
    private var lastKeyRequest = 0L
    var droppedFrames = 0
        private set

    fun sendSession(w: Int, h: Int) {
        waitKey = true
        send(ByteBuffer.allocate(9).put(1).putInt(w).putInt(h).array())
    }

    fun sendPacket(config: Boolean, key: Boolean, pts: Long, payload: ByteArray) {
        val dc = channel ?: return
        if (!config) {
            if (!key && dc.bufferedAmount() > MAX_BUFFERED_BYTES) {
                if (!waitKey) Log.w(tag, "session $sid congested (${dc.bufferedAmount()} B buffered), dropping to next key frame")
                waitKey = true
                droppedFrames++
                val now = System.currentTimeMillis()
                if (now - lastKeyRequest > 1000) {
                    lastKeyRequest = now
                    onNeedKeyFrame()
                }
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
            if (first) bb.put(2).put(flags.toByte()).putLong(pts).putInt(payload.size) else bb.put(3)
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

    companion object {
        private const val CHUNK = 64 * 1024
        private const val MAX_BUFFERED_BYTES = 768L * 1024
    }
}

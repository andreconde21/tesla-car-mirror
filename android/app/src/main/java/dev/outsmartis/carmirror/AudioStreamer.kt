package dev.outsmartis.carmirror

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.util.Log
import org.webrtc.DataChannel
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * The phone's sound, sent to the car with the picture. Teslas switch their media source to the
 * browser while it's open, so Bluetooth audio from the phone isn't heard: the browser plays it.
 *
 * Source: with Shizuku, the phone's output itself (remote submix: the phone goes quiet while
 * the car plays); otherwise, playback capture on the screen-sharing permission (the phone keeps
 * playing too). Encoded as Opus, one packet per 20 ms, on an unreliable/unordered channel:
 * a late audio packet is worth nothing.
 *
 * Wire format (phone -> car): [pts µs: u64][opus packet]
 */
class AudioStreamer(private val context: Context, private val onLog: (String) -> Unit) {
    private val tag = "CarMirrorAudio"
    @Volatile var channel: DataChannel? = null
    @Volatile private var running = false
    private var worker: Thread? = null

    @Synchronized
    fun start() {
        if (running) return
        running = true
        worker = thread(name = "audio-stream") {
            var lastSource = ""
            while (running) {
                val source = openSource()
                if (source == null) {
                    if (lastSource != "none") onLog("audio: waiting for a source (Shizuku, or screen sharing + microphone permission)")
                    lastSource = "none"
                    Thread.sleep(1500)
                    continue
                }
                if (lastSource != source.name) onLog("audio: capturing via ${source.name}")
                lastSource = source.name
                try {
                    encodeAndSend(source.input)
                } catch (e: Exception) {
                    if (running) onLog("audio: ${source.name} stopped: $e")
                } finally {
                    source.close()
                }
                if (running) Thread.sleep(500)
            }
        }
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { ShizukuBridge.service?.stopAudioCapture() }
        worker?.interrupt()
        worker = null
    }

    private class Source(val name: String, val input: InputStream, val close: () -> Unit)

    @SuppressLint("MissingPermission")
    private fun openSource(): Source? {
        // 1. Shizuku: the phone's own output, phone muted meanwhile
        ShizukuBridge.service?.let { s ->
            val pfd: ParcelFileDescriptor? = runCatching { s.startAudioCapture() }
                .onFailure { Log.w(tag, "submix: $it") }.getOrNull()
            if (pfd != null) {
                val input = FileInputStream(pfd.fileDescriptor)
                return Source("phone output (Shizuku)", input) {
                    runCatching { s.stopAudioCapture() }
                    runCatching { input.close() }
                    runCatching { pfd.close() }
                }
            }
        }
        // 2. Playback capture, riding on the screen-sharing permission
        val projection = Projection.projection ?: return null
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val rec = try {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build(),
                )
                .setBufferSizeInBytes(FRAME_BYTES * 8)
                .build()
        } catch (e: Exception) {
            Log.w(tag, "playback capture: $e")
            return null
        }
        rec.startRecording()
        val input = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int = rec.read(b, off, len).let { if (it < 0) -1 else it }
        }
        return Source("screen sharing (playback capture)", input) {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
    }

    private fun encodeAndSend(input: InputStream) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val pcm = ByteArray(FRAME_BYTES)
        val info = MediaCodec.BufferInfo()
        var samples = 0L
        try {
            while (running) {
                // one 20 ms frame in
                var got = 0
                while (got < pcm.size) {
                    val n = input.read(pcm, got, pcm.size - got)
                    if (n < 0) throw IllegalStateException("audio source closed")
                    got += n
                }
                val inIdx = codec.dequeueInputBuffer(20_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    buf.clear()
                    buf.put(pcm, 0, minOf(pcm.size, buf.remaining()))
                    codec.queueInputBuffer(inIdx, 0, pcm.size, samples * 1_000_000 / RATE, 0)
                    samples += FRAME_SAMPLES
                }
                // packets out
                while (true) {
                    val outIdx = codec.dequeueOutputBuffer(info, 0)
                    if (outIdx < 0) break
                    val out = codec.getOutputBuffer(outIdx)
                    if (out != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val msg = ByteBuffer.allocate(8 + info.size).putLong(info.presentationTimeUs)
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        msg.put(out)
                        send(msg.array())
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private fun send(bytes: ByteArray) {
        val dc = channel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        // nothing piles up: if the link is congested, a dropped audio packet beats a delayed one
        if (dc.bufferedAmount() > 64 * 1024) return
        dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
    }

    companion object {
        private const val RATE = 48_000
        private const val FRAME_SAMPLES = 960 // 20 ms
        private const val FRAME_BYTES = FRAME_SAMPLES * 2 * 2
    }
}

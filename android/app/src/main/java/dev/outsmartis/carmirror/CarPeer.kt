package dev.outsmartis.carmirror

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The WebRTC link with one car browser. The car offers and creates every data channel:
 * "ctl" (JSON control) and "v:<sid>" per mirrored app (binary video, phone -> car).
 */
class CarPeer(
    private val context: Context,
    private val factory: PeerConnectionFactory,
    val connId: String,
    private val prefs: Prefs,
    private val sendSignal: (JSONObject) -> Unit,
    private val onClosed: (CarPeer) -> Unit,
) {
    private val tag = "CarMirrorPeer"
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var pc: PeerConnection
    private var ctl: DataChannel? = null
    private val videoChannels = ConcurrentHashMap<Long, DataChannel>()
    private val sessions = ConcurrentHashMap<Long, MirrorSession>()
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var remoteSet = false
    @Volatile private var closed = false
    private var carInfo = "car"
    private val localCands = mutableListOf<String>()


    // ------------------------------------------------------------ signaling

    /** Run on the peer's thread; silently dropped once the peer is closed. */
    private fun post(block: () -> Unit) {
        try {
            exec.execute { if (!closed) block() }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    fun onSignal(data: JSONObject) = post {
        when (data.optString("type")) {
            "offer" -> {
                pc.setRemoteDescription(object : SdpAdapter("setRemote") {
                    override fun onSetSuccess() {
                        post {
                            remoteSet = true
                            pendingCandidates.forEach { pc.addIceCandidate(it) }
                            pendingCandidates.clear()
                            createAnswer()
                        }
                    }
                }, SessionDescription(SessionDescription.Type.OFFER, data.getString("sdp")))
            }
            "candidate" -> {
                val c = data.optJSONObject("candidate") ?: return@post
                val ice = IceCandidate(c.optString("sdpMid", "0"), c.optInt("sdpMLineIndex", 0), c.getString("candidate"))
                if (remoteSet) pc.addIceCandidate(ice) else pendingCandidates += ice
            }
            "bye" -> close("car said bye: ${data.optString("reason")}", notify = false)
        }
    }

    private fun createAnswer() {
        pc.createAnswer(object : SdpAdapter("createAnswer") {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(object : SdpAdapter("setLocal") {
                    override fun onSetSuccess() {
                        sendSignal(JSONObject().put("type", "answer").put("sdp", desc.description))
                    }
                }, desc)
            }
        }, MediaConstraints())
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            val parts = c.sdp.split(" ")
            // "candidate:… 1 udp prio ADDRESS PORT typ TYPE …"
            if (parts.size > 7 && !parts[4].startsWith("127.") && parts[4] != "::1") {
                synchronized(localCands) {
                    val entry = "${parts[4]} (${parts[7]})"
                    if (entry !in localCands) localCands += entry
                }
                AppState.localCandidates.value = synchronized(localCands) { localCands.toList() }
            }
            Log.i(tag, "local candidate ${c.sdp}")
            sendSignal(
                JSONObject().put("type", "candidate").put(
                    "candidate",
                    JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex),
                ),
            )
        }

        override fun onDataChannel(dc: DataChannel) {
            val label = dc.label()
            Log.i(tag, "data channel $label")
            if (label == "ctl") {
                ctl = dc
                dc.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previous: Long) {}
                    override fun onStateChange() {
                        Log.i(tag, "ctl ${dc.state()}")
                        if (dc.state() == DataChannel.State.CLOSED) close("control channel closed", notify = false)
                    }
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                        val text = String(bytes, Charsets.UTF_8)
                        post { onCtl(text) }
                    }
                })
            } else if (label.startsWith("v:")) {
                val sid = label.substring(2).toLongOrNull() ?: return
                videoChannels[sid] = dc
                dc.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previous: Long) {}
                    override fun onStateChange() {
                        if (dc.state() == DataChannel.State.CLOSED) {
                            videoChannels.remove(sid)
                            sessions[sid]?.stop()
                        }
                    }
                    override fun onMessage(buffer: DataChannel.Buffer) {}
                })
                sessions[sid]?.attachChannel(dc)
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.i(tag, "connection $newState")
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> updateCarState()
                PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.CLOSED ->
                    post { close("connection $newState", notify = false) }
                else -> {}
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.i(tag, "ice $state")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
    }

    // created after `observer` is initialized
    init {
        val config = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()),
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        pc = factory.createPeerConnection(config, observer) ?: throw IllegalStateException("createPeerConnection failed")
    }

    // ------------------------------------------------------------ control channel

    private fun sendCtl(msg: JSONObject) {
        val dc = ctl ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        dc.send(DataChannel.Buffer(ByteBuffer.wrap(msg.toString().toByteArray(Charsets.UTF_8)), false))
    }

    private fun onCtl(text: String) {
        val msg = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        val sid = msg.optLong("sid", -1)
        when (msg.optString("t")) {
            "hello" -> {
                val ua = msg.optString("ua")
                carInfo = if (ua.contains("Tesla", ignoreCase = true)) "Tesla" else "car browser"
                Log.i(tag, "car hello: $msg")
                updateCarState()
            }
            "apps?" -> sendApps()
            "ping" -> sendCtl(JSONObject().put("t", "pong").put("ts", msg.opt("ts")))
            "start" -> startSession(msg)
            "stop" -> sessions.remove(sid)?.stop()
            "touch" -> sessions[sid]?.touch(
                msg.optInt("a"), msg.optLong("id"), msg.optInt("x"), msg.optInt("y"), msg.optInt("w"), msg.optInt("h"),
            )
            "scroll" -> sessions[sid]?.scroll(
                msg.optInt("x"), msg.optInt("y"), msg.optInt("w"), msg.optInt("h"),
                msg.optDouble("dx", 0.0).toFloat(), msg.optDouble("dy", 0.0).toFloat(),
            )
            "key" -> sessions[sid]?.key(
                when (msg.optString("k")) {
                    "home" -> ScrcpyControl.KEYCODE_HOME
                    "recents" -> ScrcpyControl.KEYCODE_APP_SWITCH
                    else -> ScrcpyControl.KEYCODE_BACK
                },
            )
            "reset" -> sessions[sid]?.requestKeyFrame()
            "resize" -> sessions[sid]?.resize(msg.optInt("w"), msg.optInt("h"))
        }
    }

    private fun sendApps() {
        val apps = Apps.favorites(context, prefs)
        val arr = JSONArray()
        apps.forEach { arr.put(JSONObject().put("pkg", it.pkg).put("label", it.label)) }
        sendCtl(JSONObject().put("t", "apps").put("apps", arr))
        // icons one by one: keeps each message small
        for (a in apps) {
            val icon = Apps.iconPngBase64(context, a.pkg) ?: continue
            sendCtl(JSONObject().put("t", "icon").put("pkg", a.pkg).put("icon", icon))
        }
    }

    private fun startSession(msg: JSONObject) {
        val sid = msg.getLong("sid")
        val pkg = msg.getString("pkg")
        // scrcpy aligns sizes to 8 anyway; doing it here keeps touch coordinates exact
        val w = msg.optInt("w", 1280).coerceIn(320, 2560) and 7.inv()
        val h = msg.optInt("h", 720).coerceIn(240, 2560) and 7.inv()
        val session = MirrorSession(
            sid = sid,
            pkg = pkg,
            width = w,
            height = h,
            dpi = msg.optInt("dpi", 240).coerceIn(80, 640),
            fps = msg.optInt("fps", 60).coerceIn(10, 60),
            bitrate = msg.optInt("bitrate", 8_000_000).coerceIn(1_000_000, 40_000_000),
            onStarted = { sendCtl(JSONObject().put("t", "started").put("sid", it.sid)) },
            onStats = { s, lagMs, dropped ->
                sendCtl(JSONObject().put("t", "stats").put("sid", s.sid).put("lag", lagMs).put("dropped", dropped))
            },
            onEnded = { s, reason ->
                post {
                    sessions.remove(s.sid, s)
                    AppState.sessions.value = sessions.size
                    if (reason != null) AppState.lastError.value = reason
                    sendCtl(JSONObject().put("t", "ended").put("sid", s.sid).apply { if (reason != null) put("reason", reason) })
                    updateCarState()
                }
            },
        )
        sessions.put(sid, session)?.stop()
        videoChannels[sid]?.let { session.attachChannel(it) }
        AppState.sessions.value = sessions.size
        session.start()
        updateCarState()
    }

    private fun updateCarState() {
        if (closed) return
        val n = sessions.size
        AppState.car.value = "$carInfo connected" + if (n > 0) " · $n app${if (n > 1) "s" else ""} showing" else ""
    }

    fun close(reason: String, notify: Boolean = true) {
        if (closed) return
        closed = true
        Log.i(tag, "closing peer $connId: $reason")
        if (notify) runCatching { sendSignal(JSONObject().put("type", "bye").put("reason", reason)) }
        sessions.values.forEach { it.stop() }
        sessions.clear()
        AppState.sessions.value = 0
        AppState.car.value = null
        exec.execute {
            runCatching { pc.dispose() }
            exec.shutdown()
        }
        onClosed(this)
    }

    private open class SdpAdapter(private val what: String) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            Log.e("CarMirrorPeer", "$what failed: $error")
        }
        override fun onSetFailure(error: String?) {
            Log.e("CarMirrorPeer", "$what failed: $error")
        }
    }
}

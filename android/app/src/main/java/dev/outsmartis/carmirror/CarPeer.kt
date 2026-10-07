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
    private val sessions = ConcurrentHashMap<Long, CarSession>()
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var remoteSet = false
    @Volatile private var closed = false
    private var carInfo = "car"
    private val localCands = mutableListOf<String>()

    // hotspot relay (see HotspotRelay)
    private var relay: HotspotRelay? = null
    private var relayMid: String = "0"
    private var relayMLine = 0
    private val carIps = mutableSetOf<String>()
    private val advertised = mutableSetOf<String>()


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
                val parts = c.getString("candidate").split(" ")
                if (parts.size > 7 && parts[2].equals("udp", true) && parts[7] == "host" && !parts[4].contains(":")) {
                    carIps += parts[4]
                    advertiseRelay()
                }
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
            if (parts.size > 7 && parts[2].equals("udp", true) && parts[4] == "127.0.0.1") {
                val port = parts[5].toIntOrNull()
                if (port != null) post { startRelay(port, c.sdpMid, c.sdpMLineIndex) }
            }
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

    // ------------------------------------------------------------ hotspot relay

    private fun startRelay(loopbackPort: Int, mid: String?, mLine: Int) {
        if (relay != null || closed) return
        relay = try {
            HotspotRelay(loopbackPort)
        } catch (e: Exception) {
            Log.w(tag, "relay failed: $e")
            return
        }
        relayMid = mid ?: "0"
        relayMLine = mLine
        advertiseRelay()
    }

    /** Offer the relay at every address the car might reach us on. */
    private fun advertiseRelay() {
        val r = relay ?: return
        val targets = linkedSetOf<String>()
        carIps.mapNotNullTo(targets) { HotspotRelay.guessFor(it) }
        HotspotRelay.visibleAddresses().filterTo(targets) { HotspotRelay.guessFor(it) != null }
        for (ip in targets) {
            if (!advertised.add(ip)) continue
            val cand = "candidate:${(ip.hashCode() and 0x7fffffff)} 1 udp 2130706431 $ip ${r.port} typ host generation 0"
            Log.i(tag, "relay candidate $cand")
            sendSignal(
                JSONObject().put("type", "candidate").put(
                    "candidate",
                    JSONObject().put("candidate", cand).put("sdpMid", relayMid).put("sdpMLineIndex", relayMLine),
                ),
            )
        }
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
                sendCtl(JSONObject().put("t", "caps").put("mode", mode()))
                TouchService.foregroundListener = { pkg, home ->
                    post { sendCtl(JSONObject().put("t", "fg").put("pkg", pkg).put("home", home)) }
                }
                TouchService.instance?.foreground?.let { pkg ->
                    sendCtl(JSONObject().put("t", "fg").put("pkg", pkg).put("home", TouchService.instance?.isHome(pkg) == true))
                }
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
            "key" -> sessions[sid]?.key(msg.optString("k", "back"))
            "reset" -> sessions[sid]?.requestKeyFrame()
            "launch" -> (sessions[sid] as? ScreenSession)?.launch(msg.optString("pkg"))
            "resize" -> sessions[sid]?.resize(msg.optInt("w"), msg.optInt("h"))
            "reconfigure" -> sessions[sid]?.reconfigure(
                msg.optInt("w", 1280).coerceIn(320, 2560),
                msg.optInt("h", 720).coerceIn(240, 2560),
                msg.optInt("dpi", 240).coerceIn(80, 640),
                msg.optInt("fps", 60).coerceIn(10, 60),
                msg.optInt("bitrate", 8_000_000).coerceIn(1_000_000, 40_000_000),
            )
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

    private data class StartParams(val sid: Long, val pkg: String, val w: Int, val h: Int, val dpi: Int, val fps: Int, val bitrate: Int)

    private fun startSession(msg: JSONObject) {
        val p = StartParams(
            sid = msg.getLong("sid"),
            pkg = msg.getString("pkg"),
            // encoders like sizes in multiples of 16; doing it here keeps touch coordinates exact
            w = msg.optInt("w", 1280).coerceIn(320, 2560) and 15.inv(),
            h = msg.optInt("h", 720).coerceIn(240, 2560) and 15.inv(),
            dpi = msg.optInt("dpi", 240).coerceIn(80, 640),
            fps = msg.optInt("fps", 60).coerceIn(10, 60),
            bitrate = msg.optInt("bitrate", 8_000_000).coerceIn(1_000_000, 40_000_000),
        )
        launchSession(p, mode())
    }

    private fun launchSession(p: StartParams, mode: String) {
        val onEnded = { sess: CarSession, reason: String? ->
            post {
                if (sess is AppSession && !sess.startedOk && reason != null && !appsFailed) {
                    // per-app screens didn't work on this phone/session: fall back to the phone screen
                    Log.w(tag, "per-app screen failed ($reason), falling back to screen mode")
                    appsFailed = true
                    sendCtl(JSONObject().put("t", "caps").put("mode", "screen"))
                    sessions.remove(sess.sid, sess)
                    launchSession(p, "screen")
                    return@post
                }
                sessions.remove(sess.sid, sess)
                AppState.sessions.value = sessions.size
                if (reason != null) AppState.lastError.value = reason
                sendCtl(
                    JSONObject().put("t", "ended").put("sid", sess.sid).apply {
                        if (reason != null) put("reason", reason)
                        sess.fullLog?.let { put("log", it.takeLast(6000)) }
                    },
                )
                updateCarState()
            }
        }
        val onStats = { sess: CarSession, lagMs: Long, dropped: Int ->
            sendCtl(JSONObject().put("t", "stats").put("sid", sess.sid).put("lag", lagMs).put("dropped", dropped))
        }
        val onStarted = { sess: CarSession -> sendCtl(JSONObject().put("t", "started").put("sid", sess.sid)) }
        val session: CarSession = if (mode == "apps") {
            AppSession(context, p.sid, p.pkg, p.w, p.h, p.dpi, p.fps, p.bitrate, onStarted, onEnded, onStats)
        } else {
            // one phone screen: a new app replaces whatever was showing
            sessions.values.forEach { it.stop() }
            sessions.clear()
            ScreenSession(
                onBars = { sess, f ->
                    sendCtl(
                        JSONObject().put("t", "bars").put("sid", sess.sid)
                            .put("l", f[0].toDouble()).put("tp", f[1].toDouble()).put("r", f[2].toDouble()).put("b", f[3].toDouble()),
                    )
                },
                context = context,
                sid = p.sid,
                pkg = p.pkg,
                carW = p.w,
                carH = p.h,
                fps = p.fps,
                bitrate = p.bitrate,
                onStarted = onStarted,
                onEnded = onEnded,
                onStats = onStats,
            )
        }
        sessions.put(p.sid, session)?.stop()
        videoChannels[p.sid]?.let { session.attachChannel(it) }
        AppState.sessions.value = sessions.size
        session.start()
        updateCarState()
    }

    @Volatile private var appsFailed = false

    /**
     * "apps": each app on its own car-sized screen (Shizuku running, setting on);
     * "screen": the phone's own screen (always works).
     */
    private fun mode(): String {
        if (!prefs.appsMode || appsFailed) return "screen"
        return if (ShizukuBridge.readyOrBind(1500) != null) "apps" else "screen"
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
        relay?.close()
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

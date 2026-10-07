package dev.outsmartis.carmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.PeerConnectionFactory
import java.util.concurrent.TimeUnit

/**
 * Keeps CarMirror reachable while the phone is in a pocket: holds the signaling
 * connection to the server and the WebRTC link with the car.
 */
class MirrorService : Service() {
    private val tag = "CarMirrorService"
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var factory: PeerConnectionFactory
    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    private var backoffMs = 1000L
    private var stopped = false
    private var peer: CarPeer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        factory = PeerConnectionFactory.builder()
            .setOptions(
                PeerConnectionFactory.Options().apply {
                    networkIgnoreMask = 0
                    // Android's network monitor only reports networks ConnectivityManager knows
                    // (mobile data, Wi-Fi client); the hotspot interface the car is on is not one
                    // of them. Without the monitor, libwebrtc enumerates every interface itself.
                    disableNetworkMonitor = true
                },
            )
            .createPeerConnectionFactory()
        createChannel()
        startForegroundCompat("Waiting for the car")
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CarMirror:link")
            .apply { setReferenceCounted(false); acquire() }
        AppState.serviceRunning.value = true
        ShizukuBridge.bindIfPossible()
        connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_NEW_CODE -> ws?.send(JSONObject().put("t", "newCode").toString())
            ACTION_UNPAIR_ALL -> ws?.send(JSONObject().put("t", "unpairAll").toString())
            ACTION_RECONNECT -> {
                ws?.cancel()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        Projection.stop()
        stopped = true
        peer?.close("service stopped")
        ws?.close(1000, "bye")
        wakeLock?.release()
        AppState.serviceRunning.value = false
        AppState.serverOnline.value = false
        AppState.server.value = "Stopped"
        AppState.pairCode.value = null
        AppState.car.value = null
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ runCatching { factory.dispose() } }, 500)
        super.onDestroy()
    }

    // ------------------------------------------------------------ signaling

    private fun connect() {
        if (stopped) return
        val base = prefs.serverUrl
        val wsUrl = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws"
        AppState.server.value = "Connecting to server…"
        Log.i(tag, "connecting $wsUrl")
        ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
    }

    private fun scheduleReconnect() {
        if (stopped) return
        main.postDelayed({ connect() }, backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(30_000)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            backoffMs = 1000
            AppState.serverOnline.value = true
            AppState.server.value = "Online"
            webSocket.send(
                JSONObject()
                    .put("t", "hello")
                    .put("role", "phone")
                    .put("deviceId", prefs.deviceId)
                    .put("deviceSecret", prefs.deviceSecret)
                    .put("name", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
                    .toString(),
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = try {
                JSONObject(text)
            } catch (_: Exception) {
                return
            }
            main.post { onServerMessage(msg) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onDown(webSocket, "closed: $reason")
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onDown(webSocket, t.message ?: t.toString())

        private fun onDown(webSocket: WebSocket, why: String) {
            main.post {
                if (webSocket !== ws) return@post
                Log.w(tag, "server connection down: $why")
                AppState.serverOnline.value = false
                AppState.server.value = "Offline ($why), retrying…"
                AppState.pairCode.value = null
                scheduleReconnect()
            }
        }
    }

    private fun onServerMessage(msg: JSONObject) {
        when (msg.optString("t")) {
            "welcome" -> {
                AppState.pairCode.value = msg.optString("pairCode")
                AppState.pairedCars.value = msg.optInt("pairedCars")
            }
            "pairCode" -> AppState.pairCode.value = msg.optString("code")
            "carPaired", "unpaired" -> AppState.pairedCars.value = msg.optInt("pairedCars")
            "error" -> AppState.lastError.value = "Server: ${msg.optString("msg")}"
            "signal" -> onSignal(msg.optString("from"), msg.getJSONObject("data"))
            // "carGone": the car's signaling socket dropped. The WebRTC link may well be alive
            // (it doesn't need the server), so the peer only closes on its own connection state.
        }
    }

    private fun onSignal(from: String, data: JSONObject) {
        if (data.optString("type") == "offer") {
            // a (re)connecting car: replace any previous link
            peer?.close("replaced by a new connection")
            peer = CarPeer(
                context = this,
                factory = factory,
                connId = from,
                prefs = prefs,
                sendSignal = { d -> ws?.send(JSONObject().put("t", "signal").put("to", from).put("data", d).toString()) },
                onClosed = { p ->
                    main.post {
                        if (peer === p) peer = null
                        updateNotification("Waiting for the car")
                    }
                },
            )
            updateNotification("Car connected")
        }
        if (peer?.connId == from) peer?.onSignal(data)
    }

    // ------------------------------------------------------------ notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "CarMirror", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MirrorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("CarMirror")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun startForegroundCompat(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    /** Android 14+: a MediaProjection needs the service to run with the mediaProjection type. */
    fun enableProjectionType() {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Sharing the screen with the car"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        @Volatile var instance: MirrorService? = null
            private set
        private const val CHANNEL = "carmirror"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "dev.outsmartis.carmirror.STOP"
        const val ACTION_NEW_CODE = "dev.outsmartis.carmirror.NEW_CODE"
        const val ACTION_UNPAIR_ALL = "dev.outsmartis.carmirror.UNPAIR_ALL"
        const val ACTION_RECONNECT = "dev.outsmartis.carmirror.RECONNECT"

        fun start(context: Context, action: String? = null) {
            val i = Intent(context, MirrorService::class.java)
            if (action != null) i.action = action
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MirrorService::class.java).setAction(ACTION_STOP))
        }
    }
}

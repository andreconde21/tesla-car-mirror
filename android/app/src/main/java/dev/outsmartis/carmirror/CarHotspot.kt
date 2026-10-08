package dev.outsmartis.carmirror

import kotlin.concurrent.thread

/**
 * The phone's hotspot restarted on 9.9.0.0/24 (via Shizuku), so the Tesla browser, which
 * refuses private addresses, can reach the phone directly: no carrier loop-back, no server
 * relay, no mobile data for the video. While it's on, only one device (the car) can join.
 */
object CarHotspot {
    const val SERVER = "9.9.0.1"
    private const val SERVER_ADDR = "9.9.0.1/24"
    private const val CLIENT_ADDR = "9.9.0.2/24"

    fun start(onDone: (String) -> Unit = {}) {
        keepNormal = false
        thread(name = "car-hotspot") {
            AppState.carHotspot.value = "Starting…"
            val s = ShizukuBridge.readyOrBind(3000)
            val result = if (s == null) "Shizuku isn't running" else runCatching { s.startCarHotspot(SERVER_ADDR, CLIENT_ADDR) }.getOrElse { "error: $it" }
            AppState.carHotspot.value = if (result == "ok") "On (phone is $SERVER)" else "Couldn't start: $result"
            PhoneLog.log("car hotspot: $result")
            onDone(result)
        }
    }

    /** The user chose the normal hotspot: leave it alone until it's turned off. */
    @Volatile private var keepNormal = false
    @Volatile private var stoppedAt = 0L

    fun stop() {
        keepNormal = true
        stoppedAt = System.currentTimeMillis()
        thread(name = "car-hotspot") {
            runCatching { ShizukuBridge.service?.stopCarHotspot() }
            AppState.carHotspot.value = null
            PhoneLog.log("car hotspot stopped")
        }
    }

    val active: Boolean get() = AppState.carHotspot.value?.startsWith("On") == true

    private var lastAttempt = 0L
    @Volatile private var busy = false

    /**
     * Called every few seconds by the service: keeps the status line true to the actual hotspot,
     * and (if [auto]) switches a normal hotspot to car mode as soon as it is turned on.
     */
    fun watch(auto: Boolean) {
        if (busy) return
        val s = ShizukuBridge.service ?: return
        val addr = runCatching { s.hotspotAddress() }.getOrDefault("")
        when {
            addr == SERVER -> if (!active) AppState.carHotspot.value = "On (phone is $SERVER)"
            addr.isEmpty() -> {
                // "back to normal" restarts the hotspot: the brief gap isn't the user turning it off
                if (System.currentTimeMillis() - stoppedAt > 20_000) keepNormal = false
                if (AppState.carHotspot.value?.startsWith("Starting") != true) AppState.carHotspot.value = null
            }
            else -> {
                if (active) AppState.carHotspot.value = null
                val now = System.currentTimeMillis()
                if (auto && !keepNormal && now - lastAttempt > 60_000) {
                    lastAttempt = now
                    busy = true
                    PhoneLog.log("hotspot on $addr: switching it to car mode")
                    start { busy = false }
                }
            }
        }
    }
}

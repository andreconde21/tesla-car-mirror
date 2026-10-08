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
        thread(name = "car-hotspot") {
            AppState.carHotspot.value = "Starting…"
            val s = ShizukuBridge.readyOrBind(3000)
            val result = if (s == null) "Shizuku isn't running" else runCatching { s.startCarHotspot(SERVER_ADDR, CLIENT_ADDR) }.getOrElse { "error: $it" }
            AppState.carHotspot.value = if (result == "ok") "On (phone is $SERVER)" else "Couldn't start: $result"
            PhoneLog.log("car hotspot: $result")
            onDone(result)
        }
    }

    fun stop() {
        thread(name = "car-hotspot") {
            runCatching { ShizukuBridge.service?.stopCarHotspot() }
            AppState.carHotspot.value = null
            PhoneLog.log("car hotspot stopped")
        }
    }

    val active: Boolean get() = AppState.carHotspot.value?.startsWith("On") == true
}

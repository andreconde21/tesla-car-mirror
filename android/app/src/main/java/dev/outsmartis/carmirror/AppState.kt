package dev.outsmartis.carmirror

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.SecureRandom
import java.util.UUID

/** Observable state shared by the service and the UI. */
object AppState {
    val shizuku = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val serviceRunning = MutableStateFlow(false)
    val server = MutableStateFlow("Not started")
    val serverOnline = MutableStateFlow(false)
    val pairCode = MutableStateFlow<String?>(null)
    val pairedCars = MutableStateFlow(0)
    val car = MutableStateFlow<String?>(null)
    val sessions = MutableStateFlow(0)
    val lastError = MutableStateFlow<String?>(null)
    val localCandidates = MutableStateFlow<List<String>>(emptyList())
    val projection = MutableStateFlow(false)
    val touch = MutableStateFlow(false)
    /** null = off; otherwise a status line ("On (phone is 9.9.0.1)", "Couldn't start: …") */
    val carHotspot = MutableStateFlow<String?>(null)
}

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("carmirror", Context.MODE_PRIVATE)

    val deviceId: String
        get() = sp.getString("deviceId", null) ?: UUID.randomUUID().toString().also { sp.edit().putString("deviceId", it).apply() }

    val deviceSecret: String
        get() = sp.getString("deviceSecret", null) ?: run {
            val b = ByteArray(32).also { SecureRandom().nextBytes(it) }
            Base64.encodeToString(b, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
                .also { sp.edit().putString("deviceSecret", it).apply() }
        }

    var serverUrl: String
        get() = sp.getString("serverUrl", null) ?: BuildConfig.DEFAULT_SERVER
        set(v) = sp.edit().putString("serverUrl", v.trim().trimEnd('/')).apply()

    var favorites: Set<String>?
        get() = sp.getStringSet("favorites", null)
        set(v) = sp.edit().putStringSet("favorites", v).apply()

    /** Per-app car-sized screens whenever Shizuku is running (else the phone screen is mirrored). */
    var appsMode: Boolean
        get() = sp.getBoolean("appsMode2", true)
        set(v) = sp.edit().putBoolean("appsMode2", v).apply()

    /** Switch the hotspot to car mode (9.9.0.x) automatically whenever it's on and Shizuku runs. */
    var autoCarHotspot: Boolean
        get() = sp.getBoolean("autoCarHotspot", true)
        set(v) = sp.edit().putBoolean("autoCarHotspot", v).apply()

    /** Bluetooth address of the car: CarMirror starts when the phone connects to it, stops when it disconnects. */
    var carBluetooth: String?
        get() = sp.getString("carBluetooth", null)
        set(v) = sp.edit().putString("carBluetooth", v).apply()

    var carBluetoothName: String?
        get() = sp.getString("carBluetoothName", null)
        set(v) = sp.edit().putString("carBluetoothName", v).apply()

    var startOnLaunch: Boolean
        get() = sp.getBoolean("startOnLaunch", true)
        set(v) = sp.edit().putBoolean("startOnLaunch", v).apply()
}

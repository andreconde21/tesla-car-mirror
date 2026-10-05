package dev.outsmartis.carmirror

import android.content.Context
import android.util.Log

/**
 * Option sets to start the scrcpy server with, from "everything on" to "most conservative".
 *
 * Some phones abort the server natively (seen: Galaxy M53 on Android 16, "stack corruption
 * detected" right after start). Rather than guess which part of the device stack is at fault,
 * a session that dies before its first frame retries with the next profile, and the first
 * profile that works is remembered for next time.
 */
object ScrcpyProfiles {
    private const val TAG = "CarMirrorProfiles"
    private lateinit var appContext: Context

    data class Profile(val name: String, val overrides: Map<String, String>)

    private val SAFE = mapOf("power_on" to "false", "keep_active" to "false", "cleanup" to "false")
    private val FIXED = SAFE + mapOf("flex_display" to "false")

    @Volatile private var encoders: List<Pair<String, Boolean>>? = null // name, hardware

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val prefs get() = appContext.getSharedPreferences("carmirror", Context.MODE_PRIVATE)

    fun remember(p: Profile) {
        if (prefs.getString("scrcpyProfile", null) != p.name) {
            Log.i(TAG, "remembering profile ${p.name}")
            prefs.edit().putString("scrcpyProfile", p.name).apply()
        }
    }

    /** H.264 encoders on this phone, hardware first (asks the scrcpy server once). */
    private fun encoders(service: IPrivileged): List<Pair<String, Boolean>> {
        encoders?.let { return it }
        val out = runCatching {
            service.runServer(arrayOf(BuildConfig.SCRCPY_VERSION, "list_encoders=true", "cleanup=false", "log_level=info"))
        }.getOrDefault("")
        val re = Regex("""--video-codec=h264\s+--video-encoder=(\S+)(.*)""")
        val list = out.lines().mapNotNull { line ->
            re.find(line)?.let { m -> m.groupValues[1] to !m.groupValues[2].contains("(sw)") }
        }.distinctBy { it.first }.sortedBy { if (it.second) 0 else 1 }
        Log.i(TAG, "h264 encoders: $list")
        encoders = list
        return list
    }

    fun ordered(service: IPrivileged): List<Profile> {
        val all = mutableListOf(Profile("default", emptyMap()), Profile("safe", SAFE), Profile("fixed", FIXED))
        for ((name, hw) in encoders(service)) {
            val extra = mutableMapOf("video_encoder" to name)
            if (!hw) extra["max_fps"] = "30"
            all += Profile("enc:$name", FIXED + extra)
        }
        if (all.none { it.overrides["video_encoder"] == "c2.android.avc.encoder" }) {
            all += Profile("enc:c2.android.avc.encoder", FIXED + mapOf("video_encoder" to "c2.android.avc.encoder", "max_fps" to "30"))
        }
        // the remembered profile first
        val saved = prefs.getString("scrcpyProfile", null)
        val idx = all.indexOfFirst { it.name == saved }
        if (idx > 0) all.add(0, all.removeAt(idx))
        return all
    }
}

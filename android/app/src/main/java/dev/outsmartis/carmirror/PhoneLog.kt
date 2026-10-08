package dev.outsmartis.carmirror

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Phone-side diagnostics sent to the server over the signaling socket, so problems that happen
 * before (or instead of) a working car link are visible too. Crashes are written to a file and
 * sent on the next start.
 */
object PhoneLog {
    private const val TAG = "CarMirrorLog"
    @Volatile var sink: ((String) -> Boolean)? = null
    private val pending = ArrayDeque<String>()
    private lateinit var crashFile: File

    fun init(context: Context) {
        crashFile = File(context.filesDir, "crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                crashFile.writeText("${BuildConfig.VERSION_NAME} thread=${t.name}\n$sw".take(8000))
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun log(msg: String) {
        Log.i(TAG, msg)
        val line = msg.take(1500)
        if (sink?.invoke(line) != true) synchronized(pending) {
            pending.addLast(line)
            while (pending.size > 100) pending.removeFirst()
        }
    }

    /** Called when the server connection opens: flush queued lines and any crash from last run. */
    fun flush() {
        val s = sink ?: return
        if (::crashFile.isInitialized && crashFile.exists()) {
            val crash = runCatching { crashFile.readText() }.getOrNull()
            crashFile.delete()
            if (crash != null) s("CRASH (previous run): $crash")
        }
        val lines = synchronized(pending) { pending.toList().also { pending.clear() } }
        lines.forEach { s(it) }
    }
}

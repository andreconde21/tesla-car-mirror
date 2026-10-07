package dev.outsmartis.carmirror

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface

/**
 * The screen-capture permission (MediaProjection) and the one virtual display it may create.
 * Android 14+ allows a single createVirtualDisplay() per projection, so the display is kept
 * and re-pointed at each new encoder surface instead of being recreated.
 */
object Projection {
    private const val TAG = "CarMirrorProjection"
    private val main = Handler(Looper.getMainLooper())
    @Volatile var projection: MediaProjection? = null
        private set
    private var display: VirtualDisplay? = null
    private val lock = Object()

    val active: Boolean get() = projection != null

    fun grant(context: Context, resultCode: Int, data: Intent) {
        // Android 14+: the service must run as a mediaProjection foreground service first
        MirrorService.instance?.enableProjectionType()
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(resultCode, data) ?: return
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "projection stopped")
                synchronized(lock) {
                    display?.release()
                    display = null
                    if (projection === p) projection = null
                }
                AppState.projection.value = false
            }
        }, main)
        synchronized(lock) {
            projection?.stop()
            projection = p
            display = null
            lock.notifyAll()
        }
        AppState.projection.value = true
        Log.i(TAG, "projection granted")
    }

    /** Ask the user (on the phone) for capture permission. Needs "display over other apps" from the background. */
    fun requestConsent(context: Context) {
        context.startActivity(Intent(context, ConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun await(timeoutMs: Long): MediaProjection? {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (projection == null) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                lock.wait(left)
            }
            return projection
        }
    }

    /** Point the mirror display at [surface] with the given size (creating it the first time). */
    fun attach(surface: Surface, width: Int, height: Int, dpi: Int): Boolean {
        synchronized(lock) {
            val p = projection ?: return false
            val d = display
            if (d == null) {
                display = p.createVirtualDisplay(
                    "CarMirror", width, height, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null,
                ) ?: return false
            } else {
                d.resize(width, height, dpi)
                d.surface = surface
            }
            return true
        }
    }

    /** Unhook [surface] if it is still the display's target (a newer session may have taken over). */
    fun detach(surface: Surface? = null) {
        synchronized(lock) {
            val d = display ?: return
            if (surface == null || d.surface === surface) d.surface = null
        }
    }

    fun stop() {
        synchronized(lock) {
            display?.release()
            display = null
            projection?.stop()
            projection = null
        }
        AppState.projection.value = false
    }
}

/** Transparent activity that shows Android's "start recording/casting" dialog. */
class ConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // the whole screen: "single app" capture would go black when the app changes
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            try {
                Projection.grant(this, resultCode, data)
            } catch (e: Exception) {
                Log.e("CarMirrorProjection", "grant failed", e)
                AppState.lastError.value = "Screen capture failed: ${e.message}"
            }
        } else {
            AppState.lastError.value = "Screen capture was not allowed"
        }
        finish()
    }
}

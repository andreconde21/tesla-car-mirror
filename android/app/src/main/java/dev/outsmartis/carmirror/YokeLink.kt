package dev.outsmartis.carmirror

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * Tells Yoke (the launcher, github.com/andreconde21/yoke-launcher) that the phone is on the car
 * screen, so its smart grayscale keeps the phone, and with it the car, in colour meanwhile.
 * Android's grayscale reaches what the car receives too.
 *
 * An explicit broadcast to Yoke, repeated every few minutes while a car is connected: Yoke lets
 * the flag lapse after ten minutes without one, so a crash here can't leave the phone in
 * colour for good. Nothing happens when Yoke isn't installed.
 */
object YokeLink {
    private const val ACTION = "com.outsmartis.yoke.action.CAR_MIRROR"
    private const val EXTRA_ACTIVE = "active"
    private val PACKAGES = listOf("com.outsmartis.yoke", "com.outsmartis.yoke.debug")
    private const val REPEAT_MS = 4 * 60_000L

    @Volatile private var lastSent = 0L

    /** Call while a car is connected (any rate): sends now and then every few minutes. */
    fun carOn(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (lastSent != 0L && now - lastSent < REPEAT_MS) return
        lastSent = now
        send(context, true)
    }

    fun carOff(context: Context) {
        if (lastSent == 0L) return
        lastSent = 0L
        send(context, false)
    }

    private fun send(context: Context, active: Boolean) {
        for (pkg in PACKAGES) {
            val intent = Intent(ACTION).setPackage(pkg).putExtra(EXTRA_ACTIVE, active)
            runCatching {
                if (Build.VERSION.SDK_INT >= 34) {
                    // Yoke checks the sender is CarMirror; Android 14+ only tells it when asked to
                    context.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
                } else {
                    context.sendBroadcast(intent)
                }
            }
        }
    }
}

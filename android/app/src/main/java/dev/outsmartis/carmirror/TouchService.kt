package dev.outsmartis.carmirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Plays the car's touches on the phone screen. An accessibility service is the one way a
 * normal app (no root, no Shizuku) can tap and swipe in other apps.
 *
 * A finger is a chain of continued strokes: down starts a stroke that "will continue", each
 * batch of moves continues it once the previous piece finished, up ends it. That keeps
 * drags, scrolls and long presses working, not just taps.
 */
class TouchService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var stroke: GestureDescription.StrokeDescription? = null
    private var last: Pair<Float, Float>? = null
    private val pending = ArrayList<Pair<Float, Float>>()
    private var pendingEnd = false
    private var busy = false

    override fun onServiceConnected() {
        instance = this
        AppState.touch.value = true
        Log.i(TAG, "touch service connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        AppState.touch.value = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun down(x: Float, y: Float) = main.post {
        pending.clear()
        pendingEnd = false
        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        val s = GestureDescription.StrokeDescription(path, 0, 1, true)
        stroke = s
        last = x to y
        dispatch(s)
    }

    fun move(x: Float, y: Float) = main.post {
        if (stroke == null) return@post
        pending += x to y
        if (!busy) next()
    }

    fun up(x: Float, y: Float) = main.post {
        if (stroke == null) return@post
        pending += x to y
        pendingEnd = true
        if (!busy) next()
    }

    private fun next() {
        val s = stroke ?: return
        val from = last ?: return
        if (pending.isEmpty() && !pendingEnd) {
            busy = false
            return
        }
        val path = Path().apply {
            moveTo(from.first, from.second)
            if (pending.isEmpty()) lineTo(from.first, from.second)
            for ((px, py) in pending) lineTo(px, py)
        }
        last = pending.lastOrNull() ?: from
        val end = pendingEnd
        val duration = (pending.size * 8L).coerceIn(1, 60)
        pending.clear()
        val c = s.continueStroke(path, 0, duration, !end)
        stroke = if (end) null else c
        if (end) pendingEnd = false
        dispatch(c)
    }

    private fun dispatch(s: GestureDescription.StrokeDescription) {
        busy = true
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(s).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (stroke == null) busy = false else next()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    stroke = null
                    pending.clear()
                    busy = false
                }
            },
            main,
        )
        if (!ok) {
            stroke = null
            busy = false
        }
    }

    fun key(name: String) {
        performGlobalAction(
            when (name) {
                "home" -> GLOBAL_ACTION_HOME
                "recents" -> GLOBAL_ACTION_RECENTS
                else -> GLOBAL_ACTION_BACK
            },
        )
    }

    companion object {
        private const val TAG = "CarMirrorTouch"
        @Volatile var instance: TouchService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, TouchService::class.java).flattenToString()
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}

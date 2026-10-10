package dev.outsmartis.carmirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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

    /** Package of the app in front (launcher included), from window-state events. */
    @Volatile var foreground: String? = null
        private set

    private val homePackages: Set<String> by lazy {
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
    }

    /** The launcher, or CarMirror's own phone screen: neither is worth showing in the car. */
    fun isHome(pkg: String?) = pkg != null && (pkg in homePackages || pkg == packageName)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return
        // let the window list settle, then ask which app owns the active window
        main.removeCallbacks(checkForeground)
        main.postDelayed(checkForeground, 250)
    }

    private val checkForeground = Runnable {
        val pkg = activeAppPackage() ?: return@Runnable
        if (pkg != foreground) {
            Log.i(TAG, "foreground $pkg (home=${isHome(pkg)}, listener=${foregroundListener != null})")
            foreground = pkg
            foregroundListener?.invoke(pkg, isHome(pkg))
        }
    }

    /**
     * Package of the app window in front. Event package names lie (Pixel's home screen reports
     * the Google app, overlays report systemui), the window list doesn't.
     */
    private fun activeAppPackage(): String? = try {
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val w = apps.firstOrNull { it.isActive } ?: apps.firstOrNull { it.isFocused } ?: apps.firstOrNull()
        w?.root?.packageName?.toString()
    } catch (_: Exception) {
        null
    }

    /**
     * Space taken by the status bar and navigation bar(s) right now, in screen pixels
     * (left, top, right, bottom). Zero on a side whose bar is hidden (fullscreen video).
     */
    fun systemBars(dispW: Int, dispH: Int): Rect {
        val out = Rect()
        val b = Rect()
        val list = try { windows } catch (_: Exception) { return out }
        for (w in list) {
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            w.getBoundsInScreen(b)
            val wide = b.width() >= dispW * 0.9
            val tall = b.height() >= dispH * 0.9
            when {
                wide && b.top <= 0 && b.height() < dispH / 5 -> out.top = maxOf(out.top, b.bottom)
                wide && b.bottom >= dispH - 1 && b.height() < dispH / 5 -> out.bottom = maxOf(out.bottom, dispH - b.top)
                tall && b.left <= 0 && b.width() < dispW / 5 -> out.left = maxOf(out.left, b.right)
                tall && b.right >= dispW - 1 && b.width() < dispW / 5 -> out.right = maxOf(out.right, dispW - b.left)
            }
        }
        // Gesture navigation: its bar takes no touches, so it isn't in the window list. If the status
        // bar shows (not fullscreen) and no navigation bar was found, it is the gesture bar at the bottom.
        if (out.top > 0 && out.bottom == 0 && out.left == 0 && out.right == 0) {
            val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (id != 0) out.bottom = resources.getDimensionPixelSize(id)
        }
        return out
    }
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

    // ------------------------------------------------------------ typing (screen mode)

    /**
     * A key from the car's keyboard, applied to the focused text field (screen mode can't inject
     * keys without Shizuku, but an accessibility service can edit the field it is in).
     */
    fun typeKey(key: String, ctrl: Boolean, shift: Boolean) = main.post {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (key == "Escape") {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return@post
        }
        if (node == null) return@post
        val (text, a, b) = fieldState(node)
        when {
            ctrl && key.equals("a", true) -> select(node, 0, text.length)
            ctrl && key.equals("c", true) -> node.performAction(AccessibilityNodeInfo.ACTION_COPY)
            ctrl && key.equals("x", true) -> node.performAction(AccessibilityNodeInfo.ACTION_CUT)
            ctrl && key.equals("v", true) -> node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            ctrl -> {}
            key == "Enter" -> {
                if (!node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) && node.isMultiLine) {
                    replace(node, text, a, b, "\n")
                }
            }
            key == "Backspace" -> when {
                a != b -> replace(node, text, a, b, "")
                a > 0 -> replace(node, text, a - 1, a, "")
            }
            key == "Delete" -> when {
                a != b -> replace(node, text, a, b, "")
                b < text.length -> replace(node, text, a, a + 1, "")
            }
            key == "ArrowLeft" -> if (shift) select(node, a, maxOf(a, b - 1)) else select(node, if (a != b) a else maxOf(0, a - 1))
            key == "ArrowRight" -> if (shift) select(node, a, minOf(text.length, b + 1)) else select(node, if (a != b) b else minOf(text.length, b + 1))
            key == "Home" || key == "ArrowUp" -> if (shift) select(node, a, 0) else select(node, 0)
            key == "End" || key == "ArrowDown" -> if (shift) select(node, a, text.length) else select(node, text.length)
            key == " " || (key.length <= 2 && key !in Typing.NAMED) -> replace(node, text, a, b, key)
        }
    }

    /** Text from voice typing, at the cursor of the focused field. False when no field has focus. */
    fun typeText(s: String): Boolean {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val (text, a, b) = fieldState(node)
        replace(node, text, a, b, s)
        return true
    }

    /** Field text (empty while it shows its hint) and the selection, ordered. */
    private fun fieldState(node: AccessibilityNodeInfo): Triple<String, Int, Int> {
        val text = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        var a = node.textSelectionStart
        var b = node.textSelectionEnd
        if (a < 0 || b < 0) {
            a = text.length
            b = text.length
        }
        return Triple(text, minOf(a, b, text.length), minOf(maxOf(a, b), text.length))
    }

    private fun replace(node: AccessibilityNodeInfo, text: String, a: Int, b: Int, with: String) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text.replaceRange(a, b, with))
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) select(node, a + with.length)
    }

    private fun select(node: AccessibilityNodeInfo, start: Int, end: Int = start) {
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            },
        )
    }

    companion object {
        private const val TAG = "CarMirrorTouch"
        @Volatile var instance: TouchService? = null
            private set

        /** (package, isHomeScreen) whenever the app in front changes. */
        @Volatile var foregroundListener: ((String, Boolean) -> Unit)? = null

        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, TouchService::class.java).flattenToString()
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}

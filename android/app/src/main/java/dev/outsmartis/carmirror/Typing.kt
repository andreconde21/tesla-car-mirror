package dev.outsmartis.carmirror

import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Keys typed on a keyboard plugged into the car (browser `KeyboardEvent.key` names) and text from
 * voice typing, turned into Android key events for per-app screens (injected through Shizuku).
 * Screen mode has no key injection: [TouchService] edits the focused field instead.
 */
object Typing {
    private val kcm: KeyCharacterMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }

    /** Browser key names that aren't characters. */
    val NAMED = mapOf(
        "Enter" to KeyEvent.KEYCODE_ENTER,
        "Backspace" to KeyEvent.KEYCODE_DEL,
        "Delete" to KeyEvent.KEYCODE_FORWARD_DEL,
        "Tab" to KeyEvent.KEYCODE_TAB,
        "Escape" to KeyEvent.KEYCODE_ESCAPE,
        "ArrowLeft" to KeyEvent.KEYCODE_DPAD_LEFT,
        "ArrowRight" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "ArrowUp" to KeyEvent.KEYCODE_DPAD_UP,
        "ArrowDown" to KeyEvent.KEYCODE_DPAD_DOWN,
        "Home" to KeyEvent.KEYCODE_MOVE_HOME,
        "End" to KeyEvent.KEYCODE_MOVE_END,
        "PageUp" to KeyEvent.KEYCODE_PAGE_UP,
        "PageDown" to KeyEvent.KEYCODE_PAGE_DOWN,
        " " to KeyEvent.KEYCODE_SPACE,
    )

    /**
     * Events for one key press. [ctrl] only counts with a letter or a named key (shortcuts such as
     * Ctrl+A or Ctrl+Backspace); an ordinary character comes with whatever Shift it needs.
     */
    fun keyEvents(key: String, ctrl: Boolean, shift: Boolean): List<KeyEvent> {
        val now = SystemClock.uptimeMillis()
        val named = NAMED[key]
        if (named != null || (ctrl && key.length == 1)) {
            val code = named ?: kcm.getEvents(charArrayOf(key.lowercase()[0]))?.firstOrNull()?.keyCode ?: return emptyList()
            var meta = 0
            if (ctrl) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            if (shift && named != null) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            return listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).map { a ->
                KeyEvent(now, now, a, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD)
            }
        }
        return textEvents(key)
    }

    /**
     * Events that type [text]. Characters the virtual key map has (US layout) become key presses;
     * anything else (ç, é, emoji) goes as one ACTION_MULTIPLE event, which text fields insert as is.
     */
    fun textEvents(text: String): List<KeyEvent> {
        if (text.isEmpty()) return emptyList()
        kcm.getEvents(text.toCharArray())?.let { return it.toList() }
        return listOf(KeyEvent(SystemClock.uptimeMillis(), text, KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
    }
}

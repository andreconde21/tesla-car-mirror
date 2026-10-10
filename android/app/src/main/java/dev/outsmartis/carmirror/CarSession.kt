package dev.outsmartis.carmirror

import org.webrtc.DataChannel

/** One stream shown in a car pane: either an app on its own display (Shizuku) or the phone screen. */
interface CarSession {
    val sid: Long
    val pkg: String
    fun attachChannel(dc: DataChannel)
    fun start()
    fun stop()
    fun touch(action: Int, id: Long, x: Int, y: Int, w: Int, h: Int)
    fun scroll(x: Int, y: Int, w: Int, h: Int, dx: Float, dy: Float)
    /** "back", "home" or "recents" */
    fun key(name: String)
    fun resize(w: Int, h: Int)
    fun requestKeyFrame()
    /** New stream settings without reopening the app (quality change, lighter stream). */
    fun reconfigure(w: Int, h: Int, dpi: Int, fps: Int, bitrate: Int)
    /** Show the on-screen keyboard (off when the driver types on a physical keyboard). */
    fun setKeyboard(show: Boolean)
    /** A key from a keyboard plugged into the car: a character or a browser key name ("Enter", "ArrowLeft"…). */
    fun typeKey(key: String, ctrl: Boolean, shift: Boolean)
    /** Text at the cursor (voice typing). False when it surely went nowhere (no text field has focus). */
    fun typeText(text: String): Boolean
    /** Diagnostic output to ship to the car when the session failed. */
    val fullLog: String?
}

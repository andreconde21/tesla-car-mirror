package dev.outsmartis.carmirror;

import android.view.Surface;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.os.ParcelFileDescriptor;

// Runs as the shell user through Shizuku (see PrivilegedService). It only does what needs
// shell rights: a trusted virtual display, launching apps on it and injecting input there.
// Video encoding stays in the app process (it crashes in app_process on some phones).
interface IPrivileged {
    // Shizuku calls this transaction code when the service is unbound/replaced.
    void destroy() = 16777114;

    int uid() = 5;

    // A display of the car's size rendering into `surface` (the app's encoder input). Returns its id.
    int createDisplay(String name, int width, int height, int dpi, in Surface surface) = 10;

    // New size and/or encoder surface for an existing display (apps relayout to the new size).
    void resizeDisplay(int displayId, int width, int height, int dpi, in Surface surface) = 11;

    // Apps on it move back to the phone screen.
    void releaseDisplay(int displayId) = 12;

    // "package/activity", opened (or brought) onto that display.
    boolean launchOnDisplay(String component, int displayId) = 13;

    boolean injectMotion(in MotionEvent event, int displayId) = 14;

    // Returns whether Android accepted it (waits for the result).
    boolean injectKey(in KeyEvent event, int displayId) = 15;

    // Gives input focus to that display (keys like Back go to the focused display).
    void focusDisplay(int displayId) = 16;

    // The phone's audio output (remote submix: the phone itself goes quiet while capturing),
    // as raw PCM 16-bit little-endian, 48 kHz stereo, read from the returned pipe.
    ParcelFileDescriptor startAudioCapture() = 17;

    void stopAudioCapture() = 18;
}

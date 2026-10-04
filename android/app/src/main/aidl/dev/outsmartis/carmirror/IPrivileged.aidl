package dev.outsmartis.carmirror;

// Runs as the shell user through Shizuku (see PrivilegedService).
interface IPrivileged {
    // Shizuku calls this transaction code when the service is unbound/replaced.
    void destroy() = 16777114;

    // Writes the scrcpy server jar where app_process can load it. Returns its path.
    String installServer(in byte[] jar) = 1;

    // Starts a scrcpy server and returns the loopback TCP port the app must connect to.
    // The app sends `secret` (1-byte length + bytes) first; then the socket carries the
    // scrcpy video stream (device -> app) and control messages (app -> device).
    int startSession(int scid, in String[] args, String secret) = 2;

    void stopSession(int scid) = 3;

    // Last lines printed by the scrcpy server for that session (for error reporting).
    String sessionLog(int scid) = 4;

    int uid() = 5;
}

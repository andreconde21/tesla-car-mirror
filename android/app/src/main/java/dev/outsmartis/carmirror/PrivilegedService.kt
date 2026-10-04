package dev.outsmartis.carmirror

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Shizuku "user service": runs in a separate process as the shell user (uid 2000), the
 * same privilege level as `adb shell`. That is what lets the scrcpy server create a
 * virtual display, launch any app on it and inject touches there.
 *
 * This process is a dumb, authenticated pipe: per session it
 *  1. listens on the abstract socket scrcpy connects to (tunnel_forward=false),
 *  2. starts the scrcpy server with app_process,
 *  3. listens on a loopback TCP port for the CarMirror app (which can't talk to the
 *     shell's unix socket itself because of SELinux), checks the shared secret,
 *  4. copies video scrcpy -> app and control app -> scrcpy.
 */
class PrivilegedService : IPrivileged.Stub() {

    private val sessions = ConcurrentHashMap<Int, Session>()

    override fun destroy() {
        sessions.values.forEach { it.stop("service destroyed") }
        exitProcess(0)
    }

    override fun installServer(jar: ByteArray): String {
        val dir = File(DIR)
        dir.mkdirs()
        dir.setReadable(true, false)
        dir.setExecutable(true, false)
        val f = File(dir, "scrcpy-server.jar")
        if (!f.exists() || !sha(f.readBytes()).contentEquals(sha(jar))) {
            f.writeBytes(jar)
        }
        f.setReadable(true, false)
        return f.absolutePath
    }

    override fun startSession(scid: Int, args: Array<String>, secret: String): Int {
        sessions.remove(scid)?.stop("restarted")
        val s = Session(scid, args.toList(), secret) { sessions.remove(scid, it) }
        sessions[scid] = s
        return s.start()
    }

    override fun stopSession(scid: Int) {
        sessions.remove(scid)?.stop("stopped by app")
    }

    override fun sessionLog(scid: Int): String = Session.logs[scid]?.let { synchronized(it) { it.joinToString("\n") } } ?: ""

    override fun uid(): Int = android.os.Process.myUid()

    companion object {
        const val DIR = "/data/local/tmp/carmirror"
        private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    }
}

private class Session(
    private val scid: Int,
    private val args: List<String>,
    private val secret: String,
    private val onEnd: (Session) -> Unit,
) {
    private val socketName = "scrcpy_%08x".format(scid)
    private lateinit var local: LocalServerSocket
    private lateinit var tcp: ServerSocket
    private var proc: Process? = null
    private var jarFile: File? = null
    private val closeables = mutableListOf<AutoCloseable>()
    @Volatile private var stopped = false
    private val log = ArrayDeque<String>()

    init {
        logs[scid] = log
    }

    fun addLog(line: String) {
        synchronized(log) {
            log.addLast(line)
            while (log.size > 200) log.removeFirst()
        }
        Log.i(TAG, "[$socketName] $line")
    }

    fun start(): Int {
        local = LocalServerSocket(socketName)
        tcp = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        tcp.soTimeout = 20_000

        // Each session runs its own copy: scrcpy's cleanup deletes the jar it was started
        // from when it exits, which would break a session starting at that moment.
        val master = File(PrivilegedService.DIR, "scrcpy-server.jar")
        val jarFile = File(PrivilegedService.DIR, "scrcpy-$socketName.jar")
        master.copyTo(jarFile, overwrite = true)
        jarFile.setReadable(true, false)
        this.jarFile = jarFile
        val jar = jarFile.absolutePath
        val cmd = listOf("app_process", "/", "com.genymobile.scrcpy.Server") + args
        addLog("exec: ${cmd.joinToString(" ")}")
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment()["CLASSPATH"] = jar
        val p = pb.start()
        proc = p

        thread(name = "log-$socketName") {
            try {
                p.inputStream.bufferedReader().forEachLine { addLog(it) }
            } catch (_: Exception) {
            }
            val code = try { p.waitFor() } catch (_: Exception) { -1 }
            addLog("server exited ($code)")
            runCatching { jarFile?.delete() }
            stop("server exited ($code)")
        }
        thread(name = "accept-$socketName") { acceptAndPipe() }
        return tcp.localPort
    }

    private fun acceptAndPipe() {
        try {
            val client = tcp.accept()
            closeables += client
            client.tcpNoDelay = true
            client.soTimeout = 0
            val din = DataInputStream(client.getInputStream())
            val len = din.readUnsignedByte()
            val got = ByteArray(len).also { din.readFully(it) }
            if (!MessageDigest.isEqual(got, secret.toByteArray())) {
                addLog("bad secret from client")
                stop("bad secret")
                return
            }
            tcp.close()

            val video = local.accept()
            closeables += video
            val control = local.accept()
            closeables += control
            local.close()
            addLog("scrcpy connected")

            pipe("video-$socketName", video.inputStream, client.getOutputStream())
            pipe("control-$socketName", din, control.outputStream)
            // device -> client control messages (clipboard etc.) are not used: drain them
            thread(name = "drain-$socketName") {
                try {
                    val buf = ByteArray(4096)
                    while (control.inputStream.read(buf) >= 0) { /* discard */ }
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            if (!stopped) addLog("accept failed: $e")
            stop("accept failed")
        }
    }

    private fun pipe(name: String, input: InputStream, output: OutputStream) {
        thread(name = name) {
            val buf = ByteArray(256 * 1024)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (_: Exception) {
            }
            stop("$name ended")
        }
    }

    @Synchronized
    fun stop(reason: String) {
        if (stopped) return
        stopped = true
        addLog("stop: $reason")
        closeables.forEach { runCatching { it.close() } }
        runCatching { tcp.close() }
        // LocalServerSocket.accept() is not interrupted by close() on every Android version:
        // poke it with a connection first.
        runCatching {
            LocalSocket().use { it.connect(LocalSocketAddress(socketName)) }
        }
        runCatching { local.close() }
        proc?.let { p ->
            runCatching { p.destroy() }
            thread {
                if (runCatching { !p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(true)) {
                    runCatching { p.destroyForcibly() }
                }
            }
        }
        onEnd(this)
    }

    companion object {
        private const val TAG = "CarMirrorPriv"
        // logs of the last few sessions, oldest evicted first
        val logs: MutableMap<Int, ArrayDeque<String>> = java.util.Collections.synchronizedMap(
            object : LinkedHashMap<Int, ArrayDeque<String>>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ArrayDeque<String>>) = size > 8
            },
        )
    }
}

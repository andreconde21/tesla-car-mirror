package dev.outsmartis.carmirror

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

enum class ShizukuState { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, BINDING, READY, ERROR }

/** Owns the Shizuku connection and the privileged user service. */
object ShizukuBridge {
    private const val TAG = "CarMirrorShizuku"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST = 4242

    private lateinit var appContext: Context
    @Volatile var service: IPrivileged? = null
        private set
    private var waiters = mutableListOf<CompletableDeferred<IPrivileged?>>()

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(appContext.packageName, PrivilegedService::class.java.name))
            .daemon(false)
            .processNameSuffix("priv")
            .debuggable(BuildConfig.DEBUG)
            // a new build must replace a still-running service from the previous build
            .version(BuildConfig.BUILT_AT.hashCode() and 0x7fffffff)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                Log.w(TAG, "user service binder is dead")
                return
            }
            val s = IPrivileged.Stub.asInterface(binder)
            service = s
            Log.i(TAG, "privileged service connected (uid ${runCatching { s.uid() }.getOrNull()})")
            refresh()
            synchronized(this@ShizukuBridge) {
                waiters.forEach { it.complete(s) }
                waiters.clear()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "privileged service disconnected")
            service = null
            refresh()
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky { refresh(); bindIfPossible() }
        Shizuku.addBinderDeadListener {
            service = null
            refresh()
        }
        Shizuku.addRequestPermissionResultListener { code, result ->
            if (code == PERMISSION_REQUEST && result == PackageManager.PERMISSION_GRANTED) bindIfPossible()
            refresh()
        }
        refresh()
    }

    fun isShizukuInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun currentState(): ShizukuState {
        if (service != null) return ShizukuState.READY
        if (!Shizuku.pingBinder()) return if (isShizukuInstalled()) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
        if (Shizuku.isPreV11()) return ShizukuState.ERROR
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return ShizukuState.NO_PERMISSION
        return ShizukuState.BINDING
    }

    fun refresh() {
        AppState.shizuku.value = currentState()
    }

    fun requestPermission() {
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(PERMISSION_REQUEST)
        }
    }

    fun bindIfPossible() {
        if (service != null) return
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (e: Throwable) {
            Log.e(TAG, "bindUserService failed", e)
        }
        refresh()
    }

    /** Wait (briefly) for the privileged service, binding it if needed. */
    suspend fun awaitService(timeoutMs: Long = 8000): IPrivileged? {
        service?.let { return it }
        val d = CompletableDeferred<IPrivileged?>()
        synchronized(this) { waiters += d }
        bindIfPossible()
        return withTimeoutOrNull(timeoutMs) { d.await() }
    }

    private val serverJar: ByteArray by lazy { appContext.assets.open("scrcpy-server.jar").use { it.readBytes() } }

    /**
     * Copy the bundled scrcpy server where the shell user can run it. Done before every
     * session: scrcpy's cleanup process deletes the jar when a session ends.
     */
    @Synchronized
    fun ensureServerInstalled(s: IPrivileged) {
        s.installServer(serverJar)
    }

    fun describe(state: ShizukuState): String = when (state) {
        ShizukuState.NOT_INSTALLED -> "Shizuku is not installed"
        ShizukuState.NOT_RUNNING -> "Shizuku is installed but not running. Open Shizuku and tap Start."
        ShizukuState.NO_PERMISSION -> "CarMirror needs Shizuku access"
        ShizukuState.BINDING -> "Connecting to Shizuku…"
        ShizukuState.READY -> "Ready"
        ShizukuState.ERROR -> "This Shizuku version is too old. Update Shizuku."
    }
}

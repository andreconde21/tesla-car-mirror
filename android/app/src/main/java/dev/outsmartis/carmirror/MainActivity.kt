package dev.outsmartis.carmirror

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // adb shell am start -n dev.outsmartis.carmirror/.MainActivity --es server http://host:8080
        intent?.getStringExtra("server")?.let { prefs.serverUrl = it }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (prefs.startOnLaunch && !AppState.serviceRunning.value) MirrorService.start(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Screen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ShizukuBridge.refresh()
        ShizukuBridge.bindIfPossible()
    }

    @Composable
    private fun Screen() {
        val shizuku by AppState.shizuku.collectAsState()
        val running by AppState.serviceRunning.collectAsState()
        val server by AppState.server.collectAsState()
        val online by AppState.serverOnline.collectAsState()
        val code by AppState.pairCode.collectAsState()
        val pairedCars by AppState.pairedCars.collectAsState()
        val car by AppState.car.collectAsState()
        val lastError by AppState.lastError.collectAsState()
        val cands by AppState.localCandidates.collectAsState()
        val projectionOn by AppState.projection.collectAsState()
        val shizukuState by AppState.shizuku.collectAsState()
        val touchOnFlow by AppState.touch.collectAsState()
        var touchOn by remember { mutableStateOf(TouchService.isEnabled(this)) }
        var overlayOn by remember { mutableStateOf(Settings.canDrawOverlays(this)) }
        var audioOn by remember { mutableStateOf(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
        var addresses by remember { mutableStateOf(localAddresses()) }
        val allApps = remember { Apps.launchable(this) }
        var favorites by remember { mutableStateOf(Apps.favorites(this, prefs).map { it.pkg }.toSet()) }
        var filter by remember { mutableStateOf("") }
        var showAdvanced by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            while (true) {
                addresses = localAddresses()
                touchOn = touchOnFlow || TouchService.isEnabled(this@MainActivity)
                overlayOn = Settings.canDrawOverlays(this@MainActivity)
                audioOn = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                ShizukuBridge.refresh()
                delay(3000)
            }
        }

        Scaffold { pad ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Spacer(Modifier.padding(top = 8.dp))
                    Text("CarMirror", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Your phone's apps on the car screen", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // 1. Phone setup (no Shizuku needed)
                item {
                    Section("1 · Phone setup") {
                        Step(
                            done = touchOn,
                            title = "Touch from the car",
                            body = "Turn on CarMirror under Settings → Accessibility → Installed apps. " +
                                "If the switch is greyed out: App info → ⋮ (top right) → Allow restricted settings, then try again.",
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Accessibility") }
                                OutlinedButton(onClick = {
                                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                                }) { Text("App info") }
                            }
                        }
                        Step(
                            done = overlayOn,
                            title = "Open apps from the car",
                            body = "Allow \"Display over other apps\", so tapping an app in the car opens it on the phone.",
                        ) {
                            OutlinedButton(onClick = {
                                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                            }) { Text("Allow") }
                        }
                        Step(
                            done = projectionOn,
                            title = "Share the screen",
                            body = if (projectionOn) "Sharing. It stays on until you stop it or stop CarMirror."
                            else "Once per drive. The car also asks for it the first time you open an app; then tap Start now on the phone.",
                        ) {
                            if (projectionOn) {
                                OutlinedButton(onClick = { Projection.stop() }) { Text("Stop sharing") }
                            } else {
                                Button(onClick = {
                                    if (!running) MirrorService.start(this@MainActivity)
                                    Projection.requestConsent(this@MainActivity)
                                }) { Text("Start sharing") }
                            }
                        }
                        Step(
                            done = shizukuState == ShizukuState.READY || audioOn,
                            title = "Sound in the car",
                            body = if (shizukuState == ShizukuState.READY) "Through Shizuku: the phone goes quiet and the car plays the sound."
                            else "Without Shizuku, sound rides on screen sharing and needs the audio permission (Android calls it microphone; CarMirror only captures what apps play).",
                        ) {
                            if (shizukuState != ShizukuState.READY && !audioOn) {
                                OutlinedButton(onClick = { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2) }) { Text("Allow") }
                            }
                        }
                        Step(
                            done = shizukuState == ShizukuState.READY,
                            title = "Optional: car-sized app screens (Shizuku)",
                            body = when (shizukuState) {
                                ShizukuState.READY -> "On: apps open on their own screen at the car's size."
                                ShizukuState.NOT_INSTALLED -> "Install Shizuku and start it once (needs Wi-Fi for Wireless debugging; it then runs until the phone restarts). Without it, the phone screen is mirrored."
                                ShizukuState.NOT_RUNNING -> "Shizuku isn't running (phone restarted?). Start it when you're on Wi-Fi. Until then the phone screen is mirrored."
                                ShizukuState.NO_PERMISSION -> "Allow CarMirror in Shizuku."
                                else -> ShizukuBridge.describe(shizukuState)
                            },
                        ) {
                            when (shizukuState) {
                                ShizukuState.NOT_INSTALLED -> OutlinedButton(onClick = { openShizukuStore() }) { Text("Get Shizuku") }
                                ShizukuState.NOT_RUNNING, ShizukuState.ERROR -> OutlinedButton(onClick = { openShizuku() }) { Text("Open Shizuku") }
                                ShizukuState.NO_PERMISSION -> Button(onClick = { ShizukuBridge.requestPermission() }) { Text("Allow") }
                                else -> {}
                            }
                        }
                    }
                }

                // 2. Car link
                item {
                    Section("2 · Car link") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (running) "Running" else "Stopped", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Switch(checked = running, onCheckedChange = { on ->
                                if (on) MirrorService.start(this@MainActivity) else MirrorService.stop(this@MainActivity)
                            })
                        }
                        Text("Server: $server", style = MaterialTheme.typography.bodySmall)
                        if (car != null) {
                            Text(car!!, color = Ok, fontWeight = FontWeight.SemiBold)
                        }
                        if (running && online && code != null) {
                            Text("Pairing code for the car", style = MaterialTheme.typography.labelLarge)
                            Text(code!!.chunked(3).joinToString(" "), fontSize = 40.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Text(
                                "In the car's browser open ${prefs.serverUrl.removePrefix("https://")} and type this code. " +
                                    "Paired cars: $pairedCars.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { MirrorService.start(this@MainActivity, MirrorService.ACTION_NEW_CODE) }) { Text("New code") }
                                if (pairedCars > 0) {
                                    OutlinedButton(onClick = { MirrorService.start(this@MainActivity, MirrorService.ACTION_UNPAIR_ALL) }) { Text("Unpair all cars") }
                                }
                            }
                        }
                        Text(
                            if (addresses.isEmpty()) "Network: no Wi-Fi/hotspot address. Turn on the hotspot and connect the car to it."
                            else "Phone addresses: " + addresses.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (cands.isNotEmpty()) {
                            Text("Last link candidates: " + cands.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                        }
                        if (lastError != null) {
                            Text("Last problem: $lastError", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // 3. Apps
                item {
                    Section("3 · Apps shown in the car") {
                        Text("${favorites.size} selected", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            label = { Text("Search apps") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                val shown = allApps
                    .filter { filter.isBlank() || it.label.contains(filter, ignoreCase = true) || it.pkg.contains(filter, ignoreCase = true) }
                    .sortedBy { if (it.pkg in favorites) 0 else 1 }
                items(shown, key = { it.pkg }) { app ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = app.pkg in favorites, onCheckedChange = { on ->
                            Apps.setFavorite(prefs, Apps.favorites(this@MainActivity, prefs), app.pkg, on)
                            favorites = Apps.favorites(this@MainActivity, prefs).map { it.pkg }.toSet()
                        })
                        Column {
                            Text(app.label)
                            Text(app.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // 4. Tips & advanced
                item {
                    Section("Tips") {
                        Text(
                            "• The car must be on this phone's hotspot (not its own LTE) for a direct, smooth link.\n" +
                                "• The car shows the phone's screen: turn the phone sideways for a full-width picture (YouTube).\n" +
                                "• Sound plays from the phone: connect it to the car over Bluetooth.\n" +
                                "• Keep the phone charging on long drives: encoding video is work.\n" +
                                "• Apps that block screenshots (banking, Netflix) show black.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!ignoringBatteryOptimizations()) {
                            OutlinedButton(onClick = { requestIgnoreBatteryOptimizations() }) { Text("Let CarMirror run in the background") }
                        }
                        TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "Hide advanced" else "Advanced") }
                        if (showAdvanced) Advanced()
                        Text(
                            "Version ${BuildConfig.VERSION_NAME} · built ${BuildConfig.BUILT_AT.take(16)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.padding(bottom = 24.dp))
                }
            }
        }
    }

    @Composable
    private fun Advanced() {
        var url by remember { mutableStateOf(prefs.serverUrl) }
        var autostart by remember { mutableStateOf(prefs.startOnLaunch) }
        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                prefs.serverUrl = url
                MirrorService.stop(this@MainActivity)
                window.decorView.postDelayed({ MirrorService.start(this@MainActivity) }, 800)
            }) { Text("Save & restart") }
        }
        var appsMode by remember { mutableStateOf(prefs.appsMode) }
        val shizuku by AppState.shizuku.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Per-app screens when Shizuku runs")
                Text(
                    "Each app gets its own screen at the car's exact size (no black bars, split view, phone stays free). " +
                        "Used automatically while Shizuku is running; otherwise the phone screen is mirrored.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = appsMode, onCheckedChange = {
                appsMode = it
                prefs.appsMode = it
                if (it) ShizukuBridge.bindIfPossible()
            })
        }
        if (appsMode) {
            Text("Shizuku: " + ShizukuBridge.describe(shizuku), style = MaterialTheme.typography.bodySmall)
            when (shizuku) {
                ShizukuState.NOT_INSTALLED -> OutlinedButton(onClick = { openShizukuStore() }) { Text("Get Shizuku") }
                ShizukuState.NOT_RUNNING, ShizukuState.ERROR -> OutlinedButton(onClick = { openShizuku() }) { Text("Open Shizuku") }
                ShizukuState.NO_PERMISSION -> OutlinedButton(onClick = { ShizukuBridge.requestPermission() }) { Text("Grant Shizuku access") }
                else -> {}
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Start when the app opens", modifier = Modifier.weight(1f))
            Switch(checked = autostart, onCheckedChange = {
                autostart = it
                prefs.startOnLaunch = it
            })
        }
    }

    @Composable
    private fun Step(done: Boolean, title: String, body: String, action: @Composable () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text((if (done) "✓ " else "○ ") + title, fontWeight = FontWeight.SemiBold, color = if (done) Ok else MaterialTheme.colorScheme.onSurface)
            if (!done || title == "Share the screen" || title.startsWith("Optional")) {
                Text(body, style = MaterialTheme.typography.bodySmall)
                action()
            }
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                content()
            }
        }
    }

    private fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${it.hostAddress} (${ni.name})" } }
    } catch (_: Exception) {
        emptyList()
    }

    private fun openShizuku() {
        val i = packageManager.getLaunchIntentForPackage(ShizukuBridge.SHIZUKU_PACKAGE)
        if (i != null) startActivity(i) else openShizukuStore()
    }

    private fun openShizukuStore() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ShizukuBridge.SHIZUKU_PACKAGE}")))
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases")))
        }
    }

    private fun ignoringBatteryOptimizations(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private companion object {
        val Ok = androidx.compose.ui.graphics.Color(0xFF2ECC71)
    }
}

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
import androidx.compose.foundation.clickable
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
        if (Build.VERSION.SDK_INT >= 33) {
            // notifications for the service; nearby Wi-Fi devices = reaching the car on the hotspot
            val wanted = listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.NEARBY_WIFI_DEVICES)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
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
        val hotspot by AppState.carHotspot.collectAsState()
        val touchOnFlow by AppState.touch.collectAsState()
        var touchOn by remember { mutableStateOf(TouchService.isEnabled(this)) }
        var overlayOn by remember { mutableStateOf(Settings.canDrawOverlays(this)) }
        var audioOn by remember { mutableStateOf(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
        var addresses by remember { mutableStateOf(localAddresses()) }
        val allApps = remember { Apps.launchable(this) }
        var favorites by remember { mutableStateOf(Apps.favorites(this, prefs).map { it.pkg }.toSet()) }
        var filter by remember { mutableStateOf("") }
        var showSetup by remember { mutableStateOf(false) }
        var showPairing by remember { mutableStateOf(false) }
        var showApps by remember { mutableStateOf(false) }
        var showAdvanced by remember { mutableStateOf(false) }
        var carBt by remember { mutableStateOf(prefs.carBluetooth) }
        var pickingCar by remember { mutableStateOf(false) }
        if (pickingCar) {
            val devices = remember { CarBluetooth.bonded(this@MainActivity) }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pickingCar = false },
                title = { Text("Which is the car?") },
                text = {
                    Column {
                        if (devices.isEmpty()) Text("No paired Bluetooth devices found.")
                        devices.forEach { (addr, name) ->
                            TextButton(onClick = {
                                prefs.carBluetooth = addr
                                prefs.carBluetoothName = name
                                carBt = addr
                                pickingCar = false
                            }) { Text(name) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { pickingCar = false }) { Text("Cancel") } },
            )
        }

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

        val shizukuOn = shizukuState == ShizukuState.READY
        // what the car path needs: touch + opening apps always; without Shizuku also screen sharing + sound permission
        val setupDone = touchOn && overlayOn && (shizukuOn || audioOn)
        val carHotspotOn = hotspot?.startsWith("On") == true

        Scaffold { pad ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Spacer(Modifier.padding(top = 8.dp))
                    Text("CarMirror", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }

                // ---- status: one glance tells whether the car will work
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val (headline, color) = when {
                                !running -> "CarMirror is stopped" to MaterialTheme.colorScheme.error
                                car != null -> car!! to Ok
                                !online -> "Connecting to the server…" to MaterialTheme.colorScheme.onSurface
                                !setupDone -> "Finish the setup below" to MaterialTheme.colorScheme.error
                                pairedCars == 0 -> "Pair your car below" to MaterialTheme.colorScheme.onSurface
                                else -> "Ready for the car" to Ok
                            }
                            Text(headline, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = color)
                            StatusLine(
                                ok = carHotspotOn,
                                text = when {
                                    carHotspotOn -> "Hotspot: car mode"
                                    hotspot != null && hotspot!!.startsWith("Couldn") -> "Hotspot: $hotspot"
                                    hotspot != null -> "Hotspot: ${hotspot!!}"
                                    shizukuOn -> "Hotspot: turn it on, CarMirror sets it up for the car"
                                    else -> "Hotspot: normal (start Shizuku for car mode)"
                                },
                            )
                            StatusLine(
                                ok = shizukuOn,
                                text = if (shizukuOn) "Apps on their own car-sized screens" else "Phone screen mirrored (Shizuku off)",
                            )
                            if (!running) {
                                Button(onClick = { MirrorService.start(this@MainActivity) }) { Text("Start") }
                            }
                        }
                    }
                }

                // ---- setup: folds away once done
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Header(
                                title = if (setupDone) "✓ Setup done" else "Setup",
                                open = showSetup || !setupDone,
                                color = if (setupDone) Ok else MaterialTheme.colorScheme.onSurface,
                            ) { showSetup = !showSetup }
                            if (showSetup || !setupDone) {
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
                                    done = shizukuOn,
                                    title = "Shizuku (recommended)",
                                    body = when (shizukuState) {
                                        ShizukuState.READY -> "Running: car hotspot, car-sized app screens and sound all work."
                                        ShizukuState.NOT_INSTALLED -> "Install Shizuku and start it with Wireless debugging (needs Wi-Fi, once after each phone restart). " +
                                            "It enables the car hotspot (the Tesla can then reach the phone directly) and car-sized app screens."
                                        ShizukuState.NOT_RUNNING -> "Not running (phone restarted?). Start it when you're on Wi-Fi."
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
                                if (!shizukuOn) {
                                    Step(
                                        done = audioOn,
                                        title = "Sound in the car (without Shizuku)",
                                        body = "Sound rides on screen sharing and needs the audio permission (Android calls it microphone; " +
                                            "CarMirror only captures what apps play).",
                                    ) {
                                        OutlinedButton(onClick = { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2) }) { Text("Allow") }
                                    }
                                    Step(
                                        done = projectionOn,
                                        title = "Share the screen (without Shizuku)",
                                        body = if (projectionOn) "Sharing until you stop it." else "Once per drive; the phone also asks when the car opens an app.",
                                    ) {
                                        if (projectionOn) {
                                            OutlinedButton(onClick = { Projection.stop() }) { Text("Stop sharing") }
                                        } else {
                                            OutlinedButton(onClick = {
                                                if (!running) MirrorService.start(this@MainActivity)
                                                Projection.requestConsent(this@MainActivity)
                                            }) { Text("Start sharing") }
                                        }
                                    }
                                }
                                Step(
                                    done = carBt != null,
                                    title = "Start with the car (optional)",
                                    body = if (carBt != null) "Starts when the phone connects to ${prefs.carBluetoothName ?: "the car"} over Bluetooth, stops when it disconnects."
                                    else "Pick the car's Bluetooth: CarMirror then starts and stops by itself.",
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = {
                                            if (!CarBluetooth.hasPermission(this@MainActivity)) {
                                                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 3)
                                            } else {
                                                pickingCar = true
                                            }
                                        }) { Text(if (carBt != null) "Change" else "Choose the car") }
                                        if (carBt != null) {
                                            OutlinedButton(onClick = {
                                                prefs.carBluetooth = null
                                                prefs.carBluetoothName = null
                                                carBt = null
                                            }) { Text("Off") }
                                        }
                                    }
                                }
                                if (!ignoringBatteryOptimizations()) {
                                    OutlinedButton(onClick = { requestIgnoreBatteryOptimizations() }) { Text("Let CarMirror run in the background") }
                                }
                            }
                        }
                    }
                }

                // ---- pairing: folds away once a car is paired
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val paired = pairedCars > 0
                            Header(
                                title = if (paired) "✓ Car paired" + if (pairedCars > 1) " ($pairedCars)" else "" else "Pair your car",
                                open = showPairing || !paired,
                                color = if (paired) Ok else MaterialTheme.colorScheme.onSurface,
                            ) { showPairing = !showPairing }
                            if (showPairing || !paired) {
                                if (online && code != null) {
                                    Text(
                                        "In the car's browser open ${prefs.serverUrl.removePrefix("https://")} and type:",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(code!!.chunked(3).joinToString(" "), fontSize = 40.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { MirrorService.start(this@MainActivity, MirrorService.ACTION_NEW_CODE) }) { Text("New code") }
                                        if (paired) {
                                            OutlinedButton(onClick = { MirrorService.start(this@MainActivity, MirrorService.ACTION_UNPAIR_ALL) }) { Text("Unpair all") }
                                        }
                                    }
                                } else {
                                    Text("Server: $server", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                // ---- apps: list only when opened
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Header(title = "Apps in the car (${favorites.size})", open = showApps) { showApps = !showApps }
                            if (showApps) {
                                OutlinedTextField(
                                    value = filter,
                                    onValueChange = { filter = it },
                                    label = { Text("Search apps") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
                if (showApps) {
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
                }

                // ---- advanced & diagnostics
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Header(title = "Advanced", open = showAdvanced) { showAdvanced = !showAdvanced }
                            if (showAdvanced) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("CarMirror running", modifier = Modifier.weight(1f))
                                    Switch(checked = running, onCheckedChange = { on ->
                                        if (on) MirrorService.start(this@MainActivity) else MirrorService.stop(this@MainActivity)
                                    })
                                }
                                if (shizukuOn) {
                                    var auto by remember { mutableStateOf(prefs.autoCarHotspot) }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Car hotspot automatically")
                                            Text(
                                                "When the hotspot comes on, restart it on 9.9.0.x so the Tesla can reach the phone. " +
                                                    "Only one device can join while it's in car mode: turn this off to share the hotspot with others.",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                        Switch(checked = auto, onCheckedChange = {
                                            auto = it
                                            prefs.autoCarHotspot = it
                                        })
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (carHotspotOn) {
                                            OutlinedButton(onClick = { CarHotspot.stop() }) { Text("Back to normal hotspot") }
                                        } else {
                                            OutlinedButton(onClick = { CarHotspot.start() }) { Text("Car hotspot now") }
                                        }
                                    }
                                }
                                Advanced()
                                Text("Server: $server", style = MaterialTheme.typography.bodySmall)
                                Text("Phone addresses: " + addresses.joinToString(", ").ifEmpty { "none" }, style = MaterialTheme.typography.bodySmall)
                                if (cands.isNotEmpty()) Text("Last link candidates: " + cands.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                                if (lastError != null) {
                                    Text("Last problem: $lastError", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    "Version ${BuildConfig.VERSION_NAME} · built ${BuildConfig.BUILT_AT.take(16)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.padding(bottom = 24.dp))
                }
            }
        }
    }

    @Composable
    private fun Header(title: String, open: Boolean, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface, onToggle: () -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { onToggle() },
        ) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = color, modifier = Modifier.weight(1f))
            Text(if (open) "▴" else "▾", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun StatusLine(ok: Boolean, text: String) {
        Text((if (ok) "✓ " else "• ") + text, color = if (ok) Ok else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
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
            if (!done || title.startsWith("Share the screen") || title.startsWith("Shizuku")) {
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

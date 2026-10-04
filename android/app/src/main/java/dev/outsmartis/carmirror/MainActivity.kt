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
        var addresses by remember { mutableStateOf(localAddresses()) }
        val allApps = remember { Apps.launchable(this) }
        var favorites by remember { mutableStateOf(Apps.favorites(this, prefs).map { it.pkg }.toSet()) }
        var filter by remember { mutableStateOf("") }
        var showAdvanced by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            while (true) {
                addresses = localAddresses()
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

                // 1. Shizuku
                item {
                    Section("1 · Phone access (Shizuku)") {
                        Text(ShizukuBridge.describe(shizuku), color = if (shizuku == ShizukuState.READY) Ok else MaterialTheme.colorScheme.onSurface)
                        when (shizuku) {
                            ShizukuState.NOT_INSTALLED -> Button(onClick = { openShizukuStore() }) { Text("Get Shizuku") }
                            ShizukuState.NOT_RUNNING, ShizukuState.ERROR -> Button(onClick = { openShizuku() }) { Text("Open Shizuku") }
                            ShizukuState.NO_PERMISSION -> Button(onClick = { ShizukuBridge.requestPermission() }) { Text("Grant Shizuku access") }
                            else -> {}
                        }
                        if (shizuku != ShizukuState.READY) {
                            Text(
                                "Shizuku gives CarMirror the same rights as a USB debugging session, without root. " +
                                    "Start it once with Wireless debugging; after a phone reboot, open Shizuku and tap Start again.",
                                style = MaterialTheme.typography.bodySmall,
                            )
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
                            "Version ${BuildConfig.VERSION_NAME} · scrcpy ${BuildConfig.SCRCPY_VERSION} · built ${BuildConfig.BUILT_AT.take(16)}",
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Start when the app opens", modifier = Modifier.weight(1f))
            Switch(checked = autostart, onCheckedChange = {
                autostart = it
                prefs.startOnLaunch = it
            })
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

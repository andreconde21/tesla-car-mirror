package dev.outsmartis.carmirror

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Starts CarMirror when the phone connects to the car's Bluetooth and stops it when it
 * disconnects. ACL connect/disconnect broadcasts reach manifest receivers even when the app
 * isn't running.
 */
class CarBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        val car = prefs.carBluetooth ?: return
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        if (device?.address != car) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                Log.i("CarMirrorBt", "car connected: starting")
                try {
                    MirrorService.start(context)
                } catch (e: Exception) {
                    // Android may refuse a background start (battery optimization on): ask with a notification
                    Log.w("CarMirrorBt", "background start refused: $e")
                    notifyTapToStart(context)
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.i("CarMirrorBt", "car disconnected: stopping")
                MirrorService.instance?.stopSelf()
            }
        }
    }

    private fun notifyTapToStart(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("carmirror-start", "Start with the car", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            7,
            android.app.Notification.Builder(context, "carmirror-start")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Connected to the car")
                .setContentText("Tap to start CarMirror")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}

object CarBluetooth {
    fun hasPermission(context: Context) = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Paired Bluetooth devices as (address, name), cars first-ish (named like one). */
    @SuppressLint("MissingPermission")
    fun bonded(context: Context): List<Pair<String, String>> {
        if (!hasPermission(context)) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return adapter.bondedDevices.orEmpty()
            .map { it.address to (it.name ?: it.address) }
            .sortedBy { (_, name) -> if (name.contains("tesla", true) || name.contains("model", true)) 0 else 1 }
    }
}

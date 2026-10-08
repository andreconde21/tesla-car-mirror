package dev.outsmartis.carmirror

import android.app.Application
import org.webrtc.PeerConnectionFactory

class CarMirrorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PhoneLog.init(this)
        // Os.setsockoptIfreq (pinning the relay to the hotspot interface) is a hidden API
        runCatching { org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/system/Os;") }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                // libwebrtc only gathers candidates on interfaces Android's ConnectivityManager
                // knows about. The hotspot (swlan0/ap0) isn't one of them, so without this the
                // phone never offers the one address the car can reach.
                .setFieldTrials("WebRTC-AndroidNetworkMonitor-IsAdapterAvailable/Disabled/")
                .createInitializationOptions(),
        )
        ShizukuBridge.init(this)
    }
}

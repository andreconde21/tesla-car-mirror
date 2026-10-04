package dev.outsmartis.carmirror

import android.app.Application
import org.webrtc.PeerConnectionFactory

class CarMirrorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions(),
        )
        ShizukuBridge.init(this)
    }
}

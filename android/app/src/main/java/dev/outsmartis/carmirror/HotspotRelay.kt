package dev.outsmartis.carmirror

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * Makes the phone reachable from the car over the hotspot even though libwebrtc on
 * Android doesn't gather candidates on the hotspot interface (Samsung swlan0 & co).
 *
 * It listens on a UDP port on all interfaces, which the car reaches at the phone's
 * hotspot address, and forwards every datagram to libwebrtc's own loopback candidate
 * (127.0.0.1:Q), which libwebrtc always gathers. libwebrtc sees the relay as a
 * peer-reflexive remote; the car sees an ordinary host candidate. ICE, DTLS and SCTP pass
 * through unchanged: this only moves bytes.
 */
class HotspotRelay(private val loopbackPort: Int) {
    private val tag = "CarMirrorRelay"
    private val outer = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(0)) // all interfaces, incl. the hotspot one we can't enumerate
        receiveBufferSize = 1 shl 20
        sendBufferSize = 1 shl 20
    }
    private val inner = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)).apply {
        receiveBufferSize = 1 shl 20
        sendBufferSize = 1 shl 20
    }
    private val webrtc = InetSocketAddress(InetAddress.getByName("127.0.0.1"), loopbackPort)
    @Volatile private var car: InetSocketAddress? = null
    @Volatile private var closed = false
    private var sentOk = false
    private var sendFailed = false

    val port: Int get() = outer.localPort

    init {
        thread(name = "relay-in", isDaemon = true) {
            val buf = ByteArray(2048)
            val p = DatagramPacket(buf, buf.size)
            try {
                while (!closed) {
                    p.setData(buf)
                    outer.receive(p)
                    val from = InetSocketAddress(p.address, p.port)
                    if (car != from) {
                        Log.i(tag, "car at $from")
                        PhoneLog.log("relay: first packet from the car at $from")
                        car = from
                    }
                    inner.send(DatagramPacket(buf, p.length, webrtc))
                }
            } catch (e: Exception) {
                if (!closed) Log.w(tag, "relay-in stopped: $e")
            }
        }
        thread(name = "relay-out", isDaemon = true) {
            val buf = ByteArray(2048)
            val p = DatagramPacket(buf, buf.size)
            try {
                while (!closed) {
                    p.setData(buf)
                    inner.receive(p)
                    val dest = car ?: continue
                    try {
                        outer.send(DatagramPacket(buf, p.length, dest))
                        if (!sentOk) {
                            sentOk = true
                            PhoneLog.log("relay: replying to the car at $dest")
                        }
                    } catch (e: Exception) {
                        if (!sendFailed) {
                            sendFailed = true
                            PhoneLog.log("relay: can't reach the car at $dest: $e")
                        }
                    }
                }
            } catch (e: Exception) {
                if (!closed) Log.w(tag, "relay-out stopped: $e")
            }
        }
        Log.i(tag, "relay :${outer.localPort} -> 127.0.0.1:$loopbackPort")
    }

    fun close() {
        closed = true
        runCatching { outer.close() }
        runCatching { inner.close() }
    }

    companion object {
        /** IPv4 addresses the app can see (often not the hotspot one, hence [guessFor]). */
        fun visibleAddresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress!! } }
        } catch (_: Exception) {
            emptyList()
        }

        /**
         * The phone's likely address on the car's subnet. Hotspots put themselves at .1 of
         * the /24 they hand out on every Android build seen so far; a wrong guess costs
         * nothing (one ICE candidate that never answers).
         */
        fun guessFor(carIp: String): String? {
            val parts = carIp.split(".")
            if (parts.size != 4) return null
            val a = parts[0].toIntOrNull() ?: return null
            val b = parts[1].toIntOrNull() ?: return null
            val private = a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
            if (!private) return null
            return "${parts[0]}.${parts[1]}.${parts[2]}.1"
        }
    }
}

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

    /** Pin the relay to the hotspot interface (if found): app routing may send car-bound traffic out over mobile data. */
    fun pinTo(iface: String): Boolean = bindToDevice(outer, iface)
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

        private val TETHER_IFACES = listOf("swlan0", "ap0", "wlan1", "softap0", "swlan1", "wlan2", "ap1")

        /**
         * SO_BINDTODEVICE: sends and receives only on [iface]. Plain Linux, allowed for unprivileged
         * sockets on recent kernels; returns false where it isn't.
         */
        fun bindToDevice(socket: DatagramSocket, iface: String): Boolean = try {
            val pfd = android.os.ParcelFileDescriptor.fromDatagramSocket(socket)
            try {
                val m = android.system.Os::class.java.getMethod(
                    "setsockoptIfreq", java.io.FileDescriptor::class.java, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, String::class.java,
                )
                m.invoke(null, pfd.fileDescriptor, 1 /* SOL_SOCKET */, 25 /* SO_BINDTODEVICE */, iface)
                true
            } finally {
                pfd.close()
            }
        } catch (_: Throwable) {
            false
        }

        /**
         * The interface (and our address on it) through which [carIp] is reachable, found by trying
         * the usual hotspot interface names: Android hides the hotspot from apps' interface lists.
         */
        fun findHotspot(carIp: String): Pair<String, String>? {
            for (name in TETHER_IFACES) {
                val ok = runCatching {
                    DatagramSocket().use { s ->
                        if (!bindToDevice(s, name)) return@use null
                        s.connect(InetAddress.getByName(carIp), 9)
                        (s.localAddress as? Inet4Address)?.hostAddress?.takeIf { it != "0.0.0.0" }
                    }
                }.getOrNull()
                if (ok != null) return name to ok
            }
            return null
        }

        /** Interface names the app can see, for diagnostics. */
        fun describeInterfaces(): String = try {
            NetworkInterface.getNetworkInterfaces().toList().joinToString(", ") { ni ->
                ni.name + ni.inetAddresses.toList().filterIsInstance<Inet4Address>().joinToString("") { "=" + it.hostAddress }
            }
        } catch (e: Exception) {
            "?: $e"
        }

        /**
         * The phone's own address on the way to [carIp]: "connecting" a UDP socket makes the kernel
         * pick the route and source address, without sending anything. That is the hotspot
         * address, even though Android hides the hotspot interface from apps.
         */
        fun addressToward(carIp: String): String? = try {
            DatagramSocket().use { s ->
                s.connect(InetAddress.getByName(carIp), 9)
                (s.localAddress as? Inet4Address)?.hostAddress?.takeIf { it != "0.0.0.0" }
            }
        } catch (_: Exception) {
            null
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

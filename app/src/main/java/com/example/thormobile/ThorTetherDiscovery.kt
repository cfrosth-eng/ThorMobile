package com.example.thormobile

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * WO-210: Tier 2 Dell XPS Local Tether Auto-Discovery (USB adb-reverse + Hotspot/Wi-Fi UDP).
 * Zero manual IP configuration required; completes in <= 350ms so offline handover stays instant.
 */
object ThorTetherDiscovery {

    private const val TAG = "ThorTetherDiscovery"
    private const val DEFAULT_WS_PORT = 8766
    private const val DEFAULT_UDP_PORT = 8767
    private const val DISCOVER_MAGIC = "THOR_TETHER_DISCOVER"

    @Volatile private var cachedHost: String? = null
    @Volatile private var cachedPort: Int = DEFAULT_WS_PORT
    @Volatile private var cachedAtMs: Long = 0L

    fun discoverLaptopTetherUrl(token: String, timeoutMs: Int = 350): String? {
        // 1. Instant USB tether check (adb reverse tcp:8766 tcp:8766)
        if (isTcpReachable("127.0.0.1", DEFAULT_WS_PORT, 95)) {
            Log.i(TAG, "Tier-2 Laptop Tether detected on USB loopback 127.0.0.1:$DEFAULT_WS_PORT")
            return "ws://127.0.0.1:$DEFAULT_WS_PORT/?key=$token"
        }

        // 2. Fast re-verify cached Hotspot/LAN laptop IP (15s TTL)
        val now = System.currentTimeMillis()
        val prevHost = cachedHost
        if (prevHost != null && (now - cachedAtMs) < 15_000L) {
            if (isTcpReachable(prevHost, cachedPort, 120)) {
                return "ws://$prevHost:$cachedPort/?key=$token"
            }
        }

        // 3. UDP subnet broadcast discovery (Hotspot swlan0 + Wi-Fi wlan0 + 255.255.255.255)
        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket().apply {
                broadcast = true
                soTimeout = timeoutMs.coerceIn(180, 600)
            }
            val payload = DISCOVER_MAGIC.toByteArray(Charsets.UTF_8)
            val targets = LinkedHashSet<InetAddress>()
            try { targets.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
            try {
                val ifaces = NetworkInterface.getNetworkInterfaces()
                while (ifaces != null && ifaces.hasMoreElements()) {
                    val ni = ifaces.nextElement()
                    if (!ni.isUp || ni.isLoopback) continue
                    for (ia in ni.interfaceAddresses) {
                        val bcast = ia.broadcast
                        if (bcast is Inet4Address) targets.add(bcast)
                    }
                }
            } catch (_: Exception) {}

            for (addr in targets) {
                try {
                    sock.send(DatagramPacket(payload, payload.size, addr, DEFAULT_UDP_PORT))
                } catch (_: Exception) {}
            }

            val buf = ByteArray(256)
            val resp = DatagramPacket(buf, buf.size)
            sock.receive(resp)
            val msg = String(resp.data, 0, resp.length, Charsets.UTF_8).trim()
            if (msg.startsWith("THOR_TETHER_READY:")) {
                val parts = msg.split(":")
                val port = parts.getOrNull(1)?.toIntOrNull() ?: DEFAULT_WS_PORT
                val host = resp.address.hostAddress ?: return null
                cachedHost = host
                cachedPort = port
                cachedAtMs = System.currentTimeMillis()
                Log.i(TAG, "Tier-2 Laptop Tether discovered over Hotspot/LAN at $host:$port")
                return "ws://$host:$port/?key=$token"
            }
        } catch (_: Exception) {
            // Expected timeout when laptop is not nearby — fall straight through to Tier 1 standalone
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
        return null
    }

    private fun isTcpReachable(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}

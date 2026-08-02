package com.mazeconnect.core.discovery

import com.mazeconnect.core.Limits
import com.mazeconnect.core.PROTOCOL_VERSION
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

/** A device seen on the LAN. Not necessarily paired, and never trusted. */
data class DiscoveredDevice(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val address: InetAddress,
    val port: Int,
    val lastSeenMillis: Long,
)

/**
 * UDP multicast presence beacon.
 *
 * Everything this produces is UNAUTHENTICATED and is a hint for the UI only.
 * A beacon can be spoofed by anyone on the LAN, so the name and id it
 * carries are display strings, never authorisation. The only thing acted on
 * is the address and port, and only to *attempt* a connection that must then
 * pass mutual TLS and pinning.
 */
class Beacon(private val scope: CoroutineScope) {

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private var socket: MulticastSocket? = null
    private var listenJob: Job? = null
    private var announceJob: Job? = null

    private val seen = mutableMapOf<String, DiscoveredDevice>()
    private val lastAccepted = mutableMapOf<String, Long>()

    private var deviceId = ""
    private var deviceName = ""
    private var deviceType = "mobile"
    private var servicePort = 0

    fun start(deviceId: String, deviceName: String, deviceType: String, servicePort: Int) {
        stop()
        this.deviceId = deviceId
        this.deviceName = deviceName
        this.deviceType = deviceType
        this.servicePort = servicePort

        val group = InetAddress.getByName(MULTICAST_GROUP)
        socket = try {
            MulticastSocket(PORT).apply {
                reuseAddress = true
                @Suppress("DEPRECATION")
                joinGroup(group)
            }
        } catch (_: Exception) {
            // Multicast is filtered on plenty of networks. Not fatal: the UI
            // still offers pairing by address.
            null
        }

        listenJob = scope.launch(Dispatchers.IO) { listenLoop() }
        announceJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                announce()
                delay(ANNOUNCE_INTERVAL_MS)
            }
        }
    }

    /**
     * Rebind after a network change.
     *
     * A MulticastSocket is bound to whatever interface existed when it was
     * opened. After Wi-Fi drops and returns, that binding is stale: the
     * socket survives, announcements appear to be sent, and nothing arrives.
     * Restarting is the only reliable fix, and it is cheap.
     *
     * Does nothing before the first start(), which has not chosen an identity
     * to announce yet.
     */
    fun refresh() {
        if (deviceId.isEmpty()) return
        start(deviceId, deviceName, deviceType, servicePort)
    }

    fun stop() {
        listenJob?.cancel()
        announceJob?.cancel()
        listenJob = null
        announceJob = null
        runCatching { socket?.close() }
        socket = null
        seen.clear()
        lastAccepted.clear()
        _devices.value = emptyList()
    }

    suspend fun announce() = withContext(Dispatchers.IO) {
        val sock = socket ?: return@withContext
        val payload = JSONObject().apply {
            put("v", PROTOCOL_VERSION)
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("deviceType", deviceType)
            put("port", servicePort)
        }.toString().toByteArray(Charsets.UTF_8)

        runCatching {
            sock.send(
                DatagramPacket(
                    payload,
                    payload.size,
                    InetAddress.getByName(MULTICAST_GROUP),
                    PORT,
                )
            )
        }
    }

    private suspend fun listenLoop() {
        val sock = socket ?: return
        val buffer = ByteArray(Limits.MAX_BEACON_DATAGRAM + 1)

        while (scope.isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            val received = runCatching { sock.receive(packet); true }.getOrDefault(false)
            if (!received) break

            // Anything bigger than a beacon has no business here.
            if (packet.length == 0 || packet.length > Limits.MAX_BEACON_DATAGRAM) continue

            val device = parse(packet) ?: continue
            seen[device.deviceId] = device
            pruneAndPublish()
        }
    }

    private fun parse(packet: DatagramPacket): DiscoveredDevice? {
        val obj = try {
            JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        }

        if (obj.optInt("v", -1) != PROTOCOL_VERSION) return null

        val id = obj.bounded("deviceId", Limits.MAX_DEVICE_ID_CHARS) ?: return null
        val name = obj.bounded("deviceName", Limits.MAX_DEVICE_NAME_CHARS) ?: return null
        val type = obj.bounded("deviceType", 16) ?: return null
        if (type != "desktop" && type != "mobile") return null
        if (id == deviceId) return null // our own announcement echoed back

        val port = obj.optInt("port", -1)
        if (port !in 1..65535) return null

        // Per-source rate limit keyed on the claimed id *and* the source
        // address, so spoofing one field alone does not bypass it.
        val now = System.currentTimeMillis()
        val key = "$id@${packet.address.hostAddress}"
        val last = lastAccepted[key]
        if (last != null && now - last < MIN_ACCEPT_INTERVAL_MS) return null
        lastAccepted[key] = now

        return DiscoveredDevice(id, name, type, packet.address, port, now)
    }

    private fun pruneAndPublish() {
        val now = System.currentTimeMillis()
        seen.entries.removeAll { now - it.value.lastSeenMillis > STALE_AFTER_MS }
        _devices.value = seen.values.sortedByDescending { it.lastSeenMillis }
    }

    private fun JSONObject.bounded(key: String, maxChars: Int): String? {
        val value = opt(key) as? String ?: return null
        if (value.isEmpty() || value.length > maxChars) return null
        if (value.any { it.code < 0x20 || it.code == 0x7F || it.code in 0x80..0x9F }) return null
        return value
    }

    companion object {
        const val PORT = 38271
        const val MULTICAST_GROUP = "239.255.83.10"

        private const val ANNOUNCE_INTERVAL_MS = 5_000L
        // Roughly three missed announcements.
        private const val STALE_AFTER_MS = 17_000L
        private const val MIN_ACCEPT_INTERVAL_MS = 1_000L
    }
}

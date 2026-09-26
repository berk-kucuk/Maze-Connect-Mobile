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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

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

    /**
     * Guards every mutable field below.
     *
     * Not optional: [start]/[stop]/[refresh] are called from the
     * ConnectivityManager callback thread (a network change) and from the
     * main thread, while [listenLoop] writes `seen` from an IO thread. The
     * maps here are plain HashMaps, so a Wi-Fi change landing mid-receive
     * used to run `stop()`'s `seen.clear()` concurrently with
     * `pruneAndPublish()`'s `removeAll` — a ConcurrentModificationException
     * thrown inside a bare `scope.launch`, which is an uncaught exception,
     * which is the process dying with the app in the background.
     */
    private val lock = Any()

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

        val opened = try {
            MulticastSocket(PORT).apply {
                // Needed to send the subnet-broadcast copy in announce().
                broadcast = true
                joinGroupOnEveryInterface(this)
            }
        } catch (_: Exception) {
            // Multicast is filtered on plenty of networks. Not fatal: the UI
            // still offers pairing by address.
            null
        }

        synchronized(lock) {
            this.deviceId = deviceId
            this.deviceName = deviceName
            this.deviceType = deviceType
            this.servicePort = servicePort
            socket = opened

            // Each loop is handed *its* socket rather than reading the field.
            // A restart that replaces the socket must not leave the old loop
            // publishing into the new generation's state.
            listenJob = scope.launch(Dispatchers.IO) { listenLoop(opened ?: return@launch) }
            announceJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    announce()
                    delay(ANNOUNCE_INTERVAL_MS)
                }
            }
        }
    }

    /**
     * Join the discovery group on **every** usable interface, not on "the
     * default" one.
     *
     * `joinGroup(InetAddress)` — the deprecated one-argument form — leaves
     * the choice of interface to the kernel, and on Android that choice is
     * routinely not Wi-Fi: a phone commonly has several interfaces up at
     * once (wlan0, rmnet/mobile data, a VPN's tun) and the multicast default
     * is not the one carrying the LAN. The failure this produces is
     * asymmetric and therefore very confusing to read: **sending** still
     * works, because an outgoing datagram follows the ordinary route, so the
     * computer sees the phone and lists it as pairable — while the phone
     * receives nothing at all and shows an empty device list. That is
     * exactly the shape of the reported fault.
     *
     * The desktop client has always done it this way (`Beacon.cpp` loops
     * over `QNetworkInterface::allInterfaces()`); this is the mobile half
     * catching up.
     *
     * `supportsMulticast()` is deliberately *not* used as a filter. Android
     * reports it inconsistently per device and vendor, and a false negative
     * there would silently skip the one interface that matters. Attempting
     * the join and letting it fail is both cheaper and more honest.
     *
     * Falls back to the old single-interface join only if every explicit
     * attempt failed, so this can never be worse than what it replaced.
     */
    private fun joinGroupOnEveryInterface(sock: MulticastSocket) {
        val group = InetAddress.getByName(MULTICAST_GROUP)
        val groupAddress = InetSocketAddress(group, PORT)

        var joined = 0
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
        while (interfaces != null && interfaces.hasMoreElements()) {
            val nif = interfaces.nextElement()
            val usable = runCatching { nif.isUp && !nif.isLoopback }.getOrDefault(false)
            if (!usable) continue
            // IPv6-only and address-less interfaces have nothing to do with
            // this group; skipping them keeps the log quiet on a phone that
            // has half a dozen of them.
            val hasIpv4 = nif.inetAddresses.asSequence()
                .any { it is Inet4Address && !it.isLoopbackAddress }
            if (!hasIpv4) continue
            if (runCatching { sock.joinGroup(groupAddress, nif) }.isSuccess) joined++
        }

        if (joined == 0) {
            @Suppress("DEPRECATION")
            sock.joinGroup(group)
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
        val (id, name, type, port) = synchronized(lock) {
            if (deviceId.isEmpty()) return
            Identity(deviceId, deviceName, deviceType, servicePort)
        }
        start(id, name, type, port)
    }

    /** The four fields [refresh] needs, read as one under the lock. */
    private data class Identity(
        val deviceId: String,
        val deviceName: String,
        val deviceType: String,
        val servicePort: Int,
    )

    fun stop() {
        synchronized(lock) {
            listenJob?.cancel()
            announceJob?.cancel()
            listenJob = null
            announceJob = null
            // Closing is what actually unblocks listenLoop's receive(); the
            // cancel above only takes effect at a suspension point, and a
            // blocking receive is not one.
            runCatching { socket?.close() }
            socket = null
            seen.clear()
            lastAccepted.clear()
        }
        _devices.value = emptyList()
    }

    suspend fun announce() = withContext(Dispatchers.IO) {
        // Socket and identity read as one, so an announcement can never be
        // built from the old identity and sent on the new socket.
        val (sock, payload) = synchronized(lock) {
            val current = socket ?: return@withContext
            current to JSONObject().apply {
                put("v", PROTOCOL_VERSION)
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                put("deviceType", deviceType)
                put("port", servicePort)
            }.toString().toByteArray(Charsets.UTF_8)
        }

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

        // The same announcement as a broadcast on each Wi-Fi/Ethernet subnet.
        // Many home routers drop multicast between their Wi-Fi and Ethernet
        // sides (IGMP snooping with no querier), and a subnet broadcast
        // crosses them — which is the difference between the computer seeing
        // this phone and not. Tunnels are skipped: a phone VPN would
        // otherwise carry the announcement off the LAN entirely.
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
        while (interfaces != null && interfaces.hasMoreElements()) {
            val nif = interfaces.nextElement()
            val usable = runCatching {
                nif.isUp && !nif.isLoopback && !nif.isPointToPoint && !nif.isVirtual &&
                    VPN_PREFIXES.none { nif.name.startsWith(it) }
            }.getOrDefault(false)
            if (!usable) continue
            for (address in nif.interfaceAddresses) {
                val broadcast = address.broadcast ?: continue
                runCatching { sock.send(DatagramPacket(payload, payload.size, broadcast, PORT)) }
            }
        }
    }

    private suspend fun listenLoop(sock: MulticastSocket) {
        val buffer = ByteArray(Limits.MAX_BEACON_DATAGRAM + 1)

        while (scope.isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            val received = runCatching { sock.receive(packet); true }.getOrDefault(false)
            if (!received) break

            // Anything bigger than a beacon has no business here.
            if (packet.length == 0 || packet.length > Limits.MAX_BEACON_DATAGRAM) continue

            val published = synchronized(lock) {
                // A restart replaced the socket under us: this loop belongs to
                // the previous generation and must not write into the new
                // one's state.
                if (socket !== sock) return
                val device = parse(packet)
                if (device == null) null else {
                    seen[device.deviceId] = device
                    pruneLocked()
                }
            } ?: continue
            _devices.value = published
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

    /** Caller must hold [lock]. Returns the list to publish, so the
     *  StateFlow write itself happens outside the lock. */
    private fun pruneLocked(): List<DiscoveredDevice> {
        val now = System.currentTimeMillis()
        seen.entries.removeAll { now - it.value.lastSeenMillis > STALE_AFTER_MS }
        // The rate-limit table is keyed by id *and* source address, so on a
        // busy or hostile LAN it grows without bound in a service meant to
        // run for days. Same staleness rule as `seen`.
        lastAccepted.entries.removeAll { now - it.value > STALE_AFTER_MS }
        return seen.values.sortedByDescending { it.lastSeenMillis }
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

/** Interface name prefixes that are tunnels, not the LAN. */
private val VPN_PREFIXES = listOf("tun", "tap", "wg", "ppp", "ipsec", "rmnet", "clat")

package com.mazeconnect.core.pairing

import android.content.Context
import com.mazeconnect.core.crypto.Fingerprint
import com.mazeconnect.core.crypto.Sas
import com.mazeconnect.core.protocol.Capability
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

data class PairedDevice(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val publicKey: ByteArray,
    val pairedAtEpochSeconds: Long,
    val enabledCapabilities: Set<Capability> = Capability.DEFAULT_ENABLED,
    // Where we last successfully reached this device. A reconnect hint, not
    // a trust anchor — the address alone opens nothing; every reconnect
    // still runs the full mutual-TLS handshake and key pin check. Kept so a
    // stale or momentarily-missed discovery beacon (background throttling,
    // a dropped multicast packet) does not strand a computer that is still
    // sitting at the same address it always is.
    val lastAddress: String? = null,
    val lastPort: Int? = null,
) {
    val isValid: Boolean
        get() = deviceId.isNotEmpty() && Sas.isPlausiblePublicKey(publicKey)

    val fingerprint: String get() = Fingerprint.hexOf(publicKey)

    // Every field, for the same reason as PendingPairing: a list of these
    // is exposed through a StateFlow, and an equals() keyed only on the
    // public key would suppress the update after renaming a device or
    // toggling one of its capabilities.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairedDevice) return false
        return deviceId == other.deviceId &&
            deviceName == other.deviceName &&
            deviceType == other.deviceType &&
            pairedAtEpochSeconds == other.pairedAtEpochSeconds &&
            enabledCapabilities == other.enabledCapabilities &&
            publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + deviceType.hashCode()
        result = 31 * result + pairedAtEpochSeconds.hashCode()
        result = 31 * result + enabledCapabilities.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
}

/**
 * Persistent record of paired devices, and the authority on whether a
 * presented key is trusted.
 *
 * This store is the *only* thing that grants trust. A certificate that
 * chains to nothing, has expired, or names anything at all is irrelevant;
 * what matters is whether its public key is byte-for-byte one we pinned.
 *
 * Stored in the app's private data directory (not shared preferences and
 * not external storage), so no other app can read or edit which keys this
 * device trusts.
 *
 * Every accessor is synchronised, because the backing list is genuinely
 * reached from four threads at once: the UI, the link read loops, the
 * connectivity callback that drives reconnects, and — via
 * [com.mazeconnect.core.transport.PinnedTrustManager] — the TLS handshake
 * itself. An unsynchronised ArrayList there is not a theoretical race: a
 * reconnect writing `lastAddress` while a handshake iterates for the pin
 * throws ConcurrentModificationException on a thread with no handler above
 * it, which takes the whole process down.
 */
class PairedDeviceStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val devices = mutableListOf<PairedDevice>()

    @Synchronized
    fun load(): Boolean {
        devices.clear()
        if (!file.exists()) return true // nothing paired yet is normal

        var migrated = false
        return try {
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val key = try {
                    Base64.getDecoder().decode(obj.optString("publicKey"))
                } catch (_: IllegalArgumentException) {
                    continue
                }
                val caps = obj.optJSONArray("enabledCapabilities")
                val capNames = buildList {
                    if (caps != null) for (j in 0 until caps.length()) add(caps.optString(j))
                }
                // Capabilities this build has that the record never heard of
                // are granted, as everything is at pairing. The set used to be
                // frozen at pairing time, so media control (0.14.0) stayed off
                // for every computer paired before it — and the Media screen
                // waited forever. Only capabilities *new to the record* are
                // added, so one the user switched off stays off.
                val known = obj.optJSONArray("knownCapabilities")?.let { arr ->
                    Capability.fromNames((0 until arr.length()).map { arr.optString(it) })
                } ?: Capability.LEGACY_KNOWN
                val added = Capability.SUPPORTED - known
                if (added.isNotEmpty()) migrated = true
                val device = PairedDevice(
                    deviceId = obj.optString("deviceId"),
                    deviceName = obj.optString("deviceName"),
                    deviceType = obj.optString("deviceType"),
                    publicKey = key,
                    pairedAtEpochSeconds = obj.optLong("pairedAt"),
                    enabledCapabilities = Capability.fromNames(capNames) + added,
                    lastAddress = obj.optString("lastAddress").takeIf { it.isNotEmpty() },
                    lastPort = obj.optInt("lastPort", -1).takeIf { it in 1..65535 },
                )
                // A record we cannot fully validate is dropped rather than
                // loaded half-trusted: a truncated key must never become a pin.
                if (device.isValid) devices.add(device)
            }
            if (migrated) save()
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun save(): Boolean = try {
        val array = JSONArray()
        devices.forEach { device ->
            array.put(
                JSONObject().apply {
                    put("deviceId", device.deviceId)
                    put("deviceName", device.deviceName)
                    put("deviceType", device.deviceType)
                    put("publicKey", Base64.getEncoder().encodeToString(device.publicKey))
                    put("pairedAt", device.pairedAtEpochSeconds)
                    put(
                        "enabledCapabilities",
                        JSONArray(Capability.toNames(device.enabledCapabilities)),
                    )
                    put("knownCapabilities", JSONArray(Capability.toNames(Capability.SUPPORTED)))
                    if (device.lastAddress != null && device.lastPort != null) {
                        put("lastAddress", device.lastAddress)
                        put("lastPort", device.lastPort)
                    }
                }
            )
        }
        // Write to a temp file then rename, so a crash mid-write leaves the
        // old pin list intact rather than a truncated one.
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(array.toString())
        temp.renameTo(file)
    } catch (_: Exception) {
        false
    }

    @Synchronized
    fun all(): List<PairedDevice> = devices.toList()

    val count: Int @Synchronized get() = devices.size

    /** Is this exact public key pinned? Compared in constant time. */
    @Synchronized
    fun isTrusted(publicKey: ByteArray): Boolean {
        if (!Sas.isPlausiblePublicKey(publicKey)) return false
        return devices.any { Fingerprint.equals(it.publicKey, publicKey) }
    }

    @Synchronized
    fun forKey(publicKey: ByteArray): PairedDevice? {
        if (!Sas.isPlausiblePublicKey(publicKey)) return null
        return devices.firstOrNull { Fingerprint.equals(it.publicKey, publicKey) }
    }

    @Synchronized
    fun forId(deviceId: String): PairedDevice? = devices.firstOrNull { it.deviceId == deviceId }

    /**
     * Record a newly paired device.
     *
     * Refuses if a *different* key is already pinned for this deviceId.
     * Silently re-pinning would let a peer that learned an id displace the
     * real device — exactly the substitution pairing exists to prevent.
     * Re-pairing requires an explicit [remove] first.
     */
    @Synchronized
    fun add(device: PairedDevice): Boolean {
        if (!device.isValid) return false
        if (devices.any { Fingerprint.equals(it.publicKey, device.publicKey) }) return false
        if (devices.any { it.deviceId == device.deviceId }) return false
        devices.add(device)
        return save()
    }

    /** Updates mutable metadata only. Never changes the pinned key itself. */
    @Synchronized
    fun update(publicKey: ByteArray, deviceName: String, capabilities: Set<Capability>): Boolean {
        val index = devices.indexOfFirst { Fingerprint.equals(it.publicKey, publicKey) }
        if (index < 0) return false
        devices[index] = devices[index].copy(
            deviceName = deviceName,
            enabledCapabilities = capabilities,
        )
        return save()
    }

    /**
     * Remember where a reconnect just succeeded. Metadata only — never
     * touches the pinned key, never validates anything, and a miss (device
     * unpaired meanwhile) is silently ignored rather than treated as an
     * error worth surfacing.
     */
    @Synchronized
    fun updateLastAddress(deviceId: String, address: String, port: Int): Boolean {
        val index = devices.indexOfFirst { it.deviceId == deviceId }
        if (index < 0) return false
        val current = devices[index]
        if (current.lastAddress == address && current.lastPort == port) return true
        devices[index] = current.copy(lastAddress = address, lastPort = port)
        return save()
    }

    @Synchronized
    fun remove(publicKey: ByteArray): Boolean {
        val removed = devices.removeAll { Fingerprint.equals(it.publicKey, publicKey) }
        return if (removed) save() else false
    }

    @Synchronized
    fun clear() {
        devices.clear()
        save()
    }

    companion object {
        private const val FILE_NAME = "paired-devices.json"
    }
}

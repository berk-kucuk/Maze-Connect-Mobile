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
 */
class PairedDeviceStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val devices = mutableListOf<PairedDevice>()

    fun load(): Boolean {
        devices.clear()
        if (!file.exists()) return true // nothing paired yet is normal

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
                val device = PairedDevice(
                    deviceId = obj.optString("deviceId"),
                    deviceName = obj.optString("deviceName"),
                    deviceType = obj.optString("deviceType"),
                    publicKey = key,
                    pairedAtEpochSeconds = obj.optLong("pairedAt"),
                    enabledCapabilities = Capability.fromNames(capNames),
                )
                // A record we cannot fully validate is dropped rather than
                // loaded half-trusted: a truncated key must never become a pin.
                if (device.isValid) devices.add(device)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

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

    fun all(): List<PairedDevice> = devices.toList()

    val count: Int get() = devices.size

    /** Is this exact public key pinned? Compared in constant time. */
    fun isTrusted(publicKey: ByteArray): Boolean {
        if (!Sas.isPlausiblePublicKey(publicKey)) return false
        return devices.any { Fingerprint.equals(it.publicKey, publicKey) }
    }

    fun forKey(publicKey: ByteArray): PairedDevice? {
        if (!Sas.isPlausiblePublicKey(publicKey)) return null
        return devices.firstOrNull { Fingerprint.equals(it.publicKey, publicKey) }
    }

    fun forId(deviceId: String): PairedDevice? = devices.firstOrNull { it.deviceId == deviceId }

    /**
     * Record a newly paired device.
     *
     * Refuses if a *different* key is already pinned for this deviceId.
     * Silently re-pinning would let a peer that learned an id displace the
     * real device — exactly the substitution pairing exists to prevent.
     * Re-pairing requires an explicit [remove] first.
     */
    fun add(device: PairedDevice): Boolean {
        if (!device.isValid) return false
        if (devices.any { Fingerprint.equals(it.publicKey, device.publicKey) }) return false
        if (devices.any { it.deviceId == device.deviceId }) return false
        devices.add(device)
        return save()
    }

    /** Updates mutable metadata only. Never changes the pinned key itself. */
    fun update(publicKey: ByteArray, deviceName: String, capabilities: Set<Capability>): Boolean {
        val index = devices.indexOfFirst { Fingerprint.equals(it.publicKey, publicKey) }
        if (index < 0) return false
        devices[index] = devices[index].copy(
            deviceName = deviceName,
            enabledCapabilities = capabilities,
        )
        return save()
    }

    fun remove(publicKey: ByteArray): Boolean {
        val removed = devices.removeAll { Fingerprint.equals(it.publicKey, publicKey) }
        return if (removed) save() else false
    }

    fun clear() {
        devices.clear()
        save()
    }

    companion object {
        private const val FILE_NAME = "paired-devices.json"
    }
}

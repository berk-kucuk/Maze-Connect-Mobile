package com.mazeconnect.core.device

import org.json.JSONObject

/**
 * What this phone tells a paired computer about itself.
 *
 * Deliberately short. It is what a person glances at on the computer to answer
 * "is my phone charging / nearly full / on silent / still on Wi-Fi" — not a
 * device inventory. There is no Wi-Fi network name (that needs location
 * permission and says where the phone is), no IMEI, no phone number, no
 * account, no app list. Every field is optional: one this phone could not read
 * is left out, and the computer shows it as unknown rather than as zero.
 *
 * Plain data with a pure [toJson], so the wire shape is testable on the JVM;
 * [PhoneStatusCollector] is the Android half that fills it in.
 */
data class PhoneReading(
    val batteryLevel: Int? = null,
    val charging: Boolean? = null,
    /** "ac", "usb", "wireless", "dock" or "none". */
    val plug: String? = null,
    /** Tenths of a degree Celsius, as Android reports it. */
    val batteryTenthsC: Int? = null,
    /** "good", "overheat", "dead", "cold", "overvoltage", "failure", "unknown". */
    val batteryHealth: String? = null,
    val storageFreeBytes: Long? = null,
    val storageTotalBytes: Long? = null,
    val memoryAvailableBytes: Long? = null,
    val memoryTotalBytes: Long? = null,
    /** "wifi", "cellular", "ethernet", "vpn" or "none". */
    val network: String? = null,
    /** 0-4 bars, Wi-Fi only. */
    val signal: Int? = null,
    val metered: Boolean? = null,
    /** "normal", "vibrate" or "silent". */
    val ringer: String? = null,
    val doNotDisturb: Boolean? = null,
    val powerSave: Boolean? = null,
    val screenOn: Boolean? = null,
    val model: String? = null,
    val manufacturer: String? = null,
    val androidRelease: String? = null,
    val uptimeMs: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        val battery = JSONObject().apply {
            batteryLevel?.takeIf { it in 0..100 }?.let { put("level", it) }
            charging?.let { put("charging", it) }
            plug?.takeIf { it in PLUGS }?.let { put("plug", it) }
            batteryTenthsC?.takeIf { it in -400..1000 }?.let { put("temperature", it) }
            batteryHealth?.takeIf { it in HEALTH }?.let { put("health", it) }
        }
        if (battery.length() > 0) put("battery", battery)

        capacity(storageFreeBytes, storageTotalBytes)?.let { (free, total) ->
            put("storage", JSONObject().put("free", free).put("total", total))
        }
        capacity(memoryAvailableBytes, memoryTotalBytes)?.let { (free, total) ->
            put("memory", JSONObject().put("available", free).put("total", total))
        }

        val net = JSONObject().apply {
            network?.takeIf { it in NETWORKS }?.let { put("type", it) }
            signal?.takeIf { it in 0..4 }?.let { put("signal", it) }
            metered?.let { put("metered", it) }
        }
        if (net.length() > 0) put("network", net)

        ringer?.takeIf { it in RINGERS }?.let { put("ringer", it) }
        doNotDisturb?.let { put("dnd", it) }
        powerSave?.let { put("powerSave", it) }
        screenOn?.let { put("screenOn", it) }
        model?.let(::label)?.let { put("model", it) }
        manufacturer?.let(::label)?.let { put("manufacturer", it) }
        androidRelease?.let(::label)?.let { put("android", it) }
        uptimeMs?.takeIf { it >= 0 }?.let { put("uptimeMs", it) }
    }

    companion object {
        val PLUGS = setOf("ac", "usb", "wireless", "dock", "none")
        val HEALTH = setOf("good", "overheat", "dead", "cold", "overvoltage", "failure", "unknown")
        val NETWORKS = setOf("wifi", "cellular", "ethernet", "vpn", "none")
        val RINGERS = setOf("normal", "vibrate", "silent")

        /** Same bound the computer applies; sent within it so nothing that
         *  leaves here is refused over there for being a character too long. */
        private const val MAX_LABEL_CHARS = 64

        private fun label(value: String): String? {
            val trimmed = value.trim().take(MAX_LABEL_CHARS)
            if (trimmed.isEmpty()) return null
            if (trimmed.any { it.code < 0x20 || it.code == 0x7F || it.code in 0x80..0x9F }) return null
            return trimmed
        }

        private fun capacity(free: Long?, total: Long?): Pair<Long, Long>? {
            if (free == null || total == null || total <= 0 || free < 0 || free > total) return null
            return free to total
        }
    }
}

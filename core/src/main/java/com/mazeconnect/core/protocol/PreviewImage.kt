package com.mazeconnect.core.protocol

import java.util.Base64

/**
 * A picture a computer sent to be looked at before downloading: a shared
 * folder thumbnail, or the preview riding on a file offer.
 *
 * Bounded before it is ever decoded. The base64 is length-capped, decoded
 * strictly, and must be a JPEG — the computer only ever produces JPEG, so
 * anything else is not something it made. The pixel dimensions are checked
 * separately, on Android, before a bitmap is allocated (see
 * [com.mazeconnect.core.DeviceManager]).
 */
object PreviewImage {
    /** 280 KiB of JPEG, as the computer caps it, is ~374 KiB of base64. */
    const val MAX_BASE64_CHARS = 400 * 1024
    const val MAX_EDGE_PX = 2048

    fun decode(base64: String?): ByteArray? {
        if (base64.isNullOrEmpty() || base64.length > MAX_BASE64_CHARS) return null
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (_: IllegalArgumentException) {
            return null
        }
        // JPEG SOI marker, and something after it.
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        return bytes
    }
}

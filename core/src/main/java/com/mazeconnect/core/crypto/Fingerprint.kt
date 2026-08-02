package com.mazeconnect.core.crypto

import java.security.MessageDigest

/**
 * Device fingerprints: SHA-256 over the SubjectPublicKeyInfo DER.
 *
 * Computed from the public key rather than the certificate so it survives
 * certificate regeneration — the key is the identity, the certificate is
 * only a container TLS insists on. Byte-identical to the desktop client's
 * `Identity::fingerprintOf`.
 */
object Fingerprint {

    fun of(publicKey: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(publicKey)

    fun hexOf(publicKey: ByteArray): String = of(publicKey).toHex()

    /** Grouped, uppercase form for display. Never used for comparison. */
    fun display(hex: String, groups: Int = 4): String =
        hex.take(groups * 4)
            .chunked(4)
            .joinToString(" ")
            .uppercase()

    /**
     * Constant-time comparison. A timing oracle here is not especially
     * practical, but "compare secrets in constant time" is a rule worth
     * keeping unconditional rather than deciding case by case.
     */
    fun equals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}

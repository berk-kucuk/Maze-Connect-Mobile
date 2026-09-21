package com.mazeconnect.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Short Authentication String — the 6-digit code both users compare during
 * pairing.
 *
 * This MUST produce byte-identical results to the desktop client's
 * `Sas::derive`, or pairing between the two clients can never succeed. The
 * inputs are SubjectPublicKeyInfo DER, which Java's `PublicKey.getEncoded()`
 * and OpenSSL's `i2d_PUBKEY()` both emit identically — that shared encoding
 * is what keeps the two implementations in agreement without a custom
 * serialisation to maintain.
 *
 * WHY PUBLIC KEYS AND NOT THE TLS EXPORTER SECRET
 * -----------------------------------------------
 * Neither Android's javax.net.ssl nor Qt's QSslSocket exposes RFC 5705
 * keying-material export. Binding to both peers' long-term public keys
 * preserves the property that matters: a man-in-the-middle cannot forge
 * either key, so it must present its own to each side, and the two screens
 * then show different codes.
 *
 * WHY THE NONCE IS COMMITTED TO BEFORE IT IS REVEALED
 * ---------------------------------------------------
 * That argument holds only while neither side can choose its nonce after
 * seeing the other's. It did not: the exchange was one round, so a
 * man-in-the-middle running both halves could fix the far side's code first
 * and then search its own nonce until the near side matched. Six digits is
 * 10^6 — under a second — and then both humans see the same number.
 *
 * So pairing is three messages: PairRequest carries commit(nonce),
 * PairResponse carries the responder's nonce, and PairReveal opens the
 * initiator's. Neither side can move its contribution after learning the
 * other's. Must stay byte-identical to the desktop `Sas::commit`.
 */
object Sas {
    const val DIGITS = 6
    const val NONCE_SIZE = 32
    const val COMMIT_SIZE = 32

    private const val CONTEXT = "maze-connect/sas/v1"

    // Separate context for the commitment: the same nonce goes into both
    // hashes, and a shared string would let one be presented as the other.
    private const val COMMIT_CONTEXT = "maze-connect/sas-commit/v1"

    // SPKI DER for P-256 is 91 bytes; bounds-checked rather than fixed so a
    // future curve change cannot silently pass a malformed key.
    private const val MIN_KEY_SIZE = 64
    private const val MAX_KEY_SIZE = 256

    private val random = SecureRandom()

    fun generateNonce(): ByteArray = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }

    /**
     * Binding commitment to a nonce, sent before the nonce itself. Null for a
     * wrong-sized nonce; callers must treat null as a hard failure.
     */
    fun commit(nonce: ByteArray): ByteArray? {
        if (nonce.size != NONCE_SIZE) return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(COMMIT_CONTEXT.toByteArray(Charsets.US_ASCII))
        appendLengthPrefixed(digest, nonce)
        return digest.digest()
    }

    /**
     * Constant-time check that [nonce] is the one [commitment] named.
     *
     * MessageDigest.isEqual is the constant-time comparison on Android. A
     * mismatch here means an active attacker is present, and that is the last
     * moment to avoid telling them how much of their guess was right.
     */
    fun verifyCommitment(commitment: ByteArray?, nonce: ByteArray?): Boolean {
        if (commitment == null || nonce == null) return false
        val expected = commit(nonce) ?: return false
        if (commitment.size != expected.size) return false
        return MessageDigest.isEqual(commitment, expected)
    }

    fun isPlausiblePublicKey(key: ByteArray): Boolean = key.size in MIN_KEY_SIZE..MAX_KEY_SIZE

    /**
     * Derive the shared verification code, or null if any input is missing
     * or malformed. Callers must treat null as a hard failure and abort
     * pairing rather than displaying anything.
     */
    fun derive(
        initiatorPublicKey: ByteArray,
        responderPublicKey: ByteArray,
        initiatorNonce: ByteArray,
        responderNonce: ByteArray,
    ): String? {
        if (!isPlausiblePublicKey(initiatorPublicKey) ||
            !isPlausiblePublicKey(responderPublicKey) ||
            initiatorNonce.size != NONCE_SIZE ||
            responderNonce.size != NONCE_SIZE
        ) {
            return null
        }
        // A peer echoing our own key back would otherwise yield a code that
        // trivially matches on both screens.
        if (initiatorPublicKey.contentEquals(responderPublicKey)) return null

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(CONTEXT.toByteArray(Charsets.US_ASCII))
        appendLengthPrefixed(digest, initiatorPublicKey)
        appendLengthPrefixed(digest, responderPublicKey)
        appendLengthPrefixed(digest, initiatorNonce)
        appendLengthPrefixed(digest, responderNonce)

        val hash = digest.digest()
        if (hash.size < 4) return null

        val value = (
            ((hash[0].toLong() and 0xFF) shl 24) or
                ((hash[1].toLong() and 0xFF) shl 16) or
                ((hash[2].toLong() and 0xFF) shl 8) or
                (hash[3].toLong() and 0xFF)
            ) and 0x7FFFFFFFL

        return (value % 1_000_000L).toString().padStart(DIGITS, '0')
    }

    /**
     * Length-prefix every field so concatenation is unambiguous — otherwise
     * (A="ab", B="c") and (A="a", B="bc") would hash identically.
     */
    private fun appendLengthPrefixed(digest: MessageDigest, field: ByteArray) {
        val len = field.size
        digest.update(
            byteArrayOf(
                ((len ushr 24) and 0xFF).toByte(),
                ((len ushr 16) and 0xFF).toByte(),
                ((len ushr 8) and 0xFF).toByte(),
                (len and 0xFF).toByte(),
            )
        )
        digest.update(field)
    }
}

package com.mazeconnect.core.approval

import com.mazeconnect.core.Limits
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** What the owner's fingerprint is being asked for. A closed set: the phone
 *  says what each one means in its own words, never in the computer's. */
enum class ApprovalPurpose(val wire: String) {
    UNLOCK("unlock"),
    SUDO("sudo"),
    POLKIT("polkit");

    companion object {
        fun from(wire: String?): ApprovalPurpose? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One challenge, as it is signed. Everything the phone shows is in here and
 * all of it is signed; [code] is what the owner typed, which only verifies if
 * it is the code the computer showed.
 */
class ApprovalChallenge(
    val purpose: ApprovalPurpose,
    val host: String,
    val user: String,
    val detail: String,
    val nonce: ByteArray,
    val issuedAt: Long,
    val code: String = "",
) {
    fun withCode(typed: String) = ApprovalChallenge(purpose, host, user, detail, nonce, issuedAt, typed)
}

/**
 * The bytes this phone's approval key signs — byte for byte the desktop's
 * `approval::signingInput()` and pam_mazeconnect's `mc_signing_input()`:
 *
 *     "maze-connect/approval/v1"
 *       || lp(purpose) || lp(host) || lp(user) || lp(detail)
 *       || lp(nonce)   || lp(code) || lp(issuedAt as decimal ASCII)
 *       || lp(SHA-256 of the computer's identity key)
 *
 * The last field is taken from **this phone's pin** for the link the request
 * came over, never from the request, so a signature given to one computer is
 * worthless to any other. ApprovalSigningTest holds this to the shared vector.
 */
object ApprovalSigning {
    private val CONTEXT = "maze-connect/approval/v1".toByteArray(Charsets.US_ASCII)

    fun signingInput(challenge: ApprovalChallenge, verifierFingerprint: ByteArray): ByteArray {
        require(challenge.nonce.size == Limits.APPROVAL_NONCE_SIZE)
        require(verifierFingerprint.size == 32)
        val out = ByteArrayOutputStream()
        out.write(CONTEXT)
        out.lp(challenge.purpose.wire.toByteArray(Charsets.UTF_8))
        out.lp(challenge.host.toByteArray(Charsets.UTF_8))
        out.lp(challenge.user.toByteArray(Charsets.UTF_8))
        out.lp(challenge.detail.toByteArray(Charsets.UTF_8))
        out.lp(challenge.nonce)
        out.lp(challenge.code.toByteArray(Charsets.UTF_8))
        out.lp(challenge.issuedAt.toString().toByteArray(Charsets.US_ASCII))
        out.lp(verifierFingerprint)
        return out.toByteArray()
    }

    /** SHA-256 of a pinned identity key: the verifier field above. */
    fun fingerprintOf(spki: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(spki)

    /**
     * The short form of an approval key people compare across screens: the
     * first eight bytes of its SHA-256 as four groups of four hex digits —
     * the same code the computer's Settings and `maze-connect-approvers
     * enroll` show.
     */
    fun keyCode(spki: ByteArray): String {
        val hex = fingerprintOf(spki).copyOfRange(0, 8)
            .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        return hex.chunked(4).joinToString("-")
    }

    /**
     * A display field from a challenge is shown as it is, so it may hold no
     * control characters, no bidirectional overrides or isolates, and no line
     * or paragraph separators — nothing that could make one line read as two
     * or a host name read as something it is not.
     */
    fun isDisplaySafe(value: String): Boolean = value.none { c ->
        val code = c.code
        code < 0x20 || code == 0x7F || code in 0x80..0x9F ||
            code in 0x202A..0x202E || code in 0x2066..0x2069 || code == 0x2028 || code == 0x2029
    }

    /** Exactly [Limits.APPROVAL_CODE_DIGITS] ASCII digits. */
    fun isWellFormedCode(code: String): Boolean =
        code.length == Limits.APPROVAL_CODE_DIGITS && code.all { it in '0'..'9' }

    private fun ByteArrayOutputStream.lp(field: ByteArray) {
        val n = field.size
        write((n ushr 24) and 0xFF)
        write((n ushr 16) and 0xFF)
        write((n ushr 8) and 0xFF)
        write(n and 0xFF)
        write(field)
    }
}

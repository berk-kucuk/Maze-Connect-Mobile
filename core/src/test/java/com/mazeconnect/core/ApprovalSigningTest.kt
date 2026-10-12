package com.mazeconnect.core

import com.mazeconnect.core.approval.ApprovalChallenge
import com.mazeconnect.core.approval.ApprovalPurpose
import com.mazeconnect.core.approval.ApprovalSigning
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The approval signature, against the vector the desktop and the PAM module
 * assert too. If this drifts from them, every approval fails to verify in
 * the field — this is where that is caught instead.
 */
class ApprovalSigningTest {

    private fun vector() = ApprovalChallenge(
        purpose = ApprovalPurpose.SUDO,
        host = "maze",
        user = "berk",
        detail = "sudo · pts/1",
        nonce = ByteArray(32) { it.toByte() },
        issuedAt = 1_790_000_000L,
        code = "47",
    )

    private fun sha256Hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun matchesTheSharedKnownAnswer() {
        val input = ApprovalSigning.signingInput(vector(), ByteArray(32) { 0xAA.toByte() })
        assertEquals(
            "ecb446382e92746576b13cc8937fdee33d74a74dfb35f6d3d2f8257b3cf7b6c7",
            sha256Hex(input),
        )
    }

    @Test
    fun theTypedCodeIsWhatGetsSigned() {
        val fp = ByteArray(32) { 1 }
        val noCode = ApprovalSigning.signingInput(vector().withCode(""), fp)
        val right = ApprovalSigning.signingInput(vector().withCode("47"), fp)
        val wrong = ApprovalSigning.signingInput(vector().withCode("48"), fp)
        assertFalse(noCode.contentEquals(right))
        assertFalse(wrong.contentEquals(right))
        // Bound to the computer, too.
        assertFalse(
            right.contentEquals(ApprovalSigning.signingInput(vector(), ByteArray(32) { 2 }))
        )
    }

    @Test
    fun aJavaSignatureVerifiesOverTheSameBytes() {
        // SHA256withECDSA over the input is what the Keystore key does; this
        // is the same algorithm in software, so the encoding is pinned here.
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val input = ApprovalSigning.signingInput(vector(), ByteArray(32))
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(input)
            sign()
        }
        assertTrue(signature.size <= Limits.MAX_APPROVAL_SIGNATURE)
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(pair.public)
            update(input)
            verify(signature)
        })
        // SPKI DER, the same encoding the desktop pins.
        assertEquals(91, pair.public.encoded.size)
    }

    @Test
    fun keyCodesAreFourGroupsOfFour() {
        val code = ApprovalSigning.keyCode(ByteArray(91) { 7 })
        assertTrue(Regex("^[0-9A-F]{4}(-[0-9A-F]{4}){3}$").matches(code))
        assertNotEquals(code, ApprovalSigning.keyCode(ByteArray(91) { 8 }))
        assertArrayEquals(
            MessageDigest.getInstance("SHA-256").digest(ByteArray(3)),
            ApprovalSigning.fingerprintOf(ByteArray(3)),
        )
    }

    @Test
    fun displayFieldsCannotDisguiseThemselves() {
        assertTrue(ApprovalSigning.isDisplaySafe("sudo · pts/1"))
        assertFalse(ApprovalSigning.isDisplaySafe("maze‮evil"))
        assertFalse(ApprovalSigning.isDisplaySafe("line two"))
        assertFalse(ApprovalSigning.isDisplaySafe("tab\there"))
        assertTrue(ApprovalSigning.isWellFormedCode("47"))
        assertFalse(ApprovalSigning.isWellFormedCode("4"))
        assertFalse(ApprovalSigning.isWellFormedCode("4a"))
        assertFalse(ApprovalSigning.isWellFormedCode("٤٧")) // Arabic-Indic digits
    }

    @Test
    fun purposesAreAClosedSet() {
        assertEquals(ApprovalPurpose.UNLOCK, ApprovalPurpose.from("unlock"))
        assertEquals(null, ApprovalPurpose.from("root"))
        assertEquals(null, ApprovalPurpose.from(null))
    }
}

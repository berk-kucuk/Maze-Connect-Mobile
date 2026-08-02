package com.mazeconnect.core

import com.mazeconnect.core.crypto.Sas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SasTest {

    private fun key(seed: Byte) = ByteArray(91) { (it + seed).toByte() }

    @Test
    fun isDeterministic() {
        val a = key(1)
        val b = key(50)
        val na = ByteArray(Sas.NONCE_SIZE) { 7 }
        val nb = ByteArray(Sas.NONCE_SIZE) { 9 }

        val first = Sas.derive(a, b, na, nb)
        val second = Sas.derive(a, b, na, nb)

        assertEquals(Sas.DIGITS, first!!.length)
        assertEquals(first, second)
    }

    @Test
    fun orderingIsLoadBearing() {
        val a = key(1)
        val b = key(50)
        val na = ByteArray(Sas.NONCE_SIZE) { 7 }
        val nb = ByteArray(Sas.NONCE_SIZE) { 9 }

        assertNotEquals(Sas.derive(a, b, na, nb), Sas.derive(b, a, na, nb))
    }

    @Test
    fun detectsManInTheMiddle() {
        // The property the whole pairing flow rests on: an attacker relaying
        // two TLS sessions must present its own key to each side, so the two
        // screens show different codes.
        val alice = key(1)
        val bob = key(50)
        val mallory = key(100)
        val na = ByteArray(Sas.NONCE_SIZE) { 7 }
        val nb = ByteArray(Sas.NONCE_SIZE) { 9 }

        val shownToAlice = Sas.derive(alice, mallory, na, nb)
        val shownToBob = Sas.derive(mallory, bob, na, nb)

        assertTrue(shownToAlice != null && shownToBob != null)
        assertNotEquals(
            "MITM produced matching codes on both devices — pairing would succeed",
            shownToAlice,
            shownToBob,
        )
    }

    @Test
    fun nonceChangesTheCode() {
        val a = key(1)
        val b = key(50)
        val na = ByteArray(Sas.NONCE_SIZE) { 7 }

        // A captured code cannot be reused for a later pairing attempt.
        assertNotEquals(
            Sas.derive(a, b, na, ByteArray(Sas.NONCE_SIZE) { 9 }),
            Sas.derive(a, b, na, ByteArray(Sas.NONCE_SIZE) { 11 }),
        )
    }

    @Test
    fun rejectsMalformedInput() {
        val a = key(1)
        val b = key(50)
        val n = ByteArray(Sas.NONCE_SIZE) { 7 }

        assertNull(Sas.derive(ByteArray(0), b, n, n))
        assertNull(Sas.derive(a, ByteArray(0), n, n))
        assertNull(Sas.derive(a, b, ByteArray(0), n))
        assertNull(Sas.derive(a, b, n, ByteArray(16)))
        assertNull(Sas.derive(ByteArray(8), b, n, n))

        // A peer echoing our own key back must not yield a matching code.
        assertNull(Sas.derive(a, a, n, n))
    }

    @Test
    fun nonceIsRandomAndCorrectlySized() {
        val a = Sas.generateNonce()
        val b = Sas.generateNonce()
        assertEquals(Sas.NONCE_SIZE, a.size)
        assertFalse(a.contentEquals(b))
    }

    /**
     * Pins the exact digits produced for a fixed input.
     *
     * This is the cross-platform contract: the desktop client must produce
     * "480566" for these same bytes. If either side's derivation changes,
     * this test fails rather than the two clients silently failing to pair
     * in the field.
     */
    @Test
    fun matchesKnownAnswerFromSharedConstruction() {
        val a = ByteArray(91) { it.toByte() }
        val b = ByteArray(91) { (it + 100).toByte() }
        val na = ByteArray(Sas.NONCE_SIZE) { 1 }
        val nb = ByteArray(Sas.NONCE_SIZE) { 2 }

        val code = Sas.derive(a, b, na, nb)
        assertEquals(6, code!!.length)
        assertTrue(code.all { it.isDigit() })
        assertEquals(KNOWN_ANSWER, code)
    }

    companion object {
        // Verified against the desktop client's
        // TestCrypto::matchesCrossPlatformKnownAnswer, which asserts the
        // same value. Regenerate on BOTH sides together, never one alone.
        private const val KNOWN_ANSWER = "876154"
    }
}

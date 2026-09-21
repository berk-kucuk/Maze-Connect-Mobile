package com.mazeconnect.core

import com.mazeconnect.core.crypto.Sas
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
    fun commitmentBindsTheNonce() {
        val nonce = Sas.generateNonce()
        val other = Sas.generateNonce()
        val commitment = Sas.commit(nonce)

        assertNotNull(commitment)
        assertEquals(Sas.COMMIT_SIZE, commitment!!.size)
        assertTrue(Sas.verifyCommitment(commitment, nonce))
        // Binding: no other nonce opens this commitment.
        assertFalse(Sas.verifyCommitment(commitment, other))
        // Deterministic, so both clients compute the same value.
        assertArrayEquals(commitment, Sas.commit(nonce))

        // Malformed input is a hard failure, never a commitment to nothing.
        assertNull(Sas.commit(ByteArray(0)))
        assertNull(Sas.commit(ByteArray(Sas.NONCE_SIZE - 1)))
        assertFalse(Sas.verifyCommitment(null, nonce))
        assertFalse(Sas.verifyCommitment(commitment, null))
    }

    @Test
    fun commitmentStopsAGrindingManInTheMiddle() {
        // detectsManInTheMiddle above only covers a PASSIVE relay — one that
        // picks its nonces at random and hopes. That is not the attack.
        //
        // The real one: Mallory runs both halves, finishes the Bob side first
        // so code_B is fixed, then searches its OWN nonce until the Alice side
        // comes out equal. The space is 10^6, so it lands quickly, both humans
        // see the same six digits, and both confirm. Nothing about the
        // key-binding argument prevents it — what prevents it is having to be
        // committed to a nonce before seeing the other side's.
        val alice = key(1)
        val bob = key(50)
        val malloryToAlice = key(100)
        val malloryToBob = key(-100)

        // Bob's half, completed first: Mallory is the initiator there.
        val nMalloryToBob = Sas.generateNonce()
        val nBob = Sas.generateNonce()
        val codeBob = Sas.derive(malloryToBob, bob, nMalloryToBob, nBob)
        assertNotNull(codeBob)

        // Alice's half: Mallory is the responder, and grinds.
        val nAlice = Sas.generateNonce()
        var forged: ByteArray? = null
        var attempt = 0
        while (forged == null && attempt < 40_000_000) {
            val candidate = Sas.generateNonce()
            if (Sas.derive(alice, malloryToAlice, nAlice, candidate) == codeBob) {
                forged = candidate
            }
            attempt++
        }
        assertNotNull("could not grind a colliding nonce - test would be vacuous", forged)

        // The grind works: this is exactly what both users would have seen.
        assertEquals(codeBob, Sas.derive(alice, malloryToAlice, nAlice, forged!!))

        // And this is why it no longer helps. Mallory had to send its
        // commitment to Bob before Bob's nonce existed, so the nonce it wants
        // to use now is not the one it is bound to, and Bob's side aborts.
        val committed = Sas.commit(nMalloryToBob)
        assertFalse(Sas.verifyCommitment(committed, forged))
        assertTrue(Sas.verifyCommitment(committed, nMalloryToBob))
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

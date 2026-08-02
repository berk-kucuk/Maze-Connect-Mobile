package com.mazeconnect.core

import com.mazeconnect.core.protocol.ReplayWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayWindowTest {

    @Test
    fun acceptsMonotonicSequence() {
        val window = ReplayWindow()
        for (i in 1L..10_000L) {
            assertTrue("rejected counter $i", window.accept(i))
        }
        assertEquals(10_000L, window.highest)
    }

    @Test
    fun rejectsZeroAndNegative() {
        val window = ReplayWindow()
        // 0 must never be valid, so a zeroed or truncated counter field is
        // not mistaken for a legitimate first message.
        assertFalse(window.accept(0))
        assertFalse(window.accept(-1))
        assertEquals(0L, window.highest)
    }

    @Test
    fun rejectsExactReplay() {
        val window = ReplayWindow()
        assertTrue(window.accept(1))
        assertFalse(window.accept(1))

        assertTrue(window.accept(2))
        assertFalse(window.accept(2))
        assertFalse(window.accept(1))
    }

    @Test
    fun acceptsOutOfOrderWithinWindow() {
        val window = ReplayWindow()
        assertTrue(window.accept(100))
        // Network reordering inside the window is legitimate.
        assertTrue(window.accept(98))
        assertTrue(window.accept(99))
        assertTrue(window.accept(50))
        assertEquals(100L, window.highest)

        // But re-sending a late message is still a replay.
        assertFalse(window.accept(98))
    }

    @Test
    fun rejectsTooOld() {
        val window = ReplayWindow()
        assertTrue(window.accept(ReplayWindow.WINDOW_SIZE + 100L))
        assertFalse(window.accept(1))
        assertFalse(window.accept(50))
    }

    @Test
    fun handlesLargeJump() {
        val window = ReplayWindow()
        assertTrue(window.accept(1))
        assertTrue(window.accept(1_000_000))
        assertEquals(1_000_000L, window.highest)
        assertFalse(window.accept(1))
        assertFalse(window.accept(1_000_000))
        assertTrue(window.accept(999_999))
    }

    @Test
    fun resetClearsState() {
        val window = ReplayWindow()
        assertTrue(window.accept(5))
        window.reset()
        assertEquals(0L, window.highest)
        assertTrue(window.accept(5)) // fresh session, same counter is fine
    }
}

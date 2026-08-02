package com.mazeconnect.core.protocol

import com.mazeconnect.core.Limits
import java.util.BitSet

/**
 * Sliding-window replay detector over per-session monotonic counters.
 *
 * TLS 1.3 already prevents replay within a session; this sits above it so
 * the ordering guarantee is explicit and testable rather than implicit in
 * the TLS layer. Counters start at 1 — 0 is never valid, so a zeroed or
 * truncated field is rejected rather than accepted as "the first message".
 *
 * Mirrors the desktop client's `ReplayWindow`.
 */
class ReplayWindow {
    private var highestSeen = 0L
    private val seen = BitSet(WINDOW_SIZE)

    val highest: Long get() = highestSeen

    /**
     * Record [counter] as seen. Returns false — changing nothing — if the
     * counter is zero, already seen, or so far behind that it can no longer
     * be distinguished from a replay. Callers must treat false as a protocol
     * violation and drop the connection.
     */
    fun accept(counter: Long): Boolean {
        if (counter <= 0) return false

        if (counter > highestSeen) {
            val shift = counter - highestSeen
            if (shift >= WINDOW_SIZE) {
                // Jumped clear past the window; nothing older is
                // representable any more.
                seen.clear()
            } else {
                shiftLeft(shift.toInt())
            }
            seen.set(0)
            highestSeen = counter
            return true
        }

        val age = highestSeen - counter
        if (age >= WINDOW_SIZE) return false // too old to prove it isn't a replay

        val bit = age.toInt()
        if (seen.get(bit)) return false // already seen
        seen.set(bit)
        return true
    }

    fun reset() {
        highestSeen = 0
        seen.clear()
    }

    private fun shiftLeft(by: Int) {
        for (i in WINDOW_SIZE - 1 downTo by) {
            seen.set(i, seen.get(i - by))
        }
        seen.clear(0, by)
    }

    companion object {
        const val WINDOW_SIZE = Limits.REPLAY_WINDOW_SIZE
    }
}

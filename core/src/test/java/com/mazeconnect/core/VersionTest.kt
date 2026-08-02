package com.mazeconnect.core

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionTest {
    @Test
    fun protocolVersionMatchesDesktop() {
        // Pinned so a bump on one client alone fails here rather than in the
        // field: a version mismatch refuses the link outright.
        assertEquals(3, PROTOCOL_VERSION)
    }
}

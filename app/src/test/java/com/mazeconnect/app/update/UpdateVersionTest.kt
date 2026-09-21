package com.mazeconnect.app.update

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one piece of the update check with a wrong answer that looks right.
 *
 * Comparing version names as text is the obvious implementation and it fails
 * precisely when a project has shipped enough releases to need it: "0.10.10"
 * sorts below "0.10.2" as a string, so a phone on 0.10.9 would be told it was
 * up to date forever.
 */
class UpdateVersionTest {

    private fun newer(a: String, b: String) = UpdateChecker.compareVersions(a, b) > 0

    @Test
    fun ordersDoubleDigitComponentsNumerically() {
        assertTrue(newer("0.10.10", "0.10.2"))
        assertTrue(newer("0.11.0", "0.9.9"))
        assertTrue(newer("1.0.0", "0.99.99"))
    }

    @Test
    fun equalVersionsAreNotNewer() {
        assertTrue(UpdateChecker.compareVersions("0.10.2", "0.10.2") == 0)
        assertTrue(!newer("0.10.2", "0.10.3"))
    }

    @Test
    fun missingComponentsCountAsZero() {
        assertTrue(UpdateChecker.compareVersions("1.0", "1.0.0") == 0)
        assertTrue(newer("1.0.1", "1.0"))
    }

    @Test
    fun ignoresNonNumericSuffixes() {
        assertTrue(newer("0.11.0-beta1", "0.10.7"))
        assertTrue(UpdateChecker.compareVersions("0.11.0-beta1", "0.11.0") == 0)
    }
}

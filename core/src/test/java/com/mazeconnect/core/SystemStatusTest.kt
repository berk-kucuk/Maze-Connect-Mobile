package com.mazeconnect.core

import com.mazeconnect.core.protocol.SystemStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dashboard snapshot is the one message that goes almost straight onto
 * the screen, and it arrives from a machine that is authenticated rather than
 * trusted. These cover what happens when it is not the well-formed JSON the
 * desktop helper produces.
 *
 * The standard throughout: a bad field is dropped, never defaulted into
 * something that looks like a reading. A dashboard that invents "0%" or
 * "inactive" is worse than one that shows nothing, because the user cannot
 * tell the difference between a quiet machine and a broken one.
 */
class SystemStatusTest {

    /** A snapshot shaped like the one `maze-connect-status` prints. */
    private fun realistic(): JSONObject = JSONObject(
        """
        {
          "generated": 1785535888,
          "hostname": "msi",
          "security": [
            {"label":"AppArmor","unit":"apparmor.service","state":"active"},
            {"label":"firewalld","unit":"firewalld.service","state":"inactive"},
            {"label":"auditd","unit":"auditd.service","state":"unknown"}
          ],
          "metrics": [
            {"key":"cpu","label":"CPU","percent":6.2,"detail":"45 °C"},
            {"key":"mem","label":"Memory","percent":56.1,"detail":"17.5 / 31.3 GiB"}
          ],
          "network": [
            {"label":"Local IP","value":"192.168.0.10","good":"unknown"},
            {"label":"Tor","value":"Reachable","good":"active"}
          ],
          "facts": [
            {"icon":"cpu","value":"AMD Ryzen 7 5700X"},
            {"icon":"memory","value":"31.3 GiB RAM"}
          ],
          "hardening": {"score":78,"checks":[{"label":"AppArmor active","passed":true}]},
          "services": {"tor":"active","ollama":"inactive"},
          "unavailable": []
        }
        """.trimIndent()
    )

    @Test
    fun parsesTheSnapshotTheHelperActuallyPrints() {
        val status = SystemStatus.parse(realistic())
        assertNotNull(status)
        requireNotNull(status)

        assertEquals("msi", status.hostname)
        assertEquals(1785535888L, status.generated)
        assertEquals(2, status.metrics.size)
        assertEquals("CPU", status.metrics[0].label)
        assertEquals(6.2f, status.metrics[0].percent, 0.01f)
        assertEquals("45 °C", status.metrics[0].detail)
        assertEquals(78, status.hardeningScore)
        assertEquals(1, status.hardeningChecks.size)
        assertEquals(SystemStatus.State.ACTIVE, status.torState)
        assertEquals(SystemStatus.State.INACTIVE, status.ollamaState)
        assertEquals(listOf("AMD Ryzen 7 5700X", "31.3 GiB RAM"), status.facts)
    }

    @Test
    fun unknownIsItsOwnStateNotAQuietInactive() {
        // A service that was never installed has not failed. Collapsing the
        // two would report a machine as unprotected on the strength of a
        // question it never answered.
        val status = requireNotNull(SystemStatus.parse(realistic()))
        assertEquals(SystemStatus.State.ACTIVE, status.security[0].state)
        assertEquals(SystemStatus.State.INACTIVE, status.security[1].state)
        assertEquals(SystemStatus.State.UNKNOWN, status.security[2].state)

        // And anything the two clients do not agree on falls to UNKNOWN
        // rather than to a guess.
        val odd = JSONObject("""{"hostname":"x","services":{"tor":"maybe"}}""")
        assertEquals(SystemStatus.State.UNKNOWN, requireNotNull(SystemStatus.parse(odd)).torState)
    }

    @Test
    fun overlongStringsAreDroppedNotTruncated() {
        // A label long enough to push everything else off the screen is not
        // shortened into something that looks deliberate — the row goes.
        val json = JSONObject(
            """
            {"hostname":"box","security":[
              {"label":"${"A".repeat(500)}","state":"active"},
              {"label":"ok","state":"active"}
            ]}
            """.trimIndent()
        )
        val status = requireNotNull(SystemStatus.parse(json))
        assertEquals(1, status.security.size)
        assertEquals("ok", status.security[0].label)
    }

    @Test
    fun controlCharactersAreRefused() {
        // A label carrying newlines or escape sequences could forge extra
        // rows, or move the cursor around in any text surface it reaches.
        // Written as escapes on purpose: a literal ESC byte in a source file
        // is invisible, and a test whose point nobody can see is one that
        // gets "tidied up" later.
        val escapeSequence = "IP\u001B[2J"
        val newline = "Up\nDown"

        val json = JSONObject()
            .put("hostname", "box")
            .put(
                "network",
                org.json.JSONArray()
                    .put(JSONObject().put("label", escapeSequence).put("value", "1.2.3.4"))
                    .put(JSONObject().put("label", newline).put("value", "5.6.7.8"))
                    .put(JSONObject().put("label", "Real").put("value", "9.9.9.9"))
            )
        val status = requireNotNull(SystemStatus.parse(json))
        assertEquals(1, status.network.size)
        assertEquals("Real", status.network[0].label)
    }

    @Test
    fun rowCountIsCapped() {
        val rows = org.json.JSONArray()
        repeat(5000) { rows.put(JSONObject().put("label", "svc$it").put("state", "active")) }
        val json = JSONObject().put("hostname", "box").put("security", rows)

        val status = requireNotNull(SystemStatus.parse(json))
        assertTrue("a report cannot make the list unbounded", status.security.size <= 32)
    }

    @Test
    fun percentagesAreClamped() {
        val json = JSONObject(
            """
            {"hostname":"box","metrics":[
              {"key":"a","label":"A","percent":10000},
              {"key":"b","label":"B","percent":-50},
              {"key":"c","label":"C","percent":"not a number"}
            ]}
            """.trimIndent()
        )
        val status = requireNotNull(SystemStatus.parse(json))
        assertEquals(100f, status.metrics[0].percent, 0.01f)
        assertEquals(0f, status.metrics[1].percent, 0.01f)
        assertEquals(0f, status.metrics[2].percent, 0.01f)

        // Score too: a hostile 9999% would render as a bar off the panel.
        val scored = JSONObject("""{"hostname":"box","hardening":{"score":9999}}""")
        assertEquals(100, requireNotNull(SystemStatus.parse(scored)).hardeningScore)
    }

    @Test
    fun wrongTypesLoseTheFieldNotTheSnapshot() {
        // One broken probe on the computer must not cost the other seven
        // panels — the dashboard degrades section by section.
        val json = JSONObject(
            """
            {"hostname":"box",
             "metrics": "not an array",
             "security": [{"label":"AppArmor","state":"active"}],
             "hardening": 42,
             "services": "nope",
             "unavailable": [1, 2, "network"]}
            """.trimIndent()
        )
        val status = requireNotNull(SystemStatus.parse(json))
        assertEquals(0, status.metrics.size)
        assertEquals(1, status.security.size)
        assertNull(status.hardeningScore)
        assertEquals(SystemStatus.State.UNKNOWN, status.torState)
        assertEquals(listOf("network"), status.unavailable)
    }

    @Test
    fun anEmptyOrAbsentSnapshotIsNoSnapshot() {
        // Null is the honest answer: a machine with nothing running and a
        // report that carried nothing look identical, and the dashboard has
        // to be able to tell them apart.
        assertNull(SystemStatus.parse(null))
        assertNull(SystemStatus.parse(JSONObject()))
        assertNull(SystemStatus.parse(JSONObject("""{"unrelated":true}""")))

        // A hostname alone is still a reading — the machine answered.
        assertNotNull(SystemStatus.parse(JSONObject("""{"hostname":"box"}""")))
    }
}

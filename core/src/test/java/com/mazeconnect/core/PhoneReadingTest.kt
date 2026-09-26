package com.mazeconnect.core

import com.mazeconnect.core.device.PhoneReading
import com.mazeconnect.core.device.PhoneStatusCollector
import com.mazeconnect.core.protocol.Message
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What this phone sends about itself, and what it refuses to send.
 *
 * The computer validates every field again (phonestatus::sanitize); these
 * pin the sending side to the same bounds, so a reading is never refused
 * over there for something that could have been caught here.
 */
class PhoneReadingTest {

    @Test
    fun aFullReadingHasTheWireShape() {
        val json = PhoneReading(
            batteryLevel = 82, charging = true, plug = "ac", batteryTenthsC = 314,
            batteryHealth = "good",
            storageFreeBytes = 10, storageTotalBytes = 100,
            memoryAvailableBytes = 3, memoryTotalBytes = 8,
            network = "wifi", signal = 3, metered = false,
            ringer = "vibrate", doNotDisturb = true, powerSave = false, screenOn = true,
            model = "Pixel 8", manufacturer = "Google", androidRelease = "16", uptimeMs = 1000,
        ).toJson()

        val battery = json.getJSONObject("battery")
        assertEquals(82, battery.getInt("level"))
        assertTrue(battery.getBoolean("charging"))
        assertEquals("ac", battery.getString("plug"))
        assertEquals(314, battery.getInt("temperature"))
        assertEquals(10L, json.getJSONObject("storage").getLong("free"))
        assertEquals(8L, json.getJSONObject("memory").getLong("total"))
        assertEquals("wifi", json.getJSONObject("network").getString("type"))
        assertEquals(3, json.getJSONObject("network").getInt("signal"))
        assertEquals("vibrate", json.getString("ringer"))
        assertTrue(json.getBoolean("dnd"))
        assertEquals("Pixel 8", json.getString("model"))
        assertEquals(1000L, json.getLong("uptimeMs"))
    }

    @Test
    fun anUnreadableFieldIsLeftOutNotZeroed() {
        val json = PhoneReading(
            batteryLevel = 140,          // out of range
            plug = "fusion",             // not a plug
            storageFreeBytes = 20, storageTotalBytes = 10, // more free than total
            signal = 7,
            ringer = "loud",
            model = "Pixel\u0007",       // control character
        ).toJson()
        assertFalse(json.has("battery"))
        assertFalse(json.has("storage"))
        assertFalse(json.has("network"))
        assertFalse(json.has("ringer"))
        assertFalse(json.has("model"))
        assertEquals(0, json.length())
    }

    @Test
    fun anEmptyReadingIsAnEmptyObject() {
        assertEquals(0, PhoneReading().toJson().length())
    }

    @Test
    fun signalBars() {
        assertEquals(4, PhoneStatusCollector.bars(-40))
        assertEquals(3, PhoneStatusCollector.bars(-60))
        assertEquals(2, PhoneStatusCollector.bars(-70))
        assertEquals(1, PhoneStatusCollector.bars(-85))
        assertEquals(0, PhoneStatusCollector.bars(-100))
    }

    @Test
    fun freeTextKeepsLinesAndRefusesTricks() {
        fun parse(text: String): String? {
            val json = JSONObject().put("v", PROTOCOL_VERSION).put("t", "openOnPhone")
                .put("c", 1).put("text", text)
            return Message.parse(json.toString().toByteArray())?.text("text", 4096)
        }
        assertEquals("line one\nline two\tend", parse("line one\nline two\tend"))
        // A right-to-left override makes a link read as somewhere else.
        assertNull(parse("https://example.com/‮gpj.exe"))
        // An escape can reach a terminal someone pastes into.
        assertNull(parse("ls\u001B[2K"))
        assertTrue(Message.isAllowedText("plain\r\n"))
        assertFalse(Message.isAllowedText("bell\u0007"))
    }
}

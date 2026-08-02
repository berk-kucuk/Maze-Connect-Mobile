package com.mazeconnect.core

import com.mazeconnect.core.protocol.FrameParser
import com.mazeconnect.core.protocol.FrameType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramingTest {

    @Test
    fun roundTrips() {
        val payload = "{\"t\":\"ping\"}".toByteArray()
        val frame = FrameParser.encode(FrameType.CONTROL, payload)!!

        val parser = FrameParser()
        parser.append(frame)

        val result = parser.next()
        assertTrue(result is FrameParser.Result.Ready)
        val ready = result as FrameParser.Result.Ready
        assertEquals(FrameType.CONTROL, ready.frame.type)
        assertTrue(payload.contentEquals(ready.frame.payload))

        assertTrue(parser.next() is FrameParser.Result.Incomplete)
    }

    @Test
    fun handlesSplitReads() {
        val payload = ByteArray(1000) { 'x'.code.toByte() }
        val frame = FrameParser.encode(FrameType.DATA, payload)!!
        val parser = FrameParser()

        // Only the final byte may complete the frame.
        for (i in 0 until frame.size - 1) {
            parser.append(byteArrayOf(frame[i]))
            assertTrue(parser.next() is FrameParser.Result.Incomplete)
        }
        parser.append(byteArrayOf(frame[frame.size - 1]))

        val result = parser.next()
        assertTrue(result is FrameParser.Result.Ready)
        assertTrue(payload.contentEquals((result as FrameParser.Result.Ready).frame.payload))
    }

    @Test
    fun rejectsOversizedLengthPrefixWithoutBuffering() {
        // A peer claiming ~4 GiB must cost us the 5-byte header and nothing else.
        val hostile = byteArrayOf(
            FrameType.CONTROL.value,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        )
        val parser = FrameParser()
        parser.append(hostile)

        val result = parser.next()
        assertTrue(result is FrameParser.Result.Error)
        assertTrue((result as FrameParser.Result.Error).reason.contains("exceeds cap"))
        assertEquals(0, parser.bufferedBytes)
    }

    @Test
    fun rejectsUnknownFrameType() {
        val parser = FrameParser()
        parser.append(byteArrayOf(0x7F, 0x00, 0x00, 0x00, 0x01, 'z'.code.toByte()))
        assertTrue(parser.next() is FrameParser.Result.Error)
    }

    @Test
    fun staysPoisonedAfterError() {
        val parser = FrameParser()
        parser.append(ByteArray(5) { 0x7F })
        assertTrue(parser.next() is FrameParser.Result.Error)

        // Even a valid frame afterwards must be refused: once framing is
        // violated there is no safe resync point.
        parser.append(FrameParser.encode(FrameType.CONTROL, "ok".toByteArray())!!)
        assertTrue(parser.next() is FrameParser.Result.Error)
    }

    @Test
    fun encodeRefusesOversizedPayload() {
        assertNull(FrameParser.encode(FrameType.DATA, ByteArray(Limits.MAX_DATA_FRAME + 1)))
        // The exact cap is still allowed.
        assertTrue(FrameParser.encode(FrameType.DATA, ByteArray(Limits.MAX_DATA_FRAME)) != null)
    }

    @Test
    fun frameLayoutMatchesDesktopWireFormat() {
        // 1 byte type + 4 bytes big-endian length + payload. This layout is
        // the contract with the desktop client; if it drifts, nothing
        // interoperates.
        val frame = FrameParser.encode(FrameType.DATA, byteArrayOf(1, 2, 3))!!
        assertEquals(8, frame.size)
        assertEquals(FrameType.DATA.value, frame[0])
        // Byte literals, not Int: assertEquals boxes its arguments, so
        // comparing an Int 0 against a Byte 0 never matches.
        assertEquals(0.toByte(), frame[1])
        assertEquals(0.toByte(), frame[2])
        assertEquals(0.toByte(), frame[3])
        assertEquals(3.toByte(), frame[4])
    }
}

package com.mazeconnect.core.protocol

import com.mazeconnect.core.Limits
import java.io.ByteArrayOutputStream

/** Frame kinds carried over an established connection. */
enum class FrameType(val value: Byte) {
    CONTROL(0x01),
    DATA(0x02);

    companion object {
        fun from(raw: Byte): FrameType? = entries.firstOrNull { it.value == raw }
    }
}

data class Frame(val type: FrameType, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is Frame && type == other.type && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()
}

/**
 * Incremental parser for the wire format:
 *
 *     [1 byte type][4 bytes big-endian length][length bytes payload]
 *
 * The length prefix is checked against the per-type cap *before* any payload
 * byte is buffered, so a peer claiming a 4 GiB frame costs five bytes and a
 * disconnect rather than 4 GiB of memory.
 *
 * Mirrors the desktop client's `FrameParser`, including the poisoning
 * behaviour: once a peer violates the framing there is no safe point to
 * resynchronise from, so every later call fails too.
 */
class FrameParser {
    sealed interface Result {
        data object Incomplete : Result
        data class Ready(val frame: Frame) : Result
        data class Error(val reason: String) : Result
    }

    private var buffer = ByteArray(0)
    private var poisoned = false

    val bufferedBytes: Int get() = buffer.size

    fun append(bytes: ByteArray, length: Int = bytes.size) {
        if (poisoned) return
        buffer += bytes.copyOf(length)
    }

    fun next(): Result {
        if (poisoned) return Result.Error("parser poisoned by an earlier violation")
        if (buffer.size < HEADER_SIZE) return Result.Incomplete

        val type = FrameType.from(buffer[0])
            ?: return fail("unknown frame type 0x%02x".format(buffer[0]))

        val length = ((buffer[1].toLong() and 0xFF) shl 24) or
            ((buffer[2].toLong() and 0xFF) shl 16) or
            ((buffer[3].toLong() and 0xFF) shl 8) or
            (buffer[4].toLong() and 0xFF)

        val cap = maxPayloadFor(type)
        if (length > cap) return fail("frame length $length exceeds cap $cap")

        if (buffer.size < HEADER_SIZE + length) return Result.Incomplete

        val end = HEADER_SIZE + length.toInt()
        val payload = buffer.copyOfRange(HEADER_SIZE, end)
        buffer = buffer.copyOfRange(end, buffer.size)
        return Result.Ready(Frame(type, payload))
    }

    private fun fail(reason: String): Result.Error {
        poisoned = true
        buffer = ByteArray(0)
        return Result.Error(reason)
    }

    companion object {
        private const val HEADER_SIZE = 5

        fun maxPayloadFor(type: FrameType): Int = when (type) {
            FrameType.CONTROL -> Limits.MAX_CONTROL_FRAME
            FrameType.DATA -> Limits.MAX_DATA_FRAME
        }

        /** Returns null if the payload exceeds the cap for its type. */
        fun encode(type: FrameType, payload: ByteArray): ByteArray? {
            if (payload.size > maxPayloadFor(type)) return null
            val out = ByteArrayOutputStream(HEADER_SIZE + payload.size)
            out.write(type.value.toInt())
            out.write((payload.size ushr 24) and 0xFF)
            out.write((payload.size ushr 16) and 0xFF)
            out.write((payload.size ushr 8) and 0xFF)
            out.write(payload.size and 0xFF)
            out.write(payload)
            return out.toByteArray()
        }
    }
}

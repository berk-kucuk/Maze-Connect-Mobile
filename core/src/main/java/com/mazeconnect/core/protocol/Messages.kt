package com.mazeconnect.core.protocol

import com.mazeconnect.core.PROTOCOL_VERSION
import org.json.JSONArray
import org.json.JSONObject
// java.util.Base64, not android.util.Base64: it is available from API 26
// (minSdk here is 28), produces the same standard padded, unwrapped output
// Qt's toBase64() does, and keeps this protocol layer free of Android
// imports so it stays testable on a plain JVM.
import java.util.Base64

/** Control-plane message types. Anything not listed here is rejected. */
enum class MessageType(val wire: String) {
    HELLO("hello"),
    PAIR_REQUEST("pairRequest"),
    PAIR_RESPONSE("pairResponse"),
    PAIR_REVEAL("pairReveal"),
    PAIR_RESULT("pairResult"),
    UNPAIR("unpair"),
    FILE_OFFER("fileOffer"),
    FILE_ACCEPT("fileAccept"),
    FILE_REJECT("fileReject"),
    FILE_COMPLETE("fileComplete"),
    FILE_CANCEL("fileCancel"),
    STATUS_REQUEST("statusRequest"),
    STATUS_REPORT("statusReport"),
    STATUS_UNCHANGED("statusUnchanged"),
    COMMAND_LIST("commandList"),
    COMMAND_CATALOG("commandCatalog"),
    COMMAND_RUN("commandRun"),
    COMMAND_RESULT("commandResult"),
    AI_MODELS("aiModels"),
    AI_MODEL_LIST("aiModelList"),
    AI_PROMPT("aiPrompt"),
    AI_CHUNK("aiChunk"),
    AI_DONE("aiDone"),
    GUARD_STATUS("guardStatus"),
    GUARD_REPORT("guardReport"),
    GUARD_REQUEST("guardRequest"),
    GUARD_RESULT("guardResult"),

    /** Transport-level liveness probe; Connection answers/consumes it directly. */
    PING("ping"),
    PONG("pong"),

    /** Computer -> phone only: clipboard text to open there. */
    OPEN_ON_PHONE("openOnPhone"),

    /** Phone -> computer: send the players now, and (un)subscribe to changes. */
    MEDIA_REQUEST("mediaRequest"),

    /** Computer -> phone: the players, their track and position. */
    MEDIA_STATE("mediaState"),

    /** Phone -> computer: one action from a fixed table. */
    MEDIA_COMMAND("mediaCommand");

    companion object {
        fun from(wire: String?): MessageType? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A parsed control message.
 *
 * Parsing is strict: [parse] validates the envelope (version, counter, known
 * type) and every accessor re-validates the field it returns. Nothing here
 * hands back an unchecked peer-supplied value, so a caller cannot
 * accidentally use a field that was never validated.
 *
 * Mirrors the desktop client's `Message`.
 */
class Message private constructor(
    val type: MessageType,
    val counter: Long,
    private val body: JSONObject,
) {

    fun toJson(): ByteArray {
        val obj = JSONObject(body.toString())
        obj.put(KEY_VERSION, PROTOCOL_VERSION)
        obj.put(KEY_TYPE, type.wire)
        obj.put(KEY_COUNTER, counter)
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    // ---- Validated accessors -------------------------------------------

    /** Bounded, control-character-free display string, or null. */
    fun string(key: String, maxChars: Int): String? {
        val value = body.opt(key) as? String ?: return null
        if (value.length > maxChars) return null
        if (value.any { it.code < 0x20 || it.code == 0x7F || it.code in 0x80..0x9F }) return null
        return value
    }

    /** Non-negative integer within [0, max], or null. */
    fun integer(key: String, max: Long): Long? {
        if (!body.has(key)) return null
        val value = when (val raw = body.opt(key)) {
            null -> return null
            is Int -> raw.toLong()
            is Long -> raw
            is Number -> {
                val d = raw.toDouble()
                if (d != Math.floor(d) || d.isInfinite()) return null
                d.toLong()
            }
            else -> return null
        }
        return if (value in 0..max) value else null
    }

    fun boolean(key: String, fallback: Boolean = false): Boolean =
        if (body.opt(key) is Boolean) body.getBoolean(key) else fallback

    /** Base64 field decoded to exactly [expectedSize] bytes, or null. */
    fun binary(key: String, expectedSize: Int): ByteArray? {
        val encoded = body.opt(key) as? String ?: return null
        val decoded = try {
            // Strict decoder: malformed base64 is rejected rather than
            // silently truncated to whatever prefix happened to parse.
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return if (decoded.size == expectedSize) decoded else null
    }

    /** String array, each element bounded; capped in length. */
    fun stringList(key: String, maxItems: Int, maxChars: Int): List<String> {
        val array = body.opt(key) as? JSONArray ?: return emptyList()
        if (array.length() > maxItems) return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val item = array.opt(i) as? String ?: return emptyList()
            if (item.length > maxChars) return emptyList()
            if (item.any { it.code < 0x20 || it.code == 0x7F || it.code in 0x80..0x9F }) {
                return emptyList()
            }
            out.add(item)
        }
        return out
    }

    /**
     * A nested object, returned **unchecked**, for payloads this class has no
     * business knowing the shape of.
     *
     * Named so it cannot be reached for by accident. Every other accessor
     * above re-validates what it returns; this one confirms only that the
     * field is present and is an object. Its contents are whatever the peer
     * sent, bounded solely by the control-frame cap — see [SystemStatus] for
     * how the dashboard snapshot is actually validated before it is shown.
     */
    fun unvalidatedObject(key: String): JSONObject? = body.opt(key) as? JSONObject

    /**
     * A nested array, returned **unchecked**, with the same caveat as
     * [unvalidatedObject]: presence and type only. Callers bound each element
     * themselves.
     */
    fun unvalidatedArray(key: String): JSONArray? = body.opt(key) as? JSONArray

    /** Signed integer within [min, max], or null. Distinct from [integer],
     *  which refuses negatives — an exit code may legitimately be -1. */
    fun signedInteger(key: String, min: Long, max: Long): Long? {
        val value = when (val raw = body.opt(key)) {
            // Spelled out rather than left to `else`, matching [integer]:
            // over a nullable subject the compiler wants the null branch
            // named before it will treat the rest as exhaustive.
            null -> return null
            is Int -> raw.toLong()
            is Long -> raw
            is Number -> {
                val d = raw.toDouble()
                if (d != Math.floor(d) || d.isInfinite()) return null
                d.toLong()
            }
            else -> return null
        }
        return if (value in min..max) value else null
    }

    companion object {
        private const val KEY_VERSION = "v"
        private const val KEY_TYPE = "t"
        private const val KEY_COUNTER = "c"

        /**
         * Parse a Control frame payload. Returns null if the JSON is
         * malformed, the envelope is invalid, the protocol version does not
         * match, or the type is unrecognised — all of which callers must
         * treat as protocol violations.
         */
        fun parse(json: ByteArray): Message? {
            val obj = try {
                JSONObject(String(json, Charsets.UTF_8))
            } catch (_: Exception) {
                return null
            }

            // No negotiation to an older dialect: a version mismatch is a
            // hard stop, not something to work around.
            if (obj.optInt(KEY_VERSION, -1) != PROTOCOL_VERSION) return null

            // 0 is reserved so a missing or zeroed counter is never a valid
            // first message (see ReplayWindow).
            val counter = obj.optLong(KEY_COUNTER, -1L)
            if (counter < 1L) return null

            // optString's default parameter is non-null in the Java API;
            // an absent key yields "" here, which MessageType.from rejects.
            val type = MessageType.from(obj.optString(KEY_TYPE)) ?: return null

            return Message(type, counter, obj)
        }

        private fun build(
            type: MessageType,
            counter: Long,
            build: JSONObject.() -> Unit = {},
        ): Message = Message(type, counter, JSONObject().apply(build))

        fun hello(
            counter: Long,
            deviceId: String,
            deviceName: String,
            deviceType: String,
            capabilities: List<String>,
            appVersion: String,
        ) = build(MessageType.HELLO, counter) {
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("deviceType", deviceType)
            put("appVersion", appVersion)
            put("capabilities", JSONArray(capabilities))
        }

        /** Carries commit(nonce), never the nonce itself — see Sas. */
        fun pairRequest(counter: Long, commitment: ByteArray) =
            build(MessageType.PAIR_REQUEST, counter) {
                put("commitment", Base64.getEncoder().encodeToString(commitment))
            }

        fun pairResponse(counter: Long, nonce: ByteArray) =
            build(MessageType.PAIR_RESPONSE, counter) {
                put("nonce", Base64.getEncoder().encodeToString(nonce))
            }

        /** Opens the initiator's commitment, after the responder is committed. */
        fun pairReveal(counter: Long, nonce: ByteArray) =
            build(MessageType.PAIR_REVEAL, counter) {
                put("nonce", Base64.getEncoder().encodeToString(nonce))
            }

        fun pairResult(counter: Long, accepted: Boolean) =
            build(MessageType.PAIR_RESULT, counter) { put("accepted", accepted) }

        fun unpair(counter: Long) = build(MessageType.UNPAIR, counter)

        fun fileOffer(counter: Long, transferId: Long, filename: String, sizeBytes: Long) =
            build(MessageType.FILE_OFFER, counter) {
                put("transferId", transferId)
                put("filename", filename)
                put("size", sizeBytes)
            }

        fun fileAccept(counter: Long, transferId: Long) =
            build(MessageType.FILE_ACCEPT, counter) { put("transferId", transferId) }

        fun fileReject(counter: Long, transferId: Long, reason: String) =
            build(MessageType.FILE_REJECT, counter) {
                put("transferId", transferId)
                put("reason", reason)
            }

        fun fileComplete(counter: Long, transferId: Long) =
            build(MessageType.FILE_COMPLETE, counter) { put("transferId", transferId) }

        fun fileCancel(counter: Long, transferId: Long, reason: String) =
            build(MessageType.FILE_CANCEL, counter) {
                put("transferId", transferId)
                put("reason", reason)
            }

        /**
         * Ask the computer for a dashboard snapshot.
         *
         * Carries nothing but the envelope. There is no probe to name and no
         * argument to pass — a request decides *whether* the desktop's status
         * helper runs, never *how*.
         */
        fun statusRequest(counter: Long) = build(MessageType.STATUS_REQUEST, counter)

        /**
         * A snapshot, nested whole under "status" so no field of it can
         * collide with an envelope key. Sent by the desktop; this exists on
         * mobile for the interop vectors to build the same bytes.
         */
        fun statusReport(counter: Long, status: JSONObject) =
            build(MessageType.STATUS_REPORT, counter) { put("status", status) }

        /** The same type carrying why there is no snapshot. */
        fun statusUnavailable(counter: Long, reason: String) =
            build(MessageType.STATUS_REPORT, counter) { put("error", reason) }

        /**
         * Nothing has changed since this device's last report.
         *
         * Sent by the computer; here so the interop vectors can build it.
         */
        fun statusUnchanged(counter: Long) = build(MessageType.STATUS_UNCHANGED, counter)

        /** Ask which commands the computer's owner has defined. */
        fun commandList(counter: Long) = build(MessageType.COMMAND_LIST, counter)

        /**
         * Ask to run one entry, **by id**.
         *
         * [id] is matched exactly against the computer's own file. It is not
         * a command and is never treated as one: there is no argv here to
         * send, because the phone is not trusted to choose it.
         */
        fun commandRun(counter: Long, requestId: Long, id: String) =
            build(MessageType.COMMAND_RUN, counter) {
                put("requestId", requestId)
                put("id", id)
            }

        /**
         * Sent by the computer; here so the interop vectors can build it.
         *
         * [error] distinguishes "no commands defined" from "not telling you"
         * — an empty list alone cannot, and showing "no commands" for a
         * capability the computer switched off would be wrong.
         */
        fun commandCatalog(counter: Long, entries: JSONArray, error: String? = null) =
            build(MessageType.COMMAND_CATALOG, counter) {
                put("commands", entries)
                if (!error.isNullOrEmpty()) put("error", error)
            }

        fun commandResult(
            counter: Long,
            requestId: Long,
            id: String,
            exitCode: Int,
            output: String,
        ) = build(MessageType.COMMAND_RESULT, counter) {
            put("requestId", requestId)
            put("id", id)
            put("exitCode", exitCode)
            put("output", output)
        }

        /** Ask which models the computer's Ollama has. */
        fun aiModels(counter: Long) = build(MessageType.AI_MODELS, counter)

        /**
         * One turn of conversation.
         *
         * Carries the user's text and nothing else — no history, no system
         * prompt. The conversation lives on the computer, so this phone can
         * neither replace Maze AI's persona nor invent turns that never
         * happened.
         */
        fun aiPrompt(counter: Long, requestId: Long, model: String, text: String) =
            build(MessageType.AI_PROMPT, counter) {
                put("requestId", requestId)
                put("model", model)
                put("text", text)
            }

        /** Sent by the computer; here so the interop vectors can build it. */
        fun aiChunk(counter: Long, requestId: Long, text: String) =
            build(MessageType.AI_CHUNK, counter) {
                put("requestId", requestId)
                put("text", text)
            }

        fun aiDone(counter: Long, requestId: Long, error: String?) =
            build(MessageType.AI_DONE, counter) {
                put("requestId", requestId)
                if (!error.isNullOrEmpty()) put("error", error)
            }

        /** Ask the state of every killswitch. */
        fun guardStatus(counter: Long) = build(MessageType.GUARD_STATUS, counter)

        /**
         * Turn one killswitch on or off.
         *
         * A device name from a fixed table and a boolean — there is no verb
         * field. PANIC and RESTORE are not refused on the far side so much as
         * unexpressible from here: this message has nowhere to put them.
         */
        fun guardRequest(counter: Long, device: String, on: Boolean) =
            build(MessageType.GUARD_REQUEST, counter) {
                put("device", device)
                put("on", on)
            }

        /** Sent by the computer; here so the interop vectors can build it. */
        fun guardResult(counter: Long, device: String, state: String, error: String?) =
            build(MessageType.GUARD_RESULT, counter) {
                put("device", device)
                put("state", state)
                if (!error.isNullOrEmpty()) put("error", error)
            }

        /** A liveness probe. Handled entirely inside [com.mazeconnect.core.transport.Connection]
         *  and never seen by DeviceManager. */
        fun ping(counter: Long) = build(MessageType.PING, counter)
        fun pong(counter: Long) = build(MessageType.PONG, counter)

        /** Sent by the computer; here so the interop vectors can build it. */
        fun openOnPhone(counter: Long, text: String) =
            build(MessageType.OPEN_ON_PHONE, counter) { put("text", text) }

        /**
         * Ask for the computer's players. Always answered once; with
         * [subscribe] the computer also pushes every change until a request
         * with false, or until the link drops.
         */
        fun mediaRequest(counter: Long, subscribe: Boolean) =
            build(MessageType.MEDIA_REQUEST, counter) { put("subscribe", subscribe) }

        /**
         * One action on one player. [action] must be one of [MediaAction];
         * [player] is an id the computer handed out in mediaState. The
         * computer looks both up — neither is ever used to build anything.
         */
        fun mediaCommand(counter: Long, player: String, action: String, value: Long) =
            build(MessageType.MEDIA_COMMAND, counter) {
                put("player", player)
                put("action", action)
                put("value", value)
            }

        /** Sent by the computer; here so the interop vectors can build it. */
        fun mediaState(counter: Long, media: JSONObject) =
            build(MessageType.MEDIA_STATE, counter) { put("media", media) }
    }
}

/**
 * Data-frame helpers. A Data frame carries
 *     [4 bytes big-endian transferId][chunk bytes]
 * so several transfers can interleave without a per-chunk JSON envelope.
 */
object DataChunk {
    fun encode(transferId: Long, chunk: ByteArray): ByteArray {
        val out = ByteArray(4 + chunk.size)
        out[0] = ((transferId ushr 24) and 0xFF).toByte()
        out[1] = ((transferId ushr 16) and 0xFF).toByte()
        out[2] = ((transferId ushr 8) and 0xFF).toByte()
        out[3] = (transferId and 0xFF).toByte()
        chunk.copyInto(out, 4)
        return out
    }

    /** Returns null if the frame is too short to carry a transfer id. */
    fun decode(payload: ByteArray): Pair<Long, ByteArray>? {
        if (payload.size < 4) return null
        val id = ((payload[0].toLong() and 0xFF) shl 24) or
            ((payload[1].toLong() and 0xFF) shl 16) or
            ((payload[2].toLong() and 0xFF) shl 8) or
            (payload[3].toLong() and 0xFF)
        return id to payload.copyOfRange(4, payload.size)
    }
}

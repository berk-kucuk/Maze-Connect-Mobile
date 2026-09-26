package com.mazeconnect.core

import com.mazeconnect.core.crypto.Sas
import com.mazeconnect.core.protocol.Capability
import com.mazeconnect.core.protocol.DataChunk
import com.mazeconnect.core.protocol.FrameParser
import com.mazeconnect.core.protocol.FrameType
import com.mazeconnect.core.protocol.Message
import com.mazeconnect.core.protocol.MessageType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden vectors shared with the desktop client.
 *
 * Every assertion here is duplicated byte-for-byte in Maze-Connect's
 * `tst_interop.cpp`. The two clients are separate implementations of one
 * wire format, so the realistic failure is not a crash — it is a silent
 * drift (a renamed envelope key, a different base64 variant, an off-by-one
 * in the frame header) that makes pairing fail in the field with no useful
 * error.
 *
 * These tests are the contract. Changing a vector here without changing it
 * in the C++ file is a protocol break, and the paired test will fail.
 */
class InteropTest {

    @Test
    fun frameHeaderLayout() {
        // [1 byte type][4 bytes big-endian length][payload]
        val frame = FrameParser.encode(FrameType.CONTROL, "ab".toByteArray())!!
        assertEquals(7, frame.size)
        assertEquals(0x01.toByte(), frame[0]) // CONTROL
        assertEquals(0x00.toByte(), frame[1])
        assertEquals(0x00.toByte(), frame[2])
        assertEquals(0x00.toByte(), frame[3])
        assertEquals(0x02.toByte(), frame[4])
        assertArrayEquals("ab".toByteArray(), frame.copyOfRange(5, frame.size))

        val data = FrameParser.encode(FrameType.DATA, ByteArray(0))!!
        assertEquals(0x02.toByte(), data[0]) // DATA

        // A length spanning more than one byte must be big-endian.
        val wide = FrameParser.encode(FrameType.DATA, ByteArray(258))!!
        assertEquals(0x01.toByte(), wide[3])
        assertEquals(0x02.toByte(), wide[4])
    }

    @Test
    fun controlEnvelopeKeys() {
        // The envelope is "v"/"t"/"c". A rename on one side alone would make
        // every message from that side unparseable on the other.
        val json = JSONObject(String(Message.unpair(42).toJson(), Charsets.UTF_8))
        assertEquals(4, json.getInt("v"))
        assertEquals("unpair", json.getString("t"))
        assertEquals(42L, json.getLong("c"))

        // And the type names on the wire.
        assertEquals("hello", MessageType.HELLO.wire)
        assertEquals("pairRequest", MessageType.PAIR_REQUEST.wire)
        assertEquals("pairReveal", MessageType.PAIR_REVEAL.wire)
        assertEquals("pairResponse", MessageType.PAIR_RESPONSE.wire)
        assertEquals("pairResult", MessageType.PAIR_RESULT.wire)
        assertEquals("fileOffer", MessageType.FILE_OFFER.wire)
    }

    @Test
    fun pairRequestNonceEncoding() {
        // Standard base64, no line wrapping. Qt's toBase64() must agree with
        // Base64.NO_WRAP here or pairing cannot complete.
        val nonce = ByteArray(Sas.NONCE_SIZE) { it.toByte() }

        // PairRequest carries the COMMITMENT, never the nonce. If either
        // client ever puts the nonce back in this message the commitment round
        // is gone and the on-screen code stops meaning anything (see Sas), so
        // the absence of the key is asserted as strictly as its presence.
        val commitment = Sas.commit(nonce)
        assertNotNull(commitment)
        val request = JSONObject(
            String(Message.pairRequest(1, commitment!!).toJson(), Charsets.UTF_8),
        )
        assertFalse(request.has("nonce"))
        assertEquals(
            "Jz2nu+C6nOgcIlgQwwHoK6148NvbSJrusnW6o8PaWj4=",
            request.getString("commitment"),
        )

        // The nonce itself travels in PairReveal, base64 the same way.
        val reveal = JSONObject(
            String(Message.pairReveal(2, nonce).toJson(), Charsets.UTF_8),
        )
        assertEquals(
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
            reveal.getString("nonce"),
        )

        // And it survives a round trip through the validated accessor.
        val parsed = Message.parse(Message.pairReveal(2, nonce).toJson())
        assertNotNull(parsed)
        assertArrayEquals(nonce, parsed!!.binary("nonce", Sas.NONCE_SIZE))

        val parsedRequest = Message.parse(Message.pairRequest(1, commitment).toJson())
        assertNotNull(parsedRequest)
        assertTrue(
            Sas.verifyCommitment(
                parsedRequest!!.binary("commitment", Sas.COMMIT_SIZE),
                nonce,
            ),
        )
    }

    @Test
    fun dataFrameTransferIdLayout() {
        // [4 bytes big-endian transferId][chunk]
        val encoded = DataChunk.encode(0x01020304L, "hi".toByteArray())
        assertEquals(6, encoded.size)
        assertEquals(0x01.toByte(), encoded[0])
        assertEquals(0x02.toByte(), encoded[1])
        assertEquals(0x03.toByte(), encoded[2])
        assertEquals(0x04.toByte(), encoded[3])
        assertArrayEquals("hi".toByteArray(), encoded.copyOfRange(4, encoded.size))

        val decoded = DataChunk.decode(encoded)
        assertNotNull(decoded)
        assertEquals(0x01020304L, decoded!!.first)
        assertArrayEquals("hi".toByteArray(), decoded.second)

        // Too short to carry an id.
        assertNull(DataChunk.decode("abc".toByteArray()))
    }

    @Test
    fun counterZeroIsRejected() {
        // Both sides reserve 0 so an absent or zeroed counter is never a
        // valid first message.
        assertNull(Message.parse("""{"v":4,"t":"unpair","c":0}""".toByteArray()))
        assertNull(Message.parse("""{"v":4,"t":"unpair"}""".toByteArray()))
        assertNull(Message.parse("""{"v":4,"t":"unpair","c":-1}""".toByteArray()))
        assertNotNull(Message.parse("""{"v":4,"t":"unpair","c":1}""".toByteArray()))
    }

    @Test
    fun versionMismatchIsRejected() {
        // No negotiation to an older dialect: a mismatch is a hard stop.
        assertNull(Message.parse("""{"v":5,"t":"unpair","c":1}""".toByteArray()))
        // v3 is the version that sent the nonce in the clear. Refusing it is
        // the point of the bump: negotiating down would restore the grinding
        // attack.
        assertNull(Message.parse("""{"v":3,"t":"unpair","c":1}""".toByteArray()))
        assertNull(Message.parse("""{"t":"unpair","c":1}""".toByteArray()))
        assertNull(Message.parse("""{"v":4,"t":"nope","c":1}""".toByteArray()))
    }

    @Test
    fun statusMessageShape() {
        // Duplicated in TestInterop::statusMessageShape.
        assertEquals("statusRequest", MessageType.STATUS_REQUEST.wire)
        assertEquals("statusReport", MessageType.STATUS_REPORT.wire)

        // A request carries nothing but the envelope. The phone cannot ask
        // for a particular probe, and nothing it sends reaches the desktop's
        // status helper.
        val request = JSONObject(String(Message.statusRequest(7).toJson(), Charsets.UTF_8))
        assertEquals(setOf("v", "t", "c"), request.keys().asSequence().toSet())

        // The snapshot is nested under "status", never flattened into the
        // envelope: a future field of it must not be able to collide with
        // "v", "t" or "c".
        val snapshot = JSONObject().put("hostname", "box")
        val report = JSONObject(String(Message.statusReport(8, snapshot).toJson(), Charsets.UTF_8))
        assertEquals("statusReport", report.getString("t"))
        assertEquals("box", report.getJSONObject("status").getString("hostname"))
        assertFalse(report.has("error"))

        // Failure travels as the same type with "error" instead of "status",
        // so there is one message to handle rather than two.
        val unavailable = JSONObject(
            String(Message.statusUnavailable(9, "no maze-tools").toJson(), Charsets.UTF_8)
        )
        assertEquals("statusReport", unavailable.getString("t"))
        assertEquals("no maze-tools", unavailable.getString("error"))
        assertFalse(unavailable.has("status"))

        // "nothing changed" is the envelope and nothing else. A dashboard
        // polls every few seconds and an idle machine reads the same each
        // time; this is what stops that costing a fresh snapshot per poll.
        assertEquals("statusUnchanged", MessageType.STATUS_UNCHANGED.wire)
        val unchanged = JSONObject(
            String(Message.statusUnchanged(10).toJson(), Charsets.UTF_8)
        )
        assertEquals(setOf("v", "t", "c"), unchanged.keys().asSequence().toSet())
    }

    @Test
    fun capabilityWireNames() {
        // Capability names are matched as strings across the two clients; a
        // rename on one side alone silently disables the feature rather than
        // failing, which is why they are pinned here.
        assertEquals("fileTransfer", Capability.FILE_TRANSFER.wire)
        assertEquals("systemStatus", Capability.SYSTEM_STATUS.wire)
        assertEquals(Capability.SYSTEM_STATUS, Capability.from("systemStatus"))

        // An unknown name is not guessed at. Deliberately a name with no
        // plans behind it: "guardControl" sat here once and then became real,
        // which is how this assertion caught itself going stale.
        assertNull(Capability.from("teleport"))
        assertNull(Capability.from("commands "))

        // Pairing grants everything this build implements. The two sets are
        // still distinct concepts — advertising is "I implement this",
        // enabling is "you may use it" — but they start equal, because
        // pairing is already the deliberate act and a second wall in front of
        // every feature only made the app look broken.
        assertTrue(Capability.SYSTEM_STATUS in Capability.SUPPORTED)
        assertTrue(Capability.SYSTEM_STATUS in Capability.DEFAULT_ENABLED)
        assertEquals(Capability.SUPPORTED, Capability.DEFAULT_ENABLED)
    }

    @Test
    fun commandMessageShape() {
        // Duplicated in TestInterop::commandMessageShape.
        assertEquals("commandList", MessageType.COMMAND_LIST.wire)
        assertEquals("commandCatalog", MessageType.COMMAND_CATALOG.wire)
        assertEquals("commandRun", MessageType.COMMAND_RUN.wire)
        assertEquals("commandResult", MessageType.COMMAND_RESULT.wire)

        // The catalogue carries id, label and confirm — and never argv. This
        // is the most important shape in the feature: the phone is not told
        // what a command *is*, only what it is called.
        val entries = JSONArray().put(
            JSONObject().put("id", "lock").put("label", "Lock screen").put("confirm", false)
        )
        val catalog = JSONObject(
            String(Message.commandCatalog(3, entries).toJson(), Charsets.UTF_8)
        )
        val row = catalog.getJSONArray("commands").getJSONObject(0)
        assertEquals("lock", row.getString("id"))
        assertFalse("the catalogue must never carry the command line", row.has("argv"))

        // A run is an id and a request id. There is no argv field to fill in.
        val run = JSONObject(String(Message.commandRun(4, 9, "lock").toJson(), Charsets.UTF_8))
        assertEquals(
            setOf("v", "t", "c", "id", "requestId"),
            run.keys().asSequence().toSet(),
        )

        val result = JSONObject(
            String(Message.commandResult(6, 9, "lock", 0, "ok").toJson(), Charsets.UTF_8)
        )
        assertEquals(0, result.getInt("exitCode"))
        assertFalse(result.has("error"))
    }

    @Test
    fun capabilityCommandsName() {
        assertEquals("commands", Capability.COMMANDS.wire)
        assertEquals(Capability.COMMANDS, Capability.from("commands"))
        assertTrue(Capability.COMMANDS in Capability.SUPPORTED)
        assertTrue(Capability.COMMANDS in Capability.DEFAULT_ENABLED)
    }

    @Test
    fun aiMessageShape() {
        // Duplicated in TestInterop::aiMessageShape.
        assertEquals("aiModels", MessageType.AI_MODELS.wire)
        assertEquals("aiModelList", MessageType.AI_MODEL_LIST.wire)
        assertEquals("aiPrompt", MessageType.AI_PROMPT.wire)
        assertEquals("aiChunk", MessageType.AI_CHUNK.wire)
        assertEquals("aiDone", MessageType.AI_DONE.wire)

        // A prompt is one line of text plus which model to use. No history
        // and no system prompt: the conversation lives on the computer, so
        // this phone cannot rewrite what was said earlier or replace Maze
        // AI's persona.
        val prompt = JSONObject(
            String(Message.aiPrompt(1, 7, "gemma3", "hi").toJson(), Charsets.UTF_8)
        )
        assertEquals(
            setOf("v", "t", "c", "requestId", "model", "text"),
            prompt.keys().asSequence().toSet(),
        )
        assertFalse("a prompt carried history", prompt.has("messages"))
        assertFalse("a prompt offered tools", prompt.has("tools"))

        val chunk = JSONObject(String(Message.aiChunk(2, 7, "Hel").toJson(), Charsets.UTF_8))
        assertEquals(2L, chunk.getLong("c"))
        assertEquals("Hel", chunk.getString("text"))

        assertFalse(JSONObject(String(Message.aiDone(3, 7, null).toJson(), Charsets.UTF_8))
            .has("error"))
        assertEquals(
            "no model",
            JSONObject(String(Message.aiDone(4, 7, "no model").toJson(), Charsets.UTF_8))
                .getString("error"),
        )

        assertEquals("ai", Capability.AI.wire)
        assertTrue(Capability.AI in Capability.SUPPORTED)
        assertTrue(Capability.AI in Capability.DEFAULT_ENABLED)
    }

    @Test
    fun guardMessageShape() {
        // Duplicated in TestInterop::guardMessageShape.
        assertEquals("guardStatus", MessageType.GUARD_STATUS.wire)
        assertEquals("guardReport", MessageType.GUARD_REPORT.wire)
        assertEquals("guardRequest", MessageType.GUARD_REQUEST.wire)
        assertEquals("guardResult", MessageType.GUARD_RESULT.wire)

        // A request is a device name and a boolean. There is no verb field,
        // and that is the point: PANIC and RESTORE have nowhere to be written
        // even by a peer that knows they exist on the far side.
        val request = JSONObject(
            String(Message.guardRequest(1, "camera", true).toJson(), Charsets.UTF_8)
        )
        assertEquals(
            setOf("v", "t", "c", "device", "on"),
            request.keys().asSequence().toSet(),
        )
        assertFalse("a request could name a verb", request.has("verb"))
        assertFalse("a request could carry a command", request.has("command"))

        val result = JSONObject(
            String(Message.guardResult(2, "wifi", "off", null).toJson(), Charsets.UTF_8)
        )
        assertEquals("off", result.getString("state"))
        assertFalse(result.has("error"))

        assertEquals("guardControl", Capability.GUARD_CONTROL.wire)
        assertTrue(Capability.GUARD_CONTROL in Capability.SUPPORTED)
        // The privileged one is granted by pairing like the rest. What still
        // constrains it lives elsewhere: PANIC and RESTORE have no
        // representation in this protocol, the device table is fixed, and the
        // computer logs and announces every change.
        assertTrue(Capability.GUARD_CONTROL in Capability.DEFAULT_ENABLED)

        // Computer -> phone only, but still matched as a string like every
        // other capability, and still granted by pairing like the rest.
        assertEquals("openOnPhone", Capability.OPEN_ON_PHONE.wire)
        assertTrue(Capability.OPEN_ON_PHONE in Capability.SUPPORTED)
        assertTrue(Capability.OPEN_ON_PHONE in Capability.DEFAULT_ENABLED)

        assertEquals(MessageType.OPEN_ON_PHONE.wire, "openOnPhone")
        val openOnPhone = JSONObject(
            String(Message.openOnPhone(1, "https://example.com").toJson(), Charsets.UTF_8)
        )
        assertEquals("https://example.com", openOnPhone.getString("text"))
    }

    @Test
    fun mediaMessageShape() {
        // Duplicated in TestInterop::mediaMessageShape.
        assertEquals("media", Capability.MEDIA.wire)
        assertTrue(Capability.MEDIA in Capability.SUPPORTED)

        assertEquals("mediaRequest", MessageType.MEDIA_REQUEST.wire)
        assertEquals("mediaState", MessageType.MEDIA_STATE.wire)
        assertEquals("mediaCommand", MessageType.MEDIA_COMMAND.wire)

        val request = JSONObject(String(Message.mediaRequest(1, true).toJson(), Charsets.UTF_8))
        assertTrue(request.getBoolean("subscribe"))

        val command = JSONObject(
            String(Message.mediaCommand(2, "spotify", "seek", 61000).toJson(), Charsets.UTF_8)
        )
        assertEquals("spotify", command.getString("player"))
        assertEquals("seek", command.getString("action"))
        assertEquals(61000L, command.getLong("value"))

        val state = JSONObject(
            String(
                Message.mediaState(3, JSONObject().put("active", "spotify")).toJson(),
                Charsets.UTF_8,
            )
        )
        assertEquals("spotify", state.getJSONObject("media").getString("active"))

        // The desktop's action table, name for name.
        assertEquals(
            listOf(
                "play", "pause", "playPause", "next", "previous", "stop",
                "seek", "setVolume", "systemVolume", "systemMute",
            ),
            com.mazeconnect.core.protocol.MediaAction.entries.map { it.wire },
        )
    }

    @Test
    fun sasKnownAnswer() {
        // Duplicated in TestInterop::sasKnownAnswer.
        val a = ByteArray(91) { it.toByte() }
        val b = ByteArray(91) { (it + 100).toByte() }
        assertEquals(
            "876154",
            Sas.derive(a, b, ByteArray(Sas.NONCE_SIZE) { 1 }, ByteArray(Sas.NONCE_SIZE) { 2 }),
        )
    }

    @Test
    fun phoneMessageShape() {
        // Duplicated in TestInterop::phoneMessageShape.
        assertEquals("phoneStatus", Capability.PHONE_STATUS.wire)
        assertEquals("findPhone", Capability.FIND_PHONE.wire)
        assertEquals("shareText", Capability.SHARE_TEXT.wire)
        assertTrue(Capability.PHONE_STATUS in Capability.SUPPORTED)
        assertTrue(Capability.FIND_PHONE in Capability.SUPPORTED)
        assertTrue(Capability.SHARE_TEXT in Capability.SUPPORTED)
        // New to 0.15.0: must not be in the legacy set, or pairings made
        // before it would never be granted them.
        assertTrue(Capability.PHONE_STATUS !in Capability.LEGACY_KNOWN)

        assertEquals("phoneStatusRequest", MessageType.PHONE_STATUS_REQUEST.wire)
        assertEquals("phoneStatus", MessageType.PHONE_STATUS.wire)
        assertEquals("findPhone", MessageType.FIND_PHONE.wire)
        assertEquals("findPhoneResult", MessageType.FIND_PHONE_RESULT.wire)
        assertEquals("shareText", MessageType.SHARE_TEXT.wire)

        val report = JSONObject(
            String(
                Message.phoneStatus(4, JSONObject().put("battery", JSONObject().put("level", 50)))
                    .toJson(),
                Charsets.UTF_8,
            )
        )
        assertEquals(50, report.getJSONObject("status").getJSONObject("battery").getInt("level"))

        val ring = JSONObject(String(Message.findPhone(5, true).toJson(), Charsets.UTF_8))
        assertTrue(ring.getBoolean("ring"))

        val answer = JSONObject(
            String(Message.findPhoneResult(6, false, "off").toJson(), Charsets.UTF_8)
        )
        assertEquals(false, answer.getBoolean("ringing"))
        assertEquals("off", answer.getString("error"))

        val text = JSONObject(String(Message.shareText(7, "a\nb").toJson(), Charsets.UTF_8))
        assertEquals("a\nb", text.getString("text"))
    }

    @Test
    fun remoteAndFolderMessageShape() {
        // Duplicated in TestInterop::remoteAndFolderMessageShape.
        assertEquals("remoteInput", Capability.REMOTE_INPUT.wire)
        assertEquals("presenter", Capability.PRESENTER.wire)
        assertEquals("clipboardSync", Capability.CLIPBOARD_SYNC.wire)
        assertEquals("sharedFolder", Capability.SHARED_FOLDER.wire)

        assertEquals("inputSession", MessageType.INPUT_SESSION.wire)
        assertEquals("inputEvent", MessageType.INPUT_EVENT.wire)
        assertEquals("inputState", MessageType.INPUT_STATE.wire)
        assertEquals("folderList", MessageType.FOLDER_LIST.wire)
        assertEquals("folderListing", MessageType.FOLDER_LISTING.wire)
        assertEquals("folderFetch", MessageType.FOLDER_FETCH.wire)
        assertEquals("folderFetchResult", MessageType.FOLDER_FETCH_RESULT.wire)
        assertEquals("clipboardSync", MessageType.CLIPBOARD_SYNC.wire)

        val session = JSONObject(String(Message.inputSession(1, true, "full").toJson(), Charsets.UTF_8))
        assertTrue(session.getBoolean("start"))
        assertEquals("full", session.getString("mode"))

        // An event is flat: its fields sit beside the envelope.
        val event = JSONObject(
            String(
                Message.inputEvent(2, JSONObject().put("kind", "move").put("dx", 3.5).put("dy", -1))
                    .toJson(),
                Charsets.UTF_8,
            )
        )
        assertEquals("move", event.getString("kind"))
        assertEquals(3.5, event.getDouble("dx"), 0.0)

        val list = JSONObject(String(Message.folderList(3, 7, "Docs").toJson(), Charsets.UTF_8))
        assertEquals(7L, list.getLong("requestId"))
        assertEquals("Docs", list.getString("path"))
    }
}

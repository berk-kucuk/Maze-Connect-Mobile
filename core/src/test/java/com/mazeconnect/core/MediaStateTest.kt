package com.mazeconnect.core

import com.mazeconnect.core.protocol.MediaState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media state goes onto the screen and, through the media session, onto
 * the lock screen. Same standard as the dashboard snapshot: it comes from an
 * authenticated computer, not a trusted one, so every field is bounded here.
 */
class MediaStateTest {

    private fun player(
        id: String = "spotify",
        status: String = "playing",
        extra: JSONObject.() -> Unit = {},
    ) = JSONObject()
        .put("id", id)
        .put("name", "Spotify")
        .put("status", status)
        .put("title", "Seven Nation Army")
        .put("artist", "The White Stripes")
        .put("album", "Elephant")
        .put("lengthMs", 231000)
        .put("positionMs", 12000)
        .put("canPlay", true)
        .put("canPause", true)
        .put("canNext", true)
        .put("canPrevious", false)
        .put("canSeek", true)
        .put("volume", 50)
        .apply(extra)

    private fun state(vararg players: JSONObject, extra: JSONObject.() -> Unit = {}) =
        JSONObject().put("players", JSONArray(players.toList())).apply(extra)

    @Test
    fun aRealisticStateParses() {
        val parsed = MediaState.parse(
            state(player()) {
                put("active", "spotify")
                put("systemVolume", 40)
                put("systemMuted", true)
            },
            receivedAtMs = 1000,
        )
        assertNotNull(parsed)
        val p = parsed!!.active!!
        assertEquals("Seven Nation Army", p.title)
        assertEquals(MediaState.Status.PLAYING, p.status)
        assertEquals(231000L, p.lengthMs)
        assertEquals(50, p.volume)
        assertFalse(p.canPrevious)
        assertEquals(40, parsed.systemVolume)
        assertTrue(parsed.systemMuted)
        assertNull(parsed.error)
    }

    @Test
    fun theVolumeAndPositionAreBounded() {
        val parsed = MediaState.parse(
            state(player {
                put("volume", 400)
                put("positionMs", 999_999_999)
            }) { put("systemVolume", -5) },
            0,
        )!!
        assertNull(parsed.players[0].volume)
        assertEquals(231000L, parsed.players[0].positionMs) // clamped to the length
        assertNull(parsed.systemVolume)
    }

    @Test
    fun textIsCleanedAndCapped() {
        val parsed = MediaState.parse(
            state(player {
                put("title", "a\nb\u0000c" + "x".repeat(1000))
            }),
            0,
        )!!
        val title = parsed.players[0].title
        assertFalse(title.any { it.code < 0x20 })
        assertTrue(title.length <= 256)
        assertTrue(title.startsWith("a b c"))
    }

    @Test
    fun aPlayerWithABadIdIsDroppedAndTheRestKept() {
        val parsed = MediaState.parse(
            state(player(id = "../../etc"), player(id = "vlc"), player(id = "")),
            0,
        )!!
        assertEquals(listOf("vlc"), parsed.players.map { it.id })
    }

    @Test
    fun anActiveIdThatIsNotListedIsIgnored() {
        val parsed = MediaState.parse(state(player()) { put("active", "ghost") }, 0)!!
        assertNull(parsed.activeId)
        assertEquals("spotify", parsed.active?.id) // falls back to the first
    }

    @Test
    fun tooManyPlayersAreCapped() {
        val many = (1..40).map { player(id = "p$it") }.toTypedArray()
        assertEquals(12, MediaState.parse(state(*many), 0)!!.players.size)
    }

    @Test
    fun seekingIsOnlyOfferedWithALength() {
        val parsed = MediaState.parse(state(player { put("lengthMs", 0) }), 0)!!
        assertFalse(parsed.players[0].canSeek)
    }

    @Test
    fun positionMovesOnlyWhilePlaying() {
        val playing = MediaState.parse(state(player()), receivedAtMs = 1_000)!!.players[0]
        assertEquals(17_000L, playing.positionAt(nowMs = 6_000, receivedAtMs = 1_000))
        // Never past the end of the track.
        assertEquals(231_000L, playing.positionAt(nowMs = 10_000_000, receivedAtMs = 1_000))

        val paused = MediaState.parse(state(player(status = "paused")), 1_000)!!.players[0]
        assertEquals(12_000L, paused.positionAt(nowMs = 60_000, receivedAtMs = 1_000))
    }

    @Test
    fun anUnavailableComputerSaysWhy() {
        val parsed = MediaState.parse(
            JSONObject().put("error", "no desktop session bus"),
            0,
        )!!
        assertTrue(parsed.players.isEmpty())
        assertEquals("no desktop session bus", parsed.error)
    }

    @Test
    fun wrongTypesAreDroppedNotGuessed() {
        val parsed = MediaState.parse(
            state(player {
                put("title", 42)
                put("lengthMs", "long")
                put("volume", "loud")
            }),
            0,
        )!!
        val p = parsed.players[0]
        assertEquals("", p.title)
        assertEquals(0L, p.lengthMs)
        assertNull(p.volume)
    }
}

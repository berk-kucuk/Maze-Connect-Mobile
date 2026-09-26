package com.mazeconnect.core.protocol

import org.json.JSONObject

/**
 * The vocabulary for [Message.mediaCommand]. Mirrors the desktop's
 * `MediaAction` table name for name; InteropTest pins both sides.
 */
enum class MediaAction(val wire: String) {
    PLAY("play"),
    PAUSE("pause"),
    PLAY_PAUSE("playPause"),
    NEXT("next"),
    PREVIOUS("previous"),
    STOP("stop"),

    /** value = position in milliseconds */
    SEEK("seek"),

    /** value = the player's own volume, 0..100 */
    SET_VOLUME("setVolume"),

    /** value = the computer's output volume, 0..100 */
    SYSTEM_VOLUME("systemVolume"),

    /** value = 1 to mute the computer's output, 0 to unmute */
    SYSTEM_MUTE("systemMute"),
}

/**
 * The computer's media players, parsed from a `mediaState`.
 *
 * Same standard as [SystemStatus]: this goes onto the screen — and onto the
 * lock screen, through the media notification — so every field is bounded
 * and cleaned here rather than trusted. A paired computer is authenticated,
 * not infallible. A player entry that fails is dropped; the rest still show.
 */
data class MediaState(
    val players: List<Player>,
    /** Which player to show first — the computer's pick, if it is listed. */
    val activeId: String?,
    /** The computer's output volume, 0..150, or null when it cannot be read. */
    val systemVolume: Int?,
    val systemMuted: Boolean,
    /** Why there are no players at all (no session bus, switched off). */
    val error: String?,
    /** Why the last command did nothing ("Spotify has no next track"). */
    val notice: String?,
    /** When this arrived, for moving the position forward while playing. */
    val receivedAtMs: Long,
) {
    data class Player(
        val id: String,
        val name: String,
        val status: Status,
        val title: String,
        val artist: String,
        val album: String,
        val lengthMs: Long,
        val positionMs: Long,
        val canPlay: Boolean,
        val canPause: Boolean,
        val canNext: Boolean,
        val canPrevious: Boolean,
        val canSeek: Boolean,
        /** 0..100, or null when the player has no volume of its own. */
        val volume: Int?,
    ) {
        /** Where playback is now, not when the state was sent. */
        fun positionAt(nowMs: Long, receivedAtMs: Long): Long {
            val moved = if (status == Status.PLAYING) (nowMs - receivedAtMs).coerceAtLeast(0) else 0
            val position = positionMs + moved
            return if (lengthMs > 0) position.coerceAtMost(lengthMs) else position
        }
    }

    enum class Status { PLAYING, PAUSED, STOPPED;

        companion object {
            fun from(wire: String?): Status = when (wire) {
                "playing" -> PLAYING
                "paused" -> PAUSED
                else -> STOPPED
            }
        }
    }

    val active: Player?
        get() = players.firstOrNull { it.id == activeId } ?: players.firstOrNull()

    companion object {
        private const val MAX_PLAYERS = 12
        private const val MAX_ID_CHARS = 64
        private const val MAX_NAME_CHARS = 64
        private const val MAX_TEXT_CHARS = 256
        private const val MAX_NOTE_CHARS = 200
        private const val MAX_MS = 24L * 3600 * 1000
        private val ID_PATTERN = Regex("^[A-Za-z0-9_.-]{1,64}$")

        /** Null only when [json] is not an object at all. */
        fun parse(json: JSONObject?, receivedAtMs: Long): MediaState? {
            json ?: return null

            val players = ArrayList<Player>()
            val array = json.optJSONArray("players")
            if (array != null) {
                for (i in 0 until minOf(array.length(), MAX_PLAYERS)) {
                    parsePlayer(array.optJSONObject(i))?.let(players::add)
                }
            }
            val activeId = text(json, "active", MAX_ID_CHARS)
                ?.takeIf { id -> players.any { it.id == id } }

            val volume = json.opt("systemVolume")
            return MediaState(
                players = players,
                activeId = activeId,
                systemVolume = (volume as? Number)?.toInt()?.takeIf { it in 0..150 },
                systemMuted = json.optBoolean("systemMuted", false),
                error = text(json, "error", MAX_NOTE_CHARS)?.takeIf { it.isNotEmpty() },
                notice = text(json, "notice", MAX_NOTE_CHARS)?.takeIf { it.isNotEmpty() },
                receivedAtMs = receivedAtMs,
            )
        }

        private fun parsePlayer(obj: JSONObject?): Player? {
            obj ?: return null
            val id = obj.opt("id") as? String ?: return null
            if (!ID_PATTERN.matches(id)) return null
            val length = millis(obj, "lengthMs")
            return Player(
                id = id,
                name = text(obj, "name", MAX_NAME_CHARS)?.takeIf { it.isNotBlank() } ?: id,
                status = Status.from(obj.opt("status") as? String),
                title = text(obj, "title", MAX_TEXT_CHARS) ?: "",
                artist = text(obj, "artist", MAX_TEXT_CHARS) ?: "",
                album = text(obj, "album", MAX_TEXT_CHARS) ?: "",
                lengthMs = length,
                positionMs = millis(obj, "positionMs").let { if (length > 0) it.coerceAtMost(length) else it },
                canPlay = obj.optBoolean("canPlay", false),
                canPause = obj.optBoolean("canPause", false),
                canNext = obj.optBoolean("canNext", false),
                canPrevious = obj.optBoolean("canPrevious", false),
                canSeek = obj.optBoolean("canSeek", false) && length > 0,
                volume = (obj.opt("volume") as? Number)?.toInt()?.takeIf { it in 0..100 },
            )
        }

        /** A non-negative whole number of milliseconds, at most a day. */
        private fun millis(obj: JSONObject, key: String): Long {
            val raw = obj.opt(key) as? Number ?: return 0
            val d = raw.toDouble()
            if (d.isNaN() || d.isInfinite() || d < 0) return 0
            return d.toLong().coerceAtMost(MAX_MS)
        }

        /**
         * A string with control characters turned into spaces and the length
         * capped. Cleaned rather than rejected: a track title with a stray tab
         * in it is still the title, whereas a null would blank the row.
         */
        private fun text(obj: JSONObject, key: String, maxChars: Int): String? {
            val raw = obj.opt(key) as? String ?: return null
            val cleaned = buildString(minOf(raw.length, maxChars)) {
                for (c in raw) {
                    if (length >= maxChars) break
                    val code = c.code
                    append(if (code < 0x20 || code == 0x7F || code in 0x80..0x9F) ' ' else c)
                }
            }
            return cleaned.trim()
        }
    }
}

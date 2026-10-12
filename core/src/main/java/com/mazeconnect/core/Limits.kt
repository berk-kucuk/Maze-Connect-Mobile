package com.mazeconnect.core

/**
 * Receiver-enforced limits on untrusted input.
 *
 * Every value here is checked against what a peer actually sends; none is
 * ever taken from a peer-supplied "size" field. Kept in lock-step with the
 * desktop client's `Limits.h`.
 */
object Limits {
    /** Largest control-plane (JSON) frame. */
    const val MAX_CONTROL_FRAME = 512 * 1024

    /** Largest single file-transfer data frame. */
    const val MAX_DATA_FRAME = 256 * 1024

    /** Largest accepted incoming file. */
    const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024

    const val MAX_BEACON_DATAGRAM = 2048

    const val MAX_DEVICE_NAME_CHARS = 64
    const val MAX_DEVICE_ID_CHARS = 64
    const val MAX_FILENAME_CHARS = 255

    /** Longest "open on phone" text accepted from the computer. */
    const val MAX_OPEN_TEXT_CHARS = 4096

    /** Longest text this phone shares to a computer's clipboard. Matches the
     *  desktop's kMaxShareTextChars; longer is refused, never truncated. */
    const val MAX_SHARE_TEXT_CHARS = 16384

    const val HANDSHAKE_TIMEOUT_MS = 15_000

    /**
     * An established link with nothing to say sends a Ping after this much
     * idle time, and is dropped if nothing — not even a Pong — has arrived
     * within the timeout (three missed pings). TCP alone does not catch a
     * half-open connection: a link can survive a Wi-Fi reassociation, a NAT
     * table eviction, or a DHCP lease change with no FIN/RST ever arriving
     * on either side, and without this it can sit "connected" and dead
     * until the app is restarted.
     */
    const val HEARTBEAT_INTERVAL_MS = 15_000L
    const val HEARTBEAT_TIMEOUT_MS = 45_000L

    const val REPLAY_WINDOW_SIZE = 512

    const val MAX_CONNECTIONS_PER_PEER = 4
    /** TLS handshakes in flight at once on the listener. */
    const val MAX_PENDING_HANDSHAKES = 8
    /** Links from not-yet-paired peers held at once, from all addresses. */
    const val MAX_UNPAIRED_LINKS = 16
    /** An unpaired link that has not started pairing by then is closed. */
    const val UNPAIRED_LINK_TTL_MS = 60_000L
    /** Hard ceiling for an unpaired link, even while a code is on screen. */
    const val PAIRING_STAGE_MAX_MS = 300_000L
    const val PAIRING_ATTEMPTS_PER_MINUTE = 5

    // ---- approval (docs/PROTOCOL.md § approval). Mirrors the desktop. ----

    const val APPROVAL_NONCE_SIZE = 32
    const val MAX_APPROVAL_HOST_CHARS = 64
    const val MAX_APPROVAL_USER_CHARS = 64
    const val MAX_APPROVAL_DETAIL_CHARS = 120
    const val MAX_APPROVAL_SIGNATURE = 72
    const val APPROVAL_CODE_DIGITS = 2
    /** An approval this phone did not answer within this long is dropped. */
    const val APPROVAL_TIMEOUT_MS = 45_000L
    /** An unlock challenge is only taken this long after this phone asked. */
    const val UNLOCK_ASK_WINDOW_MS = 30_000L
    /** Challenges for administrator access one computer may raise a minute. */
    const val MAX_APPROVAL_REQUESTS_PER_MINUTE = 6
    /** Locks one computer may ask for in a minute. */
    const val MAX_PHONE_LOCKS_PER_MINUTE = 3
}

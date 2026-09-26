package com.mazeconnect.core.protocol

/**
 * Optional features a device can offer. A capability only becomes active
 * when *both* peers advertise it and the local user has enabled it — an
 * advertisement alone never grants anything.
 */
enum class Capability(val wire: String) {
    FILE_TRANSFER("fileTransfer"),

    /** Read-only dashboard snapshot of the paired computer. */
    SYSTEM_STATUS("systemStatus"),

    /** Run entries from the allow-list the computer's owner wrote. */
    COMMANDS("commands"),

    /** Chat with Maze AI, through the Ollama on the computer. */
    AI("ai"),

    /**
     * Toggle maze-guard killswitches. The one privileged capability, and the
     * only one that changes the computer's security posture rather than
     * reading it.
     */
    GUARD_CONTROL("guardControl"),

    /** Push the desktop's clipboard to the phone to open — a URL is opened
     *  directly, anything else is copied to the phone's clipboard. */
    OPEN_ON_PHONE("openOnPhone"),

    /** See the computer's media players and control them: play, pause,
     *  skip, seek, and the player and system volume. */
    MEDIA("media"),

    /** Tell the computer this phone's battery, storage, memory, network and
     *  ringer — so its dashboard shows the phone rather than itself. */
    PHONE_STATUS("phoneStatus"),

    /** Let the computer make this phone ring, loudly, even on silent. */
    FIND_PHONE("findPhone"),

    /** Send text or a link from this phone to the computer's clipboard. */
    SHARE_TEXT("shareText"),

    /** Move the computer's pointer and type on it. The computer's owner has
     *  to allow it for this phone, and confirm once on the computer. */
    REMOTE_INPUT("remoteInput"),

    /** Press a fixed handful of slide keys on the computer. */
    PRESENTER("presenter"),

    /** Keep this phone's clipboard and the computer's in step, while both
     *  owners have it switched on. */
    CLIPBOARD_SYNC("clipboardSync"),

    /** Browse and download from the one folder the computer shares. */
    SHARED_FOLDER("sharedFolder");

    companion object {
        /** Unknown names degrade to null rather than being guessed at. */
        fun from(wire: String): Capability? = entries.firstOrNull { it.wire == wire }

        /**
         * What this build implements — the set advertised in `hello`.
         *
         * Distinct from [DEFAULT_ENABLED], and the distinction matters:
         * advertising says "I implement this", enabling says "you may use
         * it". They were one set while fileTransfer was the only capability,
         * which made it easy to miss that they answer different questions.
         * Mirrors the desktop client's `supportedCapabilities()`.
         */
        val SUPPORTED: Set<Capability> =
            setOf(
                FILE_TRANSFER, SYSTEM_STATUS, COMMANDS, AI, GUARD_CONTROL, OPEN_ON_PHONE, MEDIA,
                PHONE_STATUS, FIND_PHONE, SHARE_TEXT, REMOTE_INPUT, PRESENTER, CLIPBOARD_SYNC,
                SHARED_FOLDER,
            )

        /**
         * What a newly paired computer may be asked for: **everything.**
         *
         * These used to default off, one switch per capability per device.
         * Pairing already requires a person to compare a six-digit code on
         * two screens and agree on both; requiring every feature to be opted
         * into afterwards did not add a decision, it added a wall — a freshly
         * paired phone sat on "asking the computer…" indefinitely with
         * nothing explaining why.
         *
         * Revoking still works and takes effect immediately. It is done on
         * the computer, which is the machine being controlled — see the
         * desktop's `defaultEnabledCapabilities()` for what still constrains
         * the privileged one.
         */
        val DEFAULT_ENABLED: Set<Capability> = SUPPORTED

        /** Every capability that existed before a pairing record started
         *  listing which ones it knew about (0.14.0). Only for reading such
         *  older records — see PairedDeviceStore.load(). */
        val LEGACY_KNOWN: Set<Capability> =
            setOf(FILE_TRANSFER, SYSTEM_STATUS, COMMANDS, AI, GUARD_CONTROL, OPEN_ON_PHONE)

        fun fromNames(names: Collection<String>): Set<Capability> =
            names.mapNotNull { from(it) }.toSet()

        fun toNames(capabilities: Collection<Capability>): List<String> =
            capabilities.map { it.wire }
    }
}

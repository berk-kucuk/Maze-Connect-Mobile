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
    OPEN_ON_PHONE("openOnPhone");

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
            setOf(FILE_TRANSFER, SYSTEM_STATUS, COMMANDS, AI, GUARD_CONTROL, OPEN_ON_PHONE)

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

        fun fromNames(names: Collection<String>): Set<Capability> =
            names.mapNotNull { from(it) }.toSet()

        fun toNames(capabilities: Collection<Capability>): List<String> =
            capabilities.map { it.wire }
    }
}

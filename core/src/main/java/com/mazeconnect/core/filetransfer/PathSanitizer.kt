package com.mazeconnect.core.filetransfer

import com.mazeconnect.core.Limits
import java.text.Normalizer

/**
 * Turns a peer-supplied filename into something safe to create, or rejects
 * it.
 *
 * A filename arriving over the network is fully attacker-controlled. These
 * rules are a strict filter rather than a blocklist of known-bad sequences:
 * this is the only layer between a hostile peer and an arbitrary file write.
 *
 * Mirrors the desktop client's `PathSanitizer` so a name refused on one
 * platform is refused on both.
 */
object PathSanitizer {

    private val RESERVED = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    )

    /** Returns a bare safe filename, or null if the name must be refused. */
    fun sanitizeFilename(rawName: String?): String? {
        if (rawName == null) return null

        // Normalize first: ".." can be spelled with combining characters or
        // fullwidth forms, and the dot check below must see the canonical
        // form rather than a lookalike.
        val name = Normalizer.normalize(rawName, Normalizer.Form.NFC).trim()

        if (name.isEmpty() || name.length > Limits.MAX_FILENAME_CHARS) return null
        if (name.any { it.code < 0x20 || it.code == 0x7F || it.code in 0x80..0x9F }) return null
        // No directory component may survive: the caller always joins this
        // onto the inbox path itself.
        if (name.contains('/') || name.contains('\\')) return null
        if (name == "." || name == "..") return null
        if (name.all { it == '.' }) return null
        // Would be parsed as an option by anything that later shells out.
        if (name.startsWith('-')) return null
        // Trailing dots/spaces are silently stripped by some filesystems,
        // which would make the created name differ from the validated one.
        if (name.endsWith('.') || name.endsWith(' ')) return null

        val stem = name.substringBefore('.').lowercase()
        if (stem in RESERVED) return null

        return name
    }

    /**
     * Pick a non-colliding name so a peer can never overwrite an existing
     * file by resending the same name. Returns null if no free name is found
     * within a bounded number of attempts.
     */
    fun uniqueName(sanitizedName: String, exists: (String) -> Boolean): String? {
        if (!exists(sanitizedName)) return sanitizedName

        val base = sanitizedName.substringBeforeLast('.', sanitizedName)
        val suffix = sanitizedName.substringAfterLast('.', "")

        for (i in 2 until 1000) {
            val candidate = if (suffix.isEmpty()) "$base ($i)" else "$base ($i).$suffix"
            if (!exists(candidate)) return candidate
        }
        return null
    }
}

package com.mazeconnect.core.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * A dashboard snapshot of the paired computer, parsed from a `statusReport`.
 *
 * This is where the snapshot stops being peer-supplied JSON and becomes
 * something the UI may draw. The desktop builds it from maze-tools' own
 * probes, so in practice it is well-formed — but "in practice" is not the
 * standard the rest of this protocol layer is held to. The link is
 * authenticated, not trustworthy: a paired computer is still a machine that
 * could be compromised, and a snapshot is the one message that goes almost
 * straight onto the screen.
 *
 * So every field is bounded here rather than at the point it is rendered:
 * strings are length-capped and stripped of control characters, lists are
 * capped in length, and percentages are clamped. A field that fails is
 * dropped, not defaulted to something plausible — the dashboard would rather
 * show less than show an invention.
 */
data class SystemStatus(
    val hostname: String,
    val generated: Long,
    val metrics: List<Metric>,
    val security: List<Service>,
    val network: List<NetworkRow>,
    val facts: List<String>,
    val hardeningScore: Int?,
    val hardeningChecks: List<Check>,
    val torState: State,
    val ollamaState: State,
    /** Sections the computer could not gather, named rather than omitted. */
    val unavailable: List<String>,
) {
    /**
     * Three states, not a boolean.
     *
     * "Unknown" is what a probe says when a service is not installed at all.
     * Flattening it into "inactive" would report a machine as unprotected on
     * the strength of a question it never answered.
     */
    enum class State { ACTIVE, INACTIVE, UNKNOWN;

        companion object {
            fun from(wire: String?): State = when (wire) {
                "active" -> ACTIVE
                "inactive" -> INACTIVE
                else -> UNKNOWN
            }
        }
    }

    data class Metric(val key: String, val label: String, val percent: Float, val detail: String)
    data class Service(val label: String, val state: State)
    data class NetworkRow(val label: String, val value: String, val state: State)
    data class Check(val label: String, val passed: Boolean)

    val isEmpty: Boolean
        get() = metrics.isEmpty() && security.isEmpty() && network.isEmpty() && facts.isEmpty()

    companion object {
        // Generous next to real values (labels are a word or two, a snapshot
        // is a couple of kilobytes) and small enough that a hostile report
        // cannot fill the screen with one string or the list with thousands
        // of rows.
        private const val MAX_LABEL_CHARS = 64
        private const val MAX_VALUE_CHARS = 120
        private const val MAX_ROWS = 32

        /**
         * Parse a snapshot, or return null if it is not usable at all.
         *
         * Null means "there is nothing here to show"; it does not mean the
         * link is bad. Individual bad fields are dropped rather than failing
         * the whole snapshot, because a computer with one broken probe should
         * still show the other seven.
         */
        fun parse(json: JSONObject?): SystemStatus? {
            if (json == null) return null

            val status = SystemStatus(
                hostname = json.boundedString("hostname", MAX_LABEL_CHARS) ?: "",
                generated = json.optLong("generated", 0L).coerceAtLeast(0L),
                metrics = json.rows("metrics") { row ->
                    val label = row.boundedString("label", MAX_LABEL_CHARS) ?: return@rows null
                    Metric(
                        key = row.boundedString("key", MAX_LABEL_CHARS) ?: "",
                        label = label,
                        // Clamped, not rejected: a percentage outside 0..100
                        // is a broken probe, and a meter is still readable.
                        percent = row.optDouble("percent", 0.0)
                            .toFloat()
                            .let { if (it.isNaN()) 0f else it.coerceIn(0f, 100f) },
                        detail = row.boundedString("detail", MAX_VALUE_CHARS) ?: "",
                    )
                },
                security = json.rows("security") { row ->
                    val label = row.boundedString("label", MAX_LABEL_CHARS) ?: return@rows null
                    Service(label, State.from(row.boundedString("state", MAX_LABEL_CHARS)))
                },
                network = json.rows("network") { row ->
                    val label = row.boundedString("label", MAX_LABEL_CHARS) ?: return@rows null
                    NetworkRow(
                        label = label,
                        value = row.boundedString("value", MAX_VALUE_CHARS) ?: "",
                        state = State.from(row.boundedString("good", MAX_LABEL_CHARS)),
                    )
                },
                // The icon name beside each fact is a freedesktop theme name
                // the desktop resolves; it means nothing on Android, so only
                // the human-readable half is kept.
                facts = json.rows("facts") { row ->
                    row.boundedString("value", MAX_VALUE_CHARS)
                },
                hardeningScore = json.optJSONObject("hardening")
                    ?.let { if (it.has("score")) it.optInt("score", 0).coerceIn(0, 100) else null },
                hardeningChecks = (json.optJSONObject("hardening") ?: JSONObject())
                    .rows("checks") { row ->
                        val label = row.boundedString("label", MAX_VALUE_CHARS) ?: return@rows null
                        Check(label, row.optBoolean("passed", false))
                    },
                torState = State.from(json.optJSONObject("services")
                    ?.boundedString("tor", MAX_LABEL_CHARS)),
                ollamaState = State.from(json.optJSONObject("services")
                    ?.boundedString("ollama", MAX_LABEL_CHARS)),
                unavailable = json.strings("unavailable"),
            )

            // Nothing renderable came through — treat it as no snapshot rather
            // than as a computer with nothing running.
            return if (status.isEmpty && status.hostname.isEmpty()) null else status
        }

        /** Length-capped and free of control characters, or null. */
        private fun JSONObject.boundedString(key: String, maxChars: Int): String? {
            val value = opt(key) as? String ?: return null
            if (value.length > maxChars) return null
            if (value.any { it.code < 0x20 || it.code == 0x7F || it.code in 0x80..0x9F }) {
                return null
            }
            return value
        }

        /** Map an array of objects, dropping the entries that do not parse. */
        private fun <T> JSONObject.rows(key: String, map: (JSONObject) -> T?): List<T> {
            val array = opt(key) as? JSONArray ?: return emptyList()
            val out = ArrayList<T>(minOf(array.length(), MAX_ROWS))
            for (i in 0 until minOf(array.length(), MAX_ROWS)) {
                val row = array.opt(i) as? JSONObject ?: continue
                map(row)?.let { out.add(it) }
            }
            return out
        }

        private fun JSONObject.strings(key: String): List<String> {
            val array = opt(key) as? JSONArray ?: return emptyList()
            val out = ArrayList<String>(minOf(array.length(), MAX_ROWS))
            for (i in 0 until minOf(array.length(), MAX_ROWS)) {
                val item = array.opt(i) as? String ?: continue
                if (item.length <= MAX_LABEL_CHARS) out.add(item)
            }
            return out
        }
    }
}

package com.mazeconnect.app.widget

import android.content.Context
import com.mazeconnect.core.RemoteCommand
import com.mazeconnect.core.protocol.SystemStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A pinned command's display shape, for the Commands widget — id and label
 *  to run and label with, plus whether the phone should ask before running
 *  it. No argv, same as [RemoteCommand] itself. */
data class PinnedCommand(val id: String, val label: String, val confirm: Boolean)

/**
 * The last snapshot, on disk, for the widget to draw — one per device.
 *
 * The widget runs in the launcher's process and cannot reach the link, so it
 * needs something already written down. This keeps exactly what the widget
 * shows — hostname, up to [MAX_METERS] meters, and a handful of counts
 * (services/network/hardening-checks that are "good") — and nothing else.
 *
 * That narrowness is deliberate. A full snapshot carries the local IP, the
 * kernel version, the hardware and which security services are running; none
 * of that appears on a home screen, so none of it is written to a file that
 * outlives the app's process. What is kept is what a passer-by could read off
 * the screen anyway.
 *
 * Files are keyed by device id, so two widgets configured for two different
 * computers each draw their own reading rather than fighting over one file.
 * In the app's private directory, so no other app can read it.
 */
class WidgetSnapshotStore(context: Context) {

    private val dir = context.applicationContext.filesDir

    private fun file(deviceId: String) = File(dir, "widget-snapshot-${sanitize(deviceId)}.json")
    private fun guardFile(deviceId: String) = File(dir, "widget-guard-${sanitize(deviceId)}.json")
    private fun commandsFile(deviceId: String) = File(dir, "widget-commands-${sanitize(deviceId)}.json")

    /** Overwrites the previous reading; only the latest is ever of interest. */
    fun save(deviceId: String, status: SystemStatus) {
        val meters = JSONArray()
        for (metric in status.metrics.take(MAX_METERS)) {
            meters.put(
                JSONObject()
                    .put("label", metric.label)
                    .put("percent", metric.percent.toDouble())
                    // The computer sends a detail beside every reading — a
                    // temperature, a used/total pair — and this file was
                    // dropping it, so no widget could ever show it however
                    // much room it had. It is the same class of information
                    // as the percentage beside it, not the kind of naming
                    // detail the counts below deliberately withhold.
                    .put("detail", metric.detail)
                    // Kept so a renderer can tell which reading leads
                    // without guessing from the label, which is the
                    // computer's display string and not stable.
                    .put("key", metric.key)
            )
        }
        // Security services, network rows and hardening checks are all kept
        // as counts rather than lists of names. The count is what a glance
        // can use ("4 of 5 running"); the names are detail for the app, and
        // a home screen is not the place to publish which specific
        // protections — or weaknesses — a machine has.
        val servicesRunning = status.security.count { it.state == SystemStatus.State.ACTIVE }
        // "Unknown" rows are not counted at all: a reading the computer
        // could not take is neither good nor bad, and folding it into either
        // would make the ratio a claim nobody checked.
        val servicesKnown = status.security.count { it.state != SystemStatus.State.UNKNOWN }
        val networkGood = status.network.count { it.state == SystemStatus.State.ACTIVE }
        val networkKnown = status.network.count { it.state != SystemStatus.State.UNKNOWN }
        val hardeningPassed = status.hardeningChecks.count { it.passed }
        val hardeningTotal = status.hardeningChecks.size

        val payload = JSONObject()
            .put("deviceId", deviceId)
            .put("hostname", status.hostname)
            .put("savedAt", System.currentTimeMillis())
            .put("metrics", meters)
            .put("hardening", status.hardeningScore ?: -1)
            .put("hardeningPassed", hardeningPassed)
            .put("hardeningTotal", hardeningTotal)
            .put("servicesRunning", servicesRunning)
            .put("servicesKnown", servicesKnown)
            .put("networkGood", networkGood)
            .put("networkKnown", networkKnown)

        runCatching { file(deviceId).writeText(payload.toString()) }
    }

    /** The stored reading and when it was taken, or null if there is none. */
    fun load(deviceId: String): Pair<SystemStatus, Long>? {
        val text = runCatching { file(deviceId).readText() }.getOrNull() ?: return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null

        val metrics = ArrayList<SystemStatus.Metric>(MAX_METERS)
        val array = json.optJSONArray("metrics") ?: JSONArray()
        for (i in 0 until minOf(array.length(), MAX_METERS)) {
            val row = array.optJSONObject(i) ?: continue
            metrics.add(
                SystemStatus.Metric(
                    key = row.optString("key"),
                    label = row.optString("label"),
                    // Clamped on the way out as well as on the way in: this
                    // file is ours, but a widget drawing a bar past its own
                    // width from a corrupt value would be a strange way to
                    // find that out.
                    percent = row.optDouble("percent", 0.0).toFloat().coerceIn(0f, 100f),
                    // Bounded here as well: an older file written before this
                    // field existed simply yields "", which every renderer
                    // already has to handle for a computer that sends none.
                    detail = row.optString("detail").take(MAX_DETAIL_CHARS),
                )
            )
        }

        // Rebuilt as synthetic rows carrying only the counts, so the widget
        // can render "4/5 services" or "9/10 hardening checks" without the
        // store ever keeping the names behind them.
        val security = syntheticStates(json, "servicesRunning", "servicesKnown") { active ->
            SystemStatus.Service(
                label = "",
                state = if (active) SystemStatus.State.ACTIVE else SystemStatus.State.INACTIVE,
            )
        }
        val network = syntheticStates(json, "networkGood", "networkKnown") { good ->
            SystemStatus.NetworkRow(
                label = "",
                value = "",
                state = if (good) SystemStatus.State.ACTIVE else SystemStatus.State.INACTIVE,
            )
        }
        val hardeningTotal = json.optInt("hardeningTotal", 0)
        val hardeningPassed = json.optInt("hardeningPassed", 0).coerceIn(0, hardeningTotal)
        val hardeningChecks = List(hardeningTotal) { index ->
            SystemStatus.Check(label = "", passed = index < hardeningPassed)
        }

        val status = SystemStatus(
            hostname = json.optString("hostname"),
            generated = 0,
            metrics = metrics,
            security = security,
            network = network,
            facts = emptyList(),
            hardeningScore = json.optInt("hardening", -1).takeIf { it >= 0 },
            hardeningChecks = hardeningChecks,
            torState = SystemStatus.State.UNKNOWN,
            ollamaState = SystemStatus.State.UNKNOWN,
            unavailable = emptyList(),
        )
        val savedAt = json.optLong("savedAt", 0L)
        if (savedAt <= 0L) return null
        return status to savedAt
    }

    /** Rebuilds a run of nameless "good"/"not good" rows from a
     *  positive-count/known-count pair, in that order — the shared shape
     *  behind the security, network and hardening summaries above. */
    private fun <T> syntheticStates(json: JSONObject, goodKey: String, knownKey: String, make: (Boolean) -> T): List<T> {
        val known = json.optInt(knownKey, 0)
        val good = json.optInt(goodKey, 0).coerceIn(0, known)
        return List(known) { index -> make(index < good) }
    }

    /**
     * The killswitch states, for the controls widget.
     *
     * Kept in its own file: the metrics snapshot is rewritten every minute by
     * the background reading, and folding the guard states into it would make
     * a stale toggle look fresh — or lose it entirely whenever a snapshot
     * arrived without one.
     */
    fun saveGuard(deviceId: String, states: Map<String, String>) {
        val payload = JSONObject()
        for ((device, state) in states) payload.put(device, state)
        runCatching { guardFile(deviceId).writeText(payload.toString()) }
    }

    fun guardStates(deviceId: String): Map<String, String> {
        val text = runCatching { guardFile(deviceId).readText() }.getOrNull() ?: return emptyMap()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, String>()
        for (key in json.keys()) {
            val value = json.optString(key)
            if (value.isNotEmpty()) out[key] = value
        }
        return out
    }

    /**
     * The pinned entries, for the Commands widget.
     *
     * Its own file, same reasoning as [saveGuard]: the dashboard snapshot
     * rewrites often and folding a rarely-changing list into it would mean
     * losing the pinned set the moment a snapshot arrived without one.
     * Capped at [MAX_PINNED_COMMANDS] — what the widget's cells can show —
     * so a computer with more pinned than that doesn't grow the file
     * pointlessly; which ones win is decided by the order the computer
     * sent them in, not chosen here.
     */
    fun savePinnedCommands(deviceId: String, commands: List<RemoteCommand>) {
        val array = JSONArray()
        for (command in commands.filter { it.pinned }.take(MAX_PINNED_COMMANDS)) {
            array.put(
                JSONObject()
                    .put("id", command.id)
                    .put("label", command.label)
                    .put("confirm", command.confirm)
            )
        }
        runCatching { commandsFile(deviceId).writeText(array.toString()) }
    }

    fun pinnedCommands(deviceId: String): List<PinnedCommand> {
        val text = runCatching { commandsFile(deviceId).readText() }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val out = ArrayList<PinnedCommand>(minOf(array.length(), MAX_PINNED_COMMANDS))
        for (i in 0 until minOf(array.length(), MAX_PINNED_COMMANDS)) {
            val row = array.optJSONObject(i) ?: continue
            val id = row.optString("id").takeIf { it.isNotEmpty() } ?: continue
            out.add(PinnedCommand(id, row.optString("label", id), row.optBoolean("confirm", false)))
        }
        return out
    }

    /** Forget one device's files — on unpair, or when a capability is
     *  switched off for it. A widget must not keep showing a machine the
     *  user has cut off, and must not offer buttons for one it can no
     *  longer reach. Other devices' files are untouched. */
    fun clear(deviceId: String) {
        runCatching { file(deviceId).delete() }
        runCatching { guardFile(deviceId).delete() }
        runCatching { commandsFile(deviceId).delete() }
    }

    /**
     * Which device's dashboard reading is freshest — the fallback for a
     * widget placed before per-widget configuration existed, so it keeps
     * showing something instead of going blank after the upgrade.
     */
    fun mostRecentDeviceId(): String? {
        val files = dir.listFiles { f -> f.name.startsWith("widget-snapshot-") && f.name.endsWith(".json") }
            ?: return null
        return files.mapNotNull { f ->
            val json = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return@mapNotNull null
            val id = json.optString("deviceId").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            id to json.optLong("savedAt", 0L)
        }.maxByOrNull { it.second }?.first
    }

    /** File-system-safe stand-in for a device id, which may contain
     *  anything from a UUID to a manually typed "host:port". Collisions are
     *  cosmetically possible but harmless — worst case, two devices briefly
     *  share a stale reading. */
    private fun sanitize(deviceId: String): String = deviceId.replace(Regex("[^A-Za-z0-9_-]"), "_")

    companion object {
        /** What fits legibly in the largest widget size (the default 4x2 —
         *  see DashboardWidget). Smaller sizes just draw however many of
         *  these they have room for. */
        const val MAX_METERS = 4

        /** A detail is a short label like "45 °C" or "17.5 / 31.3 GiB"; longer
         *  than this it is not a detail and will not fit a widget anyway. */
        private const val MAX_DETAIL_CHARS = 24

        /** Cells the Commands widget has — see widget_commands.xml. */
        const val MAX_PINNED_COMMANDS = 4
    }
}

package com.mazeconnect.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mazeconnect.app.data.DeviceRow
import com.mazeconnect.app.ui.components.Hairline
import com.mazeconnect.app.ui.components.MazeLabel
import com.mazeconnect.app.ui.components.MazeMeter
import com.mazeconnect.app.ui.components.TriStateGlyph
import com.mazeconnect.app.ui.theme.LocalMazeColors
import com.mazeconnect.app.ui.theme.MazeColors
import com.mazeconnect.core.SystemStatusState
import com.mazeconnect.core.protocol.Capability
import com.mazeconnect.core.protocol.SystemStatus
import kotlinx.coroutines.delay

/** How often the dashboard asks, while it is the screen being looked at. */
private const val REFRESH_MS = 3_000L

/** Wire name of the capability this screen depends on. */
private val DASHBOARD_CAPABILITY = Capability.SYSTEM_STATUS.wire

/**
 * The computer's own dashboard, read from here.
 *
 * Everything on this screen came over the network from a machine that is
 * authenticated rather than trustworthy, and it has already been bounded and
 * type-checked by [SystemStatus] before arriving. Nothing here re-derives a
 * value or fills in a plausible default: a field the computer did not send is
 * a field this screen does not show.
 */
@Composable
fun DashboardScreen(
    devices: List<DeviceRow>,
    selectedDeviceId: String?,
    state: SystemStatusState?,
    onRefresh: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMazeColors.current

    // The computer to read: whichever one the switcher has selected. Only a
    // connected device with the capability switched on can answer, so
    // anything else would be asking into a void.
    val target = devices.firstOrNull { it.deviceId == selectedDeviceId }
        ?.takeIf { it.paired && it.connected && DASHBOARD_CAPABILITY in it.capabilities }

    // Polling is tied to this screen being composed: a dashboard nobody is
    // looking at has no reason to keep waking the computer's status helper.
    LaunchedEffect(target?.deviceId) {
        val id = target?.deviceId ?: return@LaunchedEffect
        while (true) {
            onRefresh(id)
            delay(REFRESH_MS)
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MazeLabel("Dashboard")
            Spacer(Modifier.width(12.dp))
            Hairline(Modifier.weight(1f).height(1.dp))
            Spacer(Modifier.width(12.dp))
            MazeLabel(target?.name ?: "no computer")
        }

        // Filtered to the computer actually on screen. The flow holds one
        // answer at a time, and after switching machines it is still the old
        // one for a moment — showing that under the new machine's name would
        // attribute one computer's readings to another.
        val current = state?.takeIf { it.deviceId == target?.deviceId }
        val snapshot = current?.status

        when {
            target == null -> Unavailable(
                title = "Nothing to read",
                body = whyThereIsNoTarget(devices),
            )

            snapshot == null -> Unavailable(
                title = "No reading yet",
                // The reason matters. An empty dashboard and a machine with
                // nothing running on it look identical, and they are not the
                // same thing.
                body = current?.error?.let { "The computer could not be read: $it" }
                    ?: "Asking ${target.name}…",
            )

            else -> Snapshot(snapshot, current.error)
        }
    }
}

@Composable
private fun Snapshot(status: SystemStatus, error: String?) {
    val colors = LocalMazeColors.current

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = status.hostname.ifEmpty { "this computer" },
                        style = MaterialTheme.typography.titleLarge,
                        color = MazeColors.Paper,
                    )
                    Text(
                        // A failed refresh keeps the previous snapshot on
                        // screen rather than blanking it, so say that it is
                        // the previous one.
                        text = error?.let { "Last good reading — $it" } ?: "Live",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.dim,
                    )
                }
                status.hardeningScore?.let { score ->
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "$score%",
                            style = MaterialTheme.typography.titleLarge,
                            color = MazeColors.Paper,
                        )
                        MazeLabel("hardening")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        if (status.metrics.isNotEmpty()) {
            item { Section("Load") }
            items(status.metrics.size) { i ->
                val metric = status.metrics[i]
                MazeMeter(metric.label, metric.percent, metric.detail)
                Spacer(Modifier.height(14.dp))
            }
        }

        if (status.security.isNotEmpty()) {
            item { Section("Security services") }
            items(status.security.size) { i ->
                val service = status.security[i]
                StateRow(service.label, service.state.text(), service.state)
            }
            item { Spacer(Modifier.height(10.dp)) }
        }

        if (status.network.isNotEmpty()) {
            item { Section("Network") }
            items(status.network.size) { i ->
                val row = status.network[i]
                StateRow(row.label, row.value, row.state)
            }
            item { Spacer(Modifier.height(10.dp)) }
        }

        if (status.facts.isNotEmpty()) {
            item { Section("Machine") }
            items(status.facts.size) { i ->
                Text(
                    text = status.facts[i],
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.dim,
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        }

        if (status.unavailable.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                // Named rather than silently omitted: a missing panel must
                // never be read as a reading of zero.
                Text(
                    text = "Could not read: ${status.unavailable.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.dim,
                )
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun Section(title: String) {
    Column {
        MazeLabel(title)
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun StateRow(label: String, value: String, state: SystemStatus.State) {
    val colors = LocalMazeColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateGlyph(state.asBoolean())
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MazeColors.Paper,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = colors.dim,
        )
    }
}

@Composable
private fun Unavailable(title: String, body: String) {
    val colors = LocalMazeColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.dim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

/** Say which of the three things is missing, rather than just "nothing". */
private fun whyThereIsNoTarget(devices: List<DeviceRow>): String {
    val paired = devices.filter { it.paired }
    return when {
        paired.isEmpty() ->
            "Pair with a computer first. The dashboard reads the machine at " +
                "the other end of the link."
        paired.none { it.connected } ->
            "No paired computer is reachable right now."
        else ->
            "Turn on Dashboard for the computer under Devices. Reading a " +
                "machine stays off until you allow it, even though it only reads."
    }
}

private fun SystemStatus.State.asBoolean(): Boolean? = when (this) {
    SystemStatus.State.ACTIVE -> true
    SystemStatus.State.INACTIVE -> false
    SystemStatus.State.UNKNOWN -> null
}

private fun SystemStatus.State.text(): String = when (this) {
    SystemStatus.State.ACTIVE -> "Active"
    SystemStatus.State.INACTIVE -> "Inactive"
    SystemStatus.State.UNKNOWN -> "Unknown"
}

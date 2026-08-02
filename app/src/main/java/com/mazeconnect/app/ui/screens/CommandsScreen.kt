package com.mazeconnect.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mazeconnect.app.data.DeviceRow
import com.mazeconnect.app.ui.components.Hairline
import com.mazeconnect.app.ui.components.MazeButton
import com.mazeconnect.app.ui.components.MazeLabel
import com.mazeconnect.app.ui.theme.LocalMazeColors
import com.mazeconnect.app.ui.theme.MazeColors
import com.mazeconnect.core.CommandsState
import com.mazeconnect.core.RemoteCommand
import com.mazeconnect.core.protocol.Capability

private val COMMANDS_CAPABILITY = Capability.COMMANDS.wire

/**
 * The computer's own command list, run from here.
 *
 * The phone holds labels and ids. It has no command line to send and no way
 * to compose one: pressing a row sends an id back, and the computer decides
 * what — if anything — that id means. An entry marked `confirm` is asked
 * about once more here before the id goes anywhere, because the person who
 * wrote the list said it was worth a second thought.
 */
@Composable
fun CommandsScreen(
    devices: List<DeviceRow>,
    selectedDeviceId: String?,
    state: CommandsState?,
    onRefresh: (String) -> Unit,
    onRun: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMazeColors.current

    val target = devices.firstOrNull { it.deviceId == selectedDeviceId }
        ?.takeIf { it.paired && it.connected && COMMANDS_CAPABILITY in it.capabilities }

    // Asked for once per computer, not on a timer: a command list changes when
    // somebody edits a file, which is not something worth polling for.
    LaunchedEffect(target?.deviceId) {
        target?.deviceId?.let(onRefresh)
    }

    var pendingConfirm by remember { mutableStateOf<RemoteCommand?>(null) }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MazeLabel("Commands")
            Spacer(Modifier.width(12.dp))
            Hairline(Modifier.weight(1f).height(1.dp))
            Spacer(Modifier.width(12.dp))
            MazeLabel(target?.name ?: "no computer", maxLines = 1)
        }

        val current = state?.takeIf { it.deviceId == target?.deviceId }

        when {
            target == null -> Empty(
                title = "Nothing to run",
                body = whyNoCommandTarget(devices),
            )

            current == null || current.commands.isEmpty() -> Empty(
                // The computer's own reason when it gave one — "switched off
                // for your device" is a different situation from "none
                // defined", and only it can tell them apart.
                title = if (current?.lastError != null) "Commands unavailable" else "No commands",
                body = current?.lastError
                    ?: "Commands are defined on the computer, in a file only its owner " +
                    "can read. Add some there and they appear here. This phone sends " +
                    "the id of an entry — never a command.",
            )

            else -> CommandList(
                state = current,
                onRun = { command ->
                    if (command.confirm) {
                        pendingConfirm = command
                    } else {
                        onRun(target.deviceId, command.id)
                    }
                },
            )
        }
    }

    pendingConfirm?.let { command ->
        ConfirmRun(
            command = command,
            onConfirm = {
                target?.let { onRun(it.deviceId, command.id) }
                pendingConfirm = null
            },
            onDismiss = { pendingConfirm = null },
        )
    }
}

@Composable
private fun CommandList(state: CommandsState, onRun: (RemoteCommand) -> Unit) {
    val colors = LocalMazeColors.current

    LazyColumn(Modifier.fillMaxSize()) {
        items(state.commands.size) { index ->
            val command = state.commands[index]
            val running = command.id in state.running

            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = command.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MazeColors.Paper,
                    )
                    Text(
                        text = when {
                            running -> "Running…"
                            command.confirm && command.pinned -> "Asks before running · On your widget"
                            command.confirm -> "Asks before running"
                            command.pinned -> "On your widget"
                            else -> command.id
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.dim,
                    )
                }
                MazeButton(
                    text = if (running) "…" else "Run",
                    onClick = { onRun(command) },
                    enabled = !running,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
        }

        // The last result, kept at the bottom of the list so it scrolls with
        // it rather than covering the commands.
        if (state.lastId != null || state.lastError != null) {
            item {
                Spacer(Modifier.height(20.dp))
                LastResult(state)
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun LastResult(state: CommandsState) {
    val colors = LocalMazeColors.current

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MazeLabel(state.lastId ?: "result")
            Spacer(Modifier.width(10.dp))
            Text(
                // "Refused" and "ran and failed" are different answers, and
                // a phone that showed both as an error would be lying about
                // one of them.
                text = state.lastError
                    ?: state.lastExitCode?.let { if (it == 0) "exit 0" else "exit $it" }
                    ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = if (state.lastError != null || (state.lastExitCode ?: 0) != 0) {
                    MazeColors.Paper
                } else {
                    colors.dim
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        if (!state.lastOutput.isNullOrEmpty()) {
            Text(
                text = state.lastOutput!!,
                style = MaterialTheme.typography.labelSmall,
                color = colors.dim,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun ConfirmRun(command: RemoteCommand, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalMazeColors.current

    Box(
        Modifier
            .fillMaxSize()
            .background(MazeColors.Void.copy(alpha = 0.82f))
            .clickable(indication = null, interactionSource = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(32.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp)
                // Swallow taps so a press inside the card does not dismiss it.
                .clickable(indication = null, interactionSource = null) {},
        ) {
            MazeLabel("Run on the computer?")
            Spacer(Modifier.height(12.dp))
            Text(
                text = command.label,
                style = MaterialTheme.typography.titleLarge,
                color = MazeColors.Paper,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                // Honest about the limit of what this screen knows: the label
                // is the computer's, and only the computer knows the argv.
                text = "Marked as needing confirmation by whoever wrote the list on " +
                    "that computer. What it actually runs is defined there.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.dim,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MazeButton("Cancel", onDismiss, primary = false, modifier = Modifier.weight(1f))
                MazeButton("Run", onConfirm, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Empty(title: String, body: String) {
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
private fun whyNoCommandTarget(devices: List<DeviceRow>): String {
    val paired = devices.filter { it.paired }
    return when {
        paired.isEmpty() -> "Pair with a computer first."
        paired.none { it.connected } -> "No paired computer is reachable right now."
        else -> "Turn on Commands for the computer under Devices. Running anything " +
            "stays off until you allow it."
    }
}

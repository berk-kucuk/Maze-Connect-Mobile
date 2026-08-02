package com.mazeconnect.app.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import com.mazeconnect.app.R
import com.mazeconnect.app.service.MazeConnectService
import com.mazeconnect.app.ui.components.MazeButton
import com.mazeconnect.app.ui.components.MazeLabel
import com.mazeconnect.app.ui.theme.LocalMazeColors
import com.mazeconnect.app.ui.theme.MazeColors
import com.mazeconnect.app.ui.theme.MazeConnectTheme
import com.mazeconnect.core.protocol.Capability
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the dialog is showing. Every end state is one the user can read. */
private sealed interface CommandStage {
    data object Asking : CommandStage
    data object Working : CommandStage
    data class Done(val exitCode: Int?, val output: String?, val error: String?) : CommandStage
    data class Failed(val message: String) : CommandStage
}

/**
 * The confirmation (and report) a Commands-widget tap goes through.
 *
 * Mirrors [GuardConfirmActivity] closely, for the same two reasons: a
 * widget press has no other way to say whether it worked, and pressing a
 * cell may be the thing that starts the app. The one difference is that
 * asking is conditional here — a command not marked "confirm" is already
 * one the user chose to put on their home screen, and the tap itself is
 * the confirmation, exactly as it is from the in-app Commands screen.
 */
class CommandRunActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val commandId = intent.getStringExtra(CommandsWidget.EXTRA_COMMAND_ID)
        val label = intent.getStringExtra(CommandsWidget.EXTRA_COMMAND_LABEL)
        val confirm = intent.getBooleanExtra(CommandsWidget.EXTRA_COMMAND_CONFIRM, false)
        val targetDeviceId = intent.getStringExtra(CommandsWidget.EXTRA_TARGET_DEVICE_ID)
        if (commandId == null || label == null) {
            finish()
            return
        }

        setContent {
            MazeConnectTheme {
                var stage: CommandStage by remember {
                    mutableStateOf(if (confirm) CommandStage.Asking else CommandStage.Working)
                }

                // A non-confirm command runs the instant this screen appears —
                // the widget tap already was the confirmation.
                LaunchedEffect(Unit) {
                    if (!confirm) stage = run(commandId, targetDeviceId)
                }

                CommandConfirm(
                    label = label,
                    stage = stage,
                    onConfirm = {
                        stage = CommandStage.Working
                        lifecycleScope.launch { stage = run(commandId, targetDeviceId) }
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }

    private suspend fun run(commandId: String, targetDeviceId: String?): CommandStage {
        // Starting an already-running service is a no-op — safe unconditionally,
        // and the case that matters: a tap may arrive with the app not running.
        MazeConnectService.start(this)

        val manager = withTimeoutOrNull(LINK_WAIT_MS) {
            while (MazeConnectService.manager() == null) delay(POLL_MS)
            MazeConnectService.manager()
        } ?: return CommandStage.Failed(getString(R.string.guard_widget_no_service))

        // Wait for the configured computer specifically — with two connected
        // at once, waiting on "any" would sometimes run this on the wrong
        // one. A widget placed before per-widget configuration existed has
        // no target id, and falls back to whichever answers first, same as
        // before there was more than one to choose from.
        val ready = withTimeoutOrNull(LINK_WAIT_MS) {
            while (
                if (targetDeviceId != null) {
                    targetDeviceId !in manager.connectedIds.value ||
                        !manager.allows(targetDeviceId, Capability.COMMANDS)
                } else {
                    manager.connectedIds.value.none { manager.allows(it, Capability.COMMANDS) }
                }
            ) {
                delay(POLL_MS)
            }
            true
        } ?: false
        if (!ready) {
            return CommandStage.Failed(getString(R.string.guard_widget_unreachable))
        }
        val targetId = targetDeviceId
            ?: manager.connectedIds.value.first { manager.allows(it, Capability.COMMANDS) }

        // Refresh the catalog rather than trust what was pinned when the
        // widget was last drawn: the entry may have been renamed or removed
        // on the computer since.
        manager.requestCommands(targetId)
        val known = withTimeoutOrNull(CATALOG_WAIT_MS) {
            while (true) {
                val state = manager.commands.value[targetId]
                if (state != null && state.commands.any { it.id == commandId }) break
                delay(POLL_MS)
            }
            true
        } ?: false
        if (!known) {
            return CommandStage.Failed(getString(R.string.commands_widget_gone))
        }

        if (!manager.runCommand(targetId, commandId)) {
            return CommandStage.Failed(getString(R.string.guard_widget_refused))
        }

        // Wait for it to finish so the result can actually be shown — the
        // in-app Commands screen affords the same wait, just with more room
        // to show it in.
        withTimeoutOrNull(RUN_WAIT_MS) {
            while (commandId in (manager.commands.value[targetId]?.running ?: emptySet())) {
                delay(POLL_MS)
            }
        }

        val state = manager.commands.value[targetId]
        return if (state?.lastId == commandId) {
            CommandStage.Done(state.lastExitCode, state.lastOutput, state.lastError)
        } else {
            // Either it is still running past our own wait, or a different
            // command's result landed last — say so rather than claim a
            // result this screen does not actually have.
            CommandStage.Failed(getString(R.string.commands_widget_still_running))
        }
    }

    companion object {
        /** Long enough for a dial and a TLS handshake on a slow network,
         *  short enough that a dialog waiting on nothing still gives up. */
        private const val LINK_WAIT_MS = 12_000L
        private const val CATALOG_WAIT_MS = 6_000L

        /** Matches CommandRunner::kTimeoutMs on the computer (30s) plus room
         *  for the round trip. */
        private const val RUN_WAIT_MS = 35_000L
        private const val POLL_MS = 150L
    }
}

@Composable
private fun CommandConfirm(label: String, stage: CommandStage, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalMazeColors.current
    val failed = stage as? CommandStage.Failed
    val done = stage as? CommandStage.Done
    val working = stage is CommandStage.Working

    Dialog(onDismissRequest = { if (!working) onDismiss() }) {
        Column(
            Modifier
                .background(MazeColors.Void)
                .border(1.dp, colors.hairline, RectangleShape)
                .padding(24.dp),
        ) {
            MazeLabel(
                when {
                    failed != null -> "Not run"
                    done != null -> "Done"
                    working -> "Running…"
                    else -> "Run on your computer?"
                },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = MazeColors.Paper,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    failed != null -> failed.message
                    done != null -> done.error
                        ?: (if (done.exitCode == 0) "Exit 0" else "Exit ${done.exitCode}")
                    working -> "Reaching your computer…"
                    else -> "Marked as needing confirmation on the computer."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (done != null && done.error == null && done.exitCode != 0) {
                    MazeColors.Paper
                } else {
                    colors.dim
                },
            )
            if (done != null && !done.output.isNullOrEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = done.output,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.dim,
                    modifier = Modifier
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (stage is CommandStage.Asking) {
                    MazeButton(
                        "Cancel",
                        onDismiss,
                        primary = false,
                        modifier = Modifier.weight(1f),
                    )
                    MazeButton("Run", onConfirm, modifier = Modifier.weight(1f))
                } else {
                    // Closing here does not stop anything already sent — the
                    // computer keeps running it regardless, the same as a
                    // file transfer keeps going after its screen closes.
                    MazeButton(
                        "Close",
                        onDismiss,
                        primary = !working,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

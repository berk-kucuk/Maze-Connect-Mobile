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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
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
private sealed interface Stage {
    data object Asking : Stage
    data object Working : Stage
    data class Failed(val message: String) : Stage
}

/**
 * The confirmation a home-screen killswitch tap goes through.
 *
 * Two reasons it exists rather than the widget acting on the press directly:
 *
 * **It is a privileged action.** The in-app Guard screen asks in both
 * directions; a button on the home screen is *easier* to hit by accident, not
 * harder, so it should not be the one place that skips the question.
 *
 * **It is the only way the tap can report anything.** A widget press has no
 * feedback channel — if the computer is unreachable the cell simply does not
 * change and the press looks ignored.
 *
 * That second point has a consequence which cost a release to find: pressing a
 * widget cell may be the thing that *starts this app*. Asking the manager for
 * a connected computer in that same instant will always fail, because dialling
 * and completing a TLS handshake takes longer than laying out a dialog. So
 * confirming does not send-and-hope; it starts the link if needed, **waits**
 * for a computer that actually allows guard control, and only then sends —
 * reporting on screen either way.
 */
class GuardConfirmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val device = intent.getStringExtra(ControlsWidget.EXTRA_DEVICE)
        if (device == null || device !in DEVICE_LABELS) {
            finish()
            return
        }
        val targetDeviceId = intent.getStringExtra(ControlsWidget.EXTRA_TARGET_DEVICE_ID)

        val store = WidgetSnapshotStore(this)
        // The state the user was looking at when they pressed decides the
        // direction: blocked → allow, anything else → block. Falls back to
        // whichever computer's reading is freshest for a widget placed
        // before per-widget configuration existed, same as the widget's own
        // render() does.
        val blocked = store.guardStates(targetDeviceId ?: store.mostRecentDeviceId() ?: "")[device] == "off"

        setContent {
            MazeConnectTheme {
                var stage: Stage by remember { mutableStateOf(Stage.Asking) }

                Confirm(
                    device = device,
                    blocked = blocked,
                    stage = stage,
                    onConfirm = {
                        stage = Stage.Working
                        lifecycleScope.launch {
                            val failure = apply(device, enabled = blocked, targetDeviceId = targetDeviceId)
                            if (failure == null) finish() else stage = Stage.Failed(failure)
                        }
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }

    /** Null on success, otherwise the sentence to put on screen. */
    private suspend fun apply(device: String, enabled: Boolean, targetDeviceId: String?): String? {
        // Starting an already-running service is a no-op, so this is safe to
        // do unconditionally — and it is the case that matters, since a tap
        // may arrive with the app not running at all.
        MazeConnectService.start(this)

        val manager = withTimeoutOrNull(LINK_WAIT_MS) {
            while (MazeConnectService.manager() == null) delay(POLL_MS)
            MazeConnectService.manager()
        } ?: return getString(com.mazeconnect.app.R.string.guard_widget_no_service)

        if (targetDeviceId != null) {
            // Configured for a specific computer: wait for *that one*, not
            // whichever happens to answer first — with two computers
            // connected, "any" would sometimes toggle the wrong one.
            val ready = withTimeoutOrNull(LINK_WAIT_MS) {
                while (targetDeviceId !in manager.connectedIds.value ||
                    !manager.allows(targetDeviceId, Capability.GUARD_CONTROL)
                ) {
                    delay(POLL_MS)
                }
                true
            } ?: false
            if (!ready) {
                return getString(com.mazeconnect.app.R.string.guard_widget_unreachable)
            }
            if (!manager.setGuardKill(targetDeviceId, device, enabled)) {
                return getString(com.mazeconnect.app.R.string.guard_widget_refused)
            }
            manager.requestGuardStatus(targetDeviceId)
            return null
        }

        // Unconfigured (a widget placed before per-widget device pickers
        // existed): fall back to whichever paired computer answers first,
        // same as before there was more than one to choose from.
        val ready = withTimeoutOrNull(LINK_WAIT_MS) {
            while (manager.connectedIds.value.none { manager.allows(it, Capability.GUARD_CONTROL) }) {
                delay(POLL_MS)
            }
            true
        } ?: false

        if (!ready) {
            return getString(com.mazeconnect.app.R.string.guard_widget_unreachable)
        }
        if (!manager.toggleGuardOnAnyComputer(device, enabled)) {
            return getString(com.mazeconnect.app.R.string.guard_widget_refused)
        }
        return null
    }

    companion object {
        val DEVICE_LABELS = mapOf(
            "camera" to "Camera",
            "microphone" to "Microphone",
            "wifi" to "Wi-Fi",
            "bluetooth" to "Bluetooth",
        )

        /** Long enough for a dial and a TLS handshake on a slow network,
         *  short enough that a dialog waiting on nothing still gives up. */
        private const val LINK_WAIT_MS = 12_000L
        private const val POLL_MS = 150L
    }
}

@Composable
private fun Confirm(
    device: String,
    blocked: Boolean,
    stage: Stage,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalMazeColors.current
    val label = GuardConfirmActivity.DEVICE_LABELS[device] ?: device
    val failed = stage as? Stage.Failed
    val working = stage is Stage.Working

    Dialog(onDismissRequest = { if (!working) onDismiss() }) {
        Column(
            Modifier
                .background(MazeColors.Void)
                .border(1.dp, colors.hairline, RectangleShape)
                .padding(24.dp),
        ) {
            MazeLabel(
                when {
                    failed != null -> "Not done"
                    blocked -> "Allow again?"
                    else -> "Block?"
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
                    working -> "Reaching your computer…"
                    blocked -> "This turns a protection off on your computer. It will be " +
                        "recorded there and announced on its screen."
                    else -> "This blocks the $label on your computer. It will be recorded " +
                        "there and announced on its screen."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.dim,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (failed != null) {
                    MazeButton("Close", onDismiss, modifier = Modifier.weight(1f))
                } else {
                    MazeButton(
                        "Cancel",
                        onDismiss,
                        primary = false,
                        enabled = !working,
                        modifier = Modifier.weight(1f),
                    )
                    MazeButton(
                        if (blocked) "Allow" else "Block",
                        onConfirm,
                        enabled = !working,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

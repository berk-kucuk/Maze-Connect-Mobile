package com.mazeconnect.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.mazeconnect.app.data.DeviceRow
import com.mazeconnect.app.ui.components.Hairline
import com.mazeconnect.app.ui.components.MazeButton
import com.mazeconnect.app.R
import com.mazeconnect.app.ui.components.MazeLabel
import com.mazeconnect.app.ui.components.StatusGlyph
import com.mazeconnect.app.ui.theme.LocalMazeColors
import com.mazeconnect.app.ui.theme.MazeColors
import com.mazeconnect.core.GuardStateSnapshot
import com.mazeconnect.core.GuardSwitch
import com.mazeconnect.core.protocol.Capability

private val GUARD_CAPABILITY = Capability.GUARD_CONTROL.wire

private val DEVICE_LABELS = mapOf(
    "camera" to "Camera",
    "microphone" to "Microphone",
    "bluetooth" to "Bluetooth",
    "wifi" to "Wi-Fi",
    "usb" to "USB",
)

/**
 * maze-guard's killswitches, from the phone.
 *
 * This is the only screen in the app that changes the computer's security
 * posture rather than reading it, and two things about it are deliberate:
 *
 * There is no panic button, and no way to reach one. `PANIC` and `RESTORE`
 * are destructive; they belong at the terminal in front of the machine, and
 * the protocol has no way to express them at all.
 *
 * Blocking asks for confirmation, and so does allowing. Turning a protection
 * *off* from across the house is the more consequential direction of the two,
 * and it is the one an accidental tap would otherwise do silently.
 */
@Composable
fun GuardScreen(
    devices: List<DeviceRow>,
    selectedDeviceId: String?,
    state: GuardStateSnapshot?,
    onRefresh: (String) -> Unit,
    onSet: (String, String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMazeColors.current

    val target = devices.firstOrNull { it.deviceId == selectedDeviceId }
        ?.takeIf { it.paired && it.connected && GUARD_CAPABILITY in it.capabilities }
    val current = state?.takeIf { it.deviceId == target?.deviceId }

    LaunchedEffect(target?.deviceId) {
        target?.deviceId?.let(onRefresh)
    }

    var confirming by remember { mutableStateOf<GuardSwitch?>(null) }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MazeLabel("Killswitches")
            Spacer(Modifier.width(12.dp))
            Hairline(Modifier.weight(1f).height(1.dp))
            Spacer(Modifier.width(12.dp))
            MazeLabel(target?.name ?: "no computer", maxLines = 1)
        }

        when {
            target == null -> Empty("Nothing to control", whyNoGuardTarget(devices))

            current == null || current.switches.isEmpty() -> Empty(
                title = "Killswitches unavailable",
                body = current?.error
                    ?: "Asking the computer for the state of each switch…",
            )

            else -> SwitchList(
                state = current,
                onPress = { confirming = it },
            )
        }
    }

    confirming?.let { entry ->
        ConfirmToggle(
            entry = entry,
            onConfirm = {
                // The flag is the *device's* wanted state, not the switch's.
                // Blocked now → the button says "Allow" → ask for enabled.
                //
                // This was `!entry.blocked`, the same expression read through
                // the opposite vocabulary, and it made Block unblock: the
                // computer ran `rfkill unblock` and honestly reported "now
                // on" while the user watched wifi come back.
                target?.let { onSet(it.deviceId, entry.device, entry.blocked) }
                confirming = null
            },
            onDismiss = { confirming = null },
        )
    }
}

@Composable
private fun SwitchList(state: GuardStateSnapshot, onPress: (GuardSwitch) -> Unit) {
    val colors = LocalMazeColors.current

    LazyColumn(Modifier.fillMaxSize()) {
        items(state.switches.size) { index ->
            val entry = state.switches[index]
            val busy = state.pending == entry.device

            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Filled means blocked — the *protected* state. That is the
                // opposite of every other glyph in this app, which is why the
                // word beside it always says which.
                StatusGlyph(active = entry.blocked)
                Spacer(Modifier.width(12.dp))

                // The device's own mark, so a row is identifiable before the
                // word is read. Matches the desktop client's set exactly.
                deviceIcon(entry.device)?.let { icon ->
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = when {
                            !entry.present -> colors.dim
                            entry.blocked -> MazeColors.Paper
                            else -> colors.dim
                        },
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                }

                Column(Modifier.weight(1f)) {
                    Text(
                        text = DEVICE_LABELS[entry.device] ?: entry.device,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (entry.present) MazeColors.Paper else colors.dim,
                    )
                    Text(
                        text = when {
                            busy -> "Changing…"
                            !entry.present -> "not present on that machine"
                            !entry.known -> "state unknown"
                            entry.blocked -> "blocked"
                            else -> "allowed"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.dim,
                    )
                }

                if (entry.present) {
                    MazeButton(
                        text = if (entry.blocked) "Allow" else "Block",
                        enabled = !busy && state.pending == null,
                        primary = !entry.blocked,
                        onClick = { onPress(entry) },
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
        }

        item {
            Spacer(Modifier.height(20.dp))
            Text(
                // Said plainly, so nobody goes looking for the button.
                text = "Panic and restore are not available from a phone. They are " +
                    "destructive and belong at the machine itself.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.dim,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ConfirmToggle(
    entry: GuardSwitch,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalMazeColors.current
    val label = DEVICE_LABELS[entry.device] ?: entry.device
    val turningOff = entry.blocked

    Box(
        Modifier
            .fillMaxSize()
            .background(MazeColors.Void.copy(alpha = 0.86f))
            .clickable(indication = null, interactionSource = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(32.dp)
                .border(1.dp, colors.hairline, RectangleShape)
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp)
                .clickable(indication = null, interactionSource = null) {},
        ) {
            MazeLabel(if (turningOff) "Allow again?" else "Block?")
            Spacer(Modifier.height(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = MazeColors.Paper,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (turningOff) {
                    "This turns a protection off on your computer, from here. " +
                        "It will be recorded there and announced on its screen."
                } else {
                    "This blocks the $label on your computer. It will be recorded " +
                        "there and announced on its screen."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.dim,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MazeButton("Cancel", onDismiss, primary = false, modifier = Modifier.weight(1f))
                MazeButton(
                    if (turningOff) "Allow" else "Block",
                    onConfirm,
                    modifier = Modifier.weight(1f),
                )
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

private fun whyNoGuardTarget(devices: List<DeviceRow>): String {
    val paired = devices.filter { it.paired }
    return when {
        paired.isEmpty() -> "Pair with a computer first."
        paired.none { it.connected } -> "No paired computer is reachable right now."
        else -> "Turn on Killswitches for the computer under Devices. This one is " +
            "off by default for good reason — it is the only capability that " +
            "changes that machine's protections."
    }
}

/** Null for a device with no mark of its own, which simply draws none. */
private fun deviceIcon(device: String): Int? = when (device) {
    "camera" -> R.drawable.ic_camera
    "microphone" -> R.drawable.ic_microphone
    "wifi" -> R.drawable.ic_wifi
    "bluetooth" -> R.drawable.ic_bluetooth
    else -> null
}

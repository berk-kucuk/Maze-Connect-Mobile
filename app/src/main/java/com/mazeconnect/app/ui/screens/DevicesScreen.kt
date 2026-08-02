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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.border
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mazeconnect.app.data.DeviceRow
import com.mazeconnect.app.ui.components.Hairline
import com.mazeconnect.app.ui.components.MazeButton
import com.mazeconnect.app.ui.components.MazeLabel
import com.mazeconnect.app.ui.components.StatusGlyph
import com.mazeconnect.app.ui.theme.LocalMazeColors
import com.mazeconnect.app.ui.theme.MazeColors

@Composable
fun DevicesScreen(
    devices: List<DeviceRow>,
    displayFingerprint: (String) -> String,
    onPair: (String) -> Unit,
    onUnpair: (String) -> Unit,
    onReconnect: (String) -> Unit,
    onPairByAddress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMazeColors.current

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MazeLabel("Devices")
            Spacer(Modifier.width(12.dp))
            Hairline(Modifier.weight(1f).height(1.dp))
            Spacer(Modifier.width(12.dp))
            MazeLabel(if (devices.size == 1) "1 device" else "${devices.size} devices")
        }

        if (devices.isEmpty()) {
            // An empty screen should say what to do next, not just report
            // that there is nothing here.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "No devices yet",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "Open Maze Connect on your computer and keep both " +
                            "devices on the same network. Devices appear here as " +
                            "they announce themselves.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.dim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                    AddByAddress(onPairByAddress)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(devices, key = { it.deviceId }) { device ->
                    DeviceListItem(device, displayFingerprint, onPair, onUnpair, onReconnect)
                }
                item {
                    Spacer(Modifier.height(20.dp))
                    AddByAddress(onPairByAddress)
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun DeviceListItem(
    device: DeviceRow,
    displayFingerprint: (String) -> String,
    onPair: (String) -> Unit,
    onUnpair: (String) -> Unit,
    onReconnect: (String) -> Unit,
) {
    val colors = LocalMazeColors.current

    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusGlyph(active = device.connected)
            Spacer(Modifier.width(10.dp))
            Text(
                text = device.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )

            if (device.paired) {
                // Only when stuck "not reachable": the heartbeat already
                // recovers a dead link on its own within seconds, this is
                // just a faster, visible way out for someone staring at it.
                if (!device.connected) {
                    MazeButton("Reconnect", { onReconnect(device.deviceId) }, primary = false)
                    Spacer(Modifier.width(7.dp))
                }
                MazeButton("Remove", { onUnpair(device.deviceId) }, primary = false)
            } else {
                MazeButton("Pair", { onPair(device.deviceId) })
            }
        }

        Spacer(Modifier.height(6.dp))

        // The fingerprint is the device's real identity, so it is shown
        // rather than hidden behind a details panel — it is what the user
        // would compare if they ever needed to.
        Text(
            text = if (device.paired) {
                val state = if (device.connected) "Connected" else "Paired · not reachable"
                "$state · ${displayFingerprint(device.fingerprint)}"
            } else {
                "Not paired · ${device.address}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = colors.dim,
        )

        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
    }
}

/*
 * There used to be a row of per-capability switches here.
 *
 * It is gone. Pairing already requires a person to compare a six-digit code
 * on two screens and agree on both — that is the decision, and it is made
 * once. A second row of switches after it did not add a choice; it meant a
 * freshly paired phone showed "asking the computer…" on four screens forever,
 * with nothing anywhere explaining that four toggles were the reason.
 *
 * Revoking is still possible, on the *computer's* Devices page. That is the
 * right place for it: the computer is the machine being controlled, and it is
 * where the person who owns it is sitting.
 */

/**
 * Manual pairing entry, for networks that filter multicast discovery.
 *
 * Typing an address is not a shortcut around verification: it only decides
 * who we dial, and the code comparison still has to pass.
 */
@Composable
private fun AddByAddress(onPairByAddress: (String) -> Unit) {
    val colors = LocalMazeColors.current
    var address by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth()) {
        MazeLabel("Not showing up?")
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Enter the address shown on the computer.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.dim,
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = address,
                onValueChange = { address = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.labelMedium.copy(
                    color = MaterialTheme.colorScheme.onBackground,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onPairByAddress(address) }),
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, colors.hairline, RectangleShape)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                decorationBox = { inner ->
                    if (address.isEmpty()) {
                        Text(
                            text = "192.168.1.20:41234",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.dim,
                        )
                    }
                    inner()
                },
            )
            Spacer(Modifier.width(10.dp))
            MazeButton("Pair", { onPairByAddress(address) })
        }
    }
}

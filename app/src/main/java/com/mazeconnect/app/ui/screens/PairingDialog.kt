package com.mazeconnect.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mazeconnect.app.ui.components.MazeButton
import com.mazeconnect.app.ui.components.MazeLabel
import com.mazeconnect.app.ui.components.VerificationCode
import com.mazeconnect.app.ui.theme.LocalMazeColors
import com.mazeconnect.app.ui.theme.MazeColors

/**
 * The pairing confirmation.
 *
 * Not dismissible by tapping outside or by the back gesture: this is the one
 * decision in the app that grants lasting trust, and it should not be
 * something the user swipes past while doing something else.
 */
@Composable
fun PairingDialog(
    deviceName: String,
    weInitiated: Boolean,
    verificationCode: String,
    /** True once this side has answered and the other has not. The buttons
     *  gave no sign of having been pressed, so the control read as dead while
     *  the other device took its time. */
    answered: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val colors = LocalMazeColors.current

    Dialog(
        onDismissRequest = { /* answered explicitly, never dismissed */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            Modifier
                .padding(20.dp)
                .background(MaterialTheme.colorScheme.surface, RectangleShape)
                .border(1.dp, colors.hairline, RectangleShape)
                .padding(24.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MazeLabel(
                    if (weInitiated) "Pairing with $deviceName"
                    else "$deviceName wants to pair",
                    modifier = Modifier.align(Alignment.Start),
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    // State the check as an instruction, and say what it is for.
                    text = "Check that this code matches the one on the other device.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(Modifier.height(24.dp))

                VerificationCode(verificationCode)

                Spacer(Modifier.height(24.dp))

                Text(
                    text = "If the codes are different, someone else is on the " +
                        "connection. Cancel and try again on a network you trust.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.dim,
                )

                Spacer(Modifier.height(24.dp))

                if (answered) {
                    // The wait now belongs to the other device; say so instead
                    // of leaving two live buttons that do nothing further.
                    Text(
                        text = "Waiting for the other device to confirm…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MazeColors.Paper,
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    ) {
                        MazeButton("Codes differ", onReject, primary = false)
                        MazeButton("Codes match", onAccept)
                    }
                }
            }
        }
    }
}

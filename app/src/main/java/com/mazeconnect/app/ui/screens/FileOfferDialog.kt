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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.mazeconnect.app.ui.components.MazeButton
import com.mazeconnect.app.ui.components.MazeLabel
import com.mazeconnect.app.ui.theme.LocalMazeColors
import com.mazeconnect.app.ui.theme.MazeColors
import com.mazeconnect.core.PendingFileOffer

/**
 * The prompt for an incoming file.
 *
 * An incoming file is never accepted automatically, on either end. The
 * filename shown here is the sender's, unmodified — it is what they claim,
 * and the user should see exactly that. What actually gets written is a
 * sanitised single-component name inside the inbox, which is why showing the
 * raw one here is safe as well as honest.
 *
 * Deliberately not dismissible by tapping outside: an accidental tap should
 * not silently decline a file someone is waiting to send.
 */
@Composable
fun FileOfferDialog(
    offer: PendingFileOffer,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val colors = LocalMazeColors.current

    Box(
        Modifier.fillMaxSize().background(MazeColors.Void.copy(alpha = 0.88f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(32.dp)
                .border(1.dp, colors.hairline, RectangleShape)
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp)
                // Swallows taps so the backdrop cannot dismiss this.
                .clickable(indication = null, interactionSource = null) {},
        ) {
            MazeLabel("Incoming file")
            Spacer(Modifier.height(12.dp))
            // A picture shows itself before it is accepted: the preview the
            // computer sent with the offer. Nothing is written until Accept.
            offer.thumbnail?.let { jpeg ->
                com.mazeconnect.app.ui.components.PreviewImage(
                    jpeg = jpeg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .border(1.dp, colors.hairline, RectangleShape),
                )
                Spacer(Modifier.height(14.dp))
            }
            Text(
                text = offer.filename,
                style = MaterialTheme.typography.titleLarge,
                color = MazeColors.Paper,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${humanSize(offer.sizeBytes)} · from ${offer.deviceName}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.dim,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MazeButton("Decline", onDecline, primary = false, modifier = Modifier.weight(1f))
                MazeButton("Accept", onAccept, modifier = Modifier.weight(1f))
            }
        }
    }
}

internal fun humanSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f kB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

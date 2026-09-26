package com.mazeconnect.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mazeconnect.app.R
import com.mazeconnect.app.ui.components.MazeLabel
import com.mazeconnect.app.ui.theme.LocalMazeColors
import com.mazeconnect.app.ui.theme.MazeColors

/** The pages reached from More, by the name kept in saved state. */
enum class MorePage(val title: String) {
    DEVICES("Devices"),
    GUARD("Guard"),
    AI("Maze AI"),
    SETTINGS("Settings"),
}

/**
 * Everything that is not an everyday action, one tap away.
 *
 * The bottom bar used to carry all eight pages and scroll sideways, which
 * hid half of them off the edge of the screen and made the bar itself the
 * thing to learn. Five fixed destinations — the ones used daily — stay on the
 * bar; pairing, the killswitches, the AI chat and settings live here, each
 * with a line saying what it is for.
 */
@Composable
fun MoreScreen(
    pairedCount: Int,
    connectedCount: Int,
    installedVersion: String,
    onOpen: (MorePage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMazeColors.current

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        MazeLabel("More")
        Spacer(Modifier.height(12.dp))

        MoreRow(MorePage.DEVICES, R.drawable.ic_devices, when {
            pairedCount == 0 -> "Pair this phone with a computer"
            connectedCount == 0 -> "$pairedCount paired · none reachable right now"
            else -> "$pairedCount paired · $connectedCount linked"
        }, onOpen)
        MoreRow(MorePage.GUARD, R.drawable.ic_guard,
            "Camera, microphone, Wi-Fi and Bluetooth killswitches", onOpen)
        MoreRow(MorePage.AI, R.drawable.ic_ai, "Chat with the model on your computer", onOpen)
        MoreRow(MorePage.SETTINGS, R.drawable.ic_settings,
            "This phone's sharing, Now bar, updates, fingerprint", onOpen)

        Spacer(Modifier.height(28.dp))
        Text(
            text = "Maze Connect $installedVersion",
            style = MaterialTheme.typography.labelSmall,
            color = colors.dim,
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun MoreRow(page: MorePage, icon: Int, subtitle: String, onOpen: (MorePage) -> Unit) {
    val colors = LocalMazeColors.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onOpen(page) }
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MazeColors.Paper,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = page.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MazeColors.Paper,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.dim,
                )
            }
            Text(text = "›", style = MaterialTheme.typography.titleLarge, color = colors.dim)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
    }
}

/** The bar at the top of a page opened from More: back, and where you are. */
@Composable
fun SubpageHeader(page: MorePage, onBack: () -> Unit) {
    val colors = LocalMazeColors.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onBack)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "‹", style = MaterialTheme.typography.titleLarge, color = MazeColors.Paper)
            Spacer(Modifier.width(10.dp))
            MazeLabel("More", color = colors.dim)
            Spacer(Modifier.width(8.dp))
            MazeLabel("/", color = colors.dim)
            Spacer(Modifier.width(8.dp))
            MazeLabel(page.title, color = MazeColors.Paper)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
    }
}

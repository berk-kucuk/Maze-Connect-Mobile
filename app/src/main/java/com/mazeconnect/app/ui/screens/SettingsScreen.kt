package com.mazeconnect.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
import com.mazeconnect.app.service.LiveStatusNotification
import com.mazeconnect.app.ui.components.MazeButton
import com.mazeconnect.app.ui.components.MazeLabel
import com.mazeconnect.app.update.UpdateStatus
import kotlinx.coroutines.delay
import com.mazeconnect.app.ui.theme.LocalMazeColors
import com.mazeconnect.app.ui.theme.MazeColors

@Composable
fun SettingsScreen(
    fingerprint: String,
    displayFingerprint: (String) -> String,
    installedVersion: String,
    liveStatusSupported: Boolean,
    liveStatusEnabled: Boolean,
    onSetLiveStatusEnabled: (Boolean) -> Unit,
    liveStatusPromotion: () -> LiveStatusNotification.PromotionState,
    update: UpdateStatus,
    updateCheckEnabled: Boolean,
    onSetUpdateCheckEnabled: (Boolean) -> Unit,
    onCheckForUpdate: () -> Unit,
    modifier: Modifier = Modifier,
    updateCheckAvailable: Boolean = true,
    shareStatus: Boolean = true,
    onSetShareStatus: (Boolean) -> Unit = {},
    allowRing: Boolean = true,
    onSetAllowRing: (Boolean) -> Unit = {},
    onTestRing: () -> Unit = {},
    clipboardSync: Boolean = false,
    onSetClipboardSync: (Boolean) -> Unit = {},
) {
    val colors = LocalMazeColors.current
    val context = LocalContext.current

    // Scrolls: with the phone's own switches added, the page is taller than
    // a small screen, and a settings page whose last section cannot be
    // reached is a settings page with a missing setting.
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(20.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        MazeLabel("This device")
        Spacer(Modifier.height(14.dp))

        Row {
            MazeLabel("Fingerprint", Modifier.width(110.dp))
            Text(
                text = displayFingerprint(fingerprint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
        Spacer(Modifier.height(24.dp))

        MazeLabel("Features")
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Everything this app can do is available to a computer you have " +
                "paired with. Pairing is the decision — you compared a six-digit code " +
                "on both screens. To take something back, use the Devices page on the " +
                "computer itself.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.dim,
        )

        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
        Spacer(Modifier.height(24.dp))

        // What paired computers may do *to this phone*. The phone's owner
        // decides these here, the same way the computer's owner decides what
        // the computer offers on its own Devices page.
        MazeLabel("This phone")
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Share status with your computer",
            style = MaterialTheme.typography.titleMedium,
            color = MazeColors.Paper,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "The computer's dashboard shows this phone's battery, storage, memory, " +
                "network type and ringer mode. Never your location, Wi-Fi name, contacts, " +
                "messages or apps.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.dim,
        )
        Spacer(Modifier.height(12.dp))
        MazeButton(
            text = if (shareStatus) "On" else "Off",
            onClick = { onSetShareStatus(!shareStatus) },
            primary = false,
        )

        Spacer(Modifier.height(22.dp))
        Text(
            text = "Let your computer ring this phone",
            style = MaterialTheme.typography.titleMedium,
            color = MazeColors.Paper,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Find my phone: rings at full volume, even on silent, until you tap " +
                "Found it or two minutes pass. Your alarm volume is put back afterwards.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.dim,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MazeButton(
                text = if (allowRing) "On" else "Off",
                onClick = { onSetAllowRing(!allowRing) },
                primary = false,
            )
            Spacer(Modifier.width(10.dp))
            MazeButton(text = "Test ring", onClick = onTestRing, primary = false, enabled = allowRing)
        }

        Spacer(Modifier.height(22.dp))
        Text(
            text = "Clipboard sync",
            style = MaterialTheme.typography.titleMedium,
            color = MazeColors.Paper,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "What you copy on the computer lands here straight away. What you copy " +
                "here goes to the computer when you open Maze Connect — Android lets no app " +
                "read the clipboard in the background. Passwords marked sensitive are never " +
                "sent. The computer has its own switch.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.dim,
        )
        Spacer(Modifier.height(12.dp))
        MazeButton(
            text = if (clipboardSync) "On" else "Off",
            onClick = { onSetClipboardSync(!clipboardSync) },
            primary = false,
        )

        if (liveStatusSupported) {
            Spacer(Modifier.height(24.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
            Spacer(Modifier.height(24.dp))

            MazeLabel("Now bar")
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Keeps the connected computer's readings on the lock screen " +
                    "and in the status bar. Turning this off stops publishing it " +
                    "entirely; the link itself is unaffected.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.dim,
            )
            Spacer(Modifier.height(14.dp))

            // Re-read while this screen is on top, so coming back from the
            // system settings shows the new answer instead of the stale one
            // the user just went away to change.
            var promotion by remember {
                mutableStateOf(LiveStatusNotification.PromotionState.NOT_PUBLISHED)
            }
            LaunchedEffect(liveStatusEnabled) {
                while (true) {
                    promotion = liveStatusPromotion()
                    delay(PROMOTION_POLL_MS)
                }
            }

            Text(
                text = when (promotion) {
                    LiveStatusNotification.PromotionState.PROMOTED ->
                        "Showing on the lock screen and in the status bar."
                    LiveStatusNotification.PromotionState.DECLINED ->
                        "Published correctly, but this phone is not showing it. " +
                            "Samsung keeps live notifications from other apps behind a " +
                            "switch in Developer options."
                    LiveStatusNotification.PromotionState.NOT_PROMOTABLE ->
                        "Published, but this phone will not accept it. That is a bug " +
                            "in this app rather than a setting."
                    LiveStatusNotification.PromotionState.NOT_PUBLISHED ->
                        "Nothing published yet — connect a computer and let a reading " +
                            "arrive."
                    else -> ""
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.dim,
            )

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MazeButton(
                    text = if (liveStatusEnabled) "On" else "Off",
                    onClick = { onSetLiveStatusEnabled(!liveStatusEnabled) },
                    primary = false,
                )
                if (promotion == LiveStatusNotification.PromotionState.DECLINED) {
                    Spacer(Modifier.width(10.dp))
                    MazeButton(
                        text = "Developer options",
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    LiveStatusNotification.developerOptionsIntent()
                                )
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
        Spacer(Modifier.height(24.dp))

        MazeLabel("Version")
        Spacer(Modifier.height(14.dp))

        Row {
            MazeLabel("Installed", Modifier.width(110.dp))
            Text(
                text = installedVersion,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Spacer(Modifier.height(12.dp))

        if (!updateCheckAvailable) {
            // The F-Droid build: F-Droid is where updates come from, and this
            // build never contacts anything but your own computers.
            Text(
                text = "Updates are delivered by F-Droid.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.dim,
            )
            return@Column
        }

        val newer = update.newer
        Text(
            text = when {
                update.checking -> "Checking…"
                update.error != null -> update.error
                newer -> "Version ${update.versionName ?: update.versionCode} is available."
                update.lastCheckedMs > 0L -> "This is the newest build."
                !updateCheckEnabled -> "Update checks are off."
                else -> "Not checked yet."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (newer) MaterialTheme.colorScheme.onBackground else colors.dim,
        )

        if (newer && update.notes != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = update.notes,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.dim,
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            MazeButton(
                text = "Check now",
                onClick = onCheckForUpdate,
                primary = false,
                enabled = !update.checking,
            )
            if (newer && update.downloadUrl != null) {
                Spacer(Modifier.width(10.dp))
                // Opens the browser; the app never fetches or installs the
                // package itself. See UpdateChecker for why that line is
                // where it is.
                MazeButton(
                    text = "Download",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, update.downloadUrl.toUri())
                            )
                        }
                    },
                )
            }
            Spacer(Modifier.width(10.dp))
            MazeButton(
                text = if (updateCheckEnabled) "On" else "Off",
                onClick = { onSetUpdateCheckEnabled(!updateCheckEnabled) },
                primary = false,
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "Checked at most once a day, over https. Nothing about your " +
                "device is sent — only this app's version, so the server can " +
                "answer — and the app never downloads or installs anything on " +
                "its own.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.dim,
        )
    }
}

/** Cheap enough to re-read on a timer; only runs while Settings is shown. */
private const val PROMOTION_POLL_MS = 2_000L

package com.mazeconnect.app.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mazeconnect.app.R
import com.mazeconnect.app.data.DeviceRow
import com.mazeconnect.app.ui.components.Hairline
import com.mazeconnect.app.ui.components.MazeButton
import com.mazeconnect.app.ui.components.MazeLabel
import com.mazeconnect.app.ui.theme.LocalMazeColors
import com.mazeconnect.app.ui.theme.MazeColors
import com.mazeconnect.core.protocol.Capability
import com.mazeconnect.core.protocol.MediaAction
import com.mazeconnect.core.protocol.MediaState
import kotlinx.coroutines.delay

private val MEDIA_CAPABILITY = Capability.MEDIA.wire

/**
 * The computer's music and video, from the phone.
 *
 * Whatever is playing there — Spotify, a browser tab, VLC, anything that
 * shows up in the desktop's own media controls — with play/pause, skip,
 * seek, the player's volume and the computer's output volume.
 *
 * While this screen is open the computer pushes every change, so a track
 * that ends or a pause pressed at the keyboard shows up here without a
 * refresh. The position between pushes is moved forward locally; the
 * computer does not send a message a second to say time is passing.
 */
@Composable
fun MediaScreen(
    devices: List<DeviceRow>,
    selectedDeviceId: String?,
    state: MediaState?,
    onWatch: (String?) -> Unit,
    onCommand: (deviceId: String, playerId: String, action: MediaAction, value: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val target = devices.firstOrNull { it.deviceId == selectedDeviceId }
        ?.takeIf { it.paired && it.connected && MEDIA_CAPABILITY in it.capabilities }

    // Subscribed for as long as this screen shows this computer, and not a
    // moment longer: a push per track change is nothing, but it is still
    // nothing worth paying for with the screen closed.
    DisposableEffect(target?.deviceId) {
        onWatch(target?.deviceId)
        onDispose { onWatch(null) }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MazeLabel("Media")
            Spacer(Modifier.width(12.dp))
            Hairline(Modifier.weight(1f).height(1.dp))
            Spacer(Modifier.width(12.dp))
            MazeLabel(target?.name ?: "no computer", maxLines = 1)
        }

        when {
            target == null -> MediaEmpty("Nothing to control", whyNoMediaTarget(devices))
            state == null -> MediaEmpty("Media", "Asking the computer what is playing…")
            state.error != null && state.players.isEmpty() && state.systemVolume == null ->
                MediaEmpty("Media unavailable", state.error!!)
            else -> MediaControls(
                state = state,
                onCommand = { playerId, action, value ->
                    onCommand(target.deviceId, playerId, action, value)
                },
            )
        }
    }
}

@Composable
private fun MediaControls(
    state: MediaState,
    onCommand: (playerId: String, action: MediaAction, value: Long) -> Unit,
) {
    val colors = LocalMazeColors.current

    // Which player is on screen. Follows the computer's pick until the user
    // picks one; a player that quits falls back to the computer's pick again.
    var chosenId by remember { mutableStateOf<String?>(null) }
    val player = state.players.firstOrNull { it.id == chosenId } ?: state.active

    // Local clock for the position bar, ticking only while something plays.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(player?.status, state.receivedAtMs) {
        now = System.currentTimeMillis()
        while (player?.status == MediaState.Status.PLAYING) {
            delay(500)
            now = System.currentTimeMillis()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (state.players.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.players.forEach { p ->
                    MazeButton(
                        text = p.name,
                        onClick = { chosenId = p.id },
                        primary = p.id == player?.id,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        if (player == null) {
            Text(
                text = "Nothing is playing on this computer.",
                style = MaterialTheme.typography.titleMedium,
                color = MazeColors.Paper,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Start music or a video there — anything that appears in its " +
                    "media controls shows up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.dim,
            )
        } else {
            NowPlaying(player = player, nowMs = now, receivedAtMs = state.receivedAtMs, onCommand = onCommand)
        }

        state.systemVolume?.let { volume ->
            Spacer(Modifier.height(28.dp))
            Hairline(Modifier.fillMaxWidth().height(1.dp))
            Spacer(Modifier.height(16.dp))
            MazeLabel("Computer volume")
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    onCommand("", MediaAction.SYSTEM_MUTE, if (state.systemMuted) 0 else 1)
                }) {
                    Icon(
                        painter = painterResource(
                            if (state.systemMuted) R.drawable.ic_volume_off else R.drawable.ic_volume
                        ),
                        contentDescription = if (state.systemMuted) "Unmute" else "Mute",
                        tint = if (state.systemMuted) colors.dim else MazeColors.Paper,
                    )
                }
                VolumeSlider(
                    value = volume.coerceIn(0, 100),
                    enabled = true,
                    onSet = { onCommand("", MediaAction.SYSTEM_VOLUME, it.toLong()) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        state.notice?.let {
            Spacer(Modifier.height(16.dp))
            Text(text = it, style = MaterialTheme.typography.labelSmall, color = colors.dim)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun NowPlaying(
    player: MediaState.Player,
    nowMs: Long,
    receivedAtMs: Long,
    onCommand: (playerId: String, action: MediaAction, value: Long) -> Unit,
) {
    val colors = LocalMazeColors.current

    MazeLabel(
        text = "${player.name} · " + when (player.status) {
            MediaState.Status.PLAYING -> "playing"
            MediaState.Status.PAUSED -> "paused"
            MediaState.Status.STOPPED -> "stopped"
        },
        maxLines = 1,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        text = player.title.ifEmpty { "Untitled" },
        style = MaterialTheme.typography.headlineSmall,
        color = MazeColors.Paper,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    val subtitle = listOf(player.artist, player.album).filter { it.isNotEmpty() }.joinToString(" · ")
    if (subtitle.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.dim,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }

    // ---- position --------------------------------------------------------
    Spacer(Modifier.height(20.dp))
    val position = player.positionAt(nowMs, receivedAtMs)
    var dragging by remember(player.id) { mutableStateOf<Float?>(null) }
    if (player.lengthMs > 0) {
        Slider(
            value = dragging ?: (position.toFloat() / player.lengthMs).coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onCommand(player.id, MediaAction.SEEK, (it * player.lengthMs).toLong()) }
                dragging = null
            },
            enabled = player.canSeek,
            colors = mazeSliderColors(),
        )
        Row(Modifier.fillMaxWidth()) {
            val shown = dragging?.let { (it * player.lengthMs).toLong() } ?: position
            Text(formatTime(shown), style = MaterialTheme.typography.labelSmall, color = colors.dim)
            Spacer(Modifier.weight(1f))
            Text(formatTime(player.lengthMs), style = MaterialTheme.typography.labelSmall, color = colors.dim)
        }
    }

    // ---- transport ---------------------------------------------------------
    Spacer(Modifier.height(16.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(R.drawable.ic_media_previous, "Previous", player.canPrevious) {
            onCommand(player.id, MediaAction.PREVIOUS, 0)
        }
        val playing = player.status == MediaState.Status.PLAYING
        Box(
            Modifier.size(72.dp).border(1.dp, MazeColors.Paper, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            TransportButton(
                icon = if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play,
                label = if (playing) "Pause" else "Play",
                enabled = player.canPlay || player.canPause,
                large = true,
            ) {
                onCommand(player.id, MediaAction.PLAY_PAUSE, 0)
            }
        }
        TransportButton(R.drawable.ic_media_next, "Next", player.canNext) {
            onCommand(player.id, MediaAction.NEXT, 0)
        }
    }

    player.volume?.let { volume ->
        Spacer(Modifier.height(24.dp))
        MazeLabel("${player.name} volume", maxLines = 1)
        VolumeSlider(
            value = volume,
            enabled = true,
            onSet = { onCommand(player.id, MediaAction.SET_VOLUME, it.toLong()) },
        )
    }
}

@Composable
private fun TransportButton(
    icon: Int,
    label: String,
    enabled: Boolean,
    large: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalMazeColors.current
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(if (large) 64.dp else 56.dp)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = if (enabled) MazeColors.Paper else colors.hairline,
            modifier = Modifier.size(if (large) 34.dp else 28.dp),
        )
    }
}

/**
 * A 0..100 slider that sends once, when the finger lifts.
 *
 * Not on every move: the computer turns each volume command into a process
 * or a bus call, and it drops a device that sends more than a few a second.
 */
@Composable
private fun VolumeSlider(
    value: Int,
    enabled: Boolean,
    onSet: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = dragging ?: (value / 100f),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSet((it * 100).toInt().coerceIn(0, 100)) }
                dragging = null
            },
            enabled = enabled,
            colors = mazeSliderColors(),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "${((dragging ?: (value / 100f)) * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = LocalMazeColors.current.dim,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp),
        )
    }
}

@Composable
private fun mazeSliderColors() = SliderDefaults.colors(
    thumbColor = MazeColors.Paper,
    activeTrackColor = MazeColors.Paper,
    inactiveTrackColor = LocalMazeColors.current.hairline,
    disabledThumbColor = LocalMazeColors.current.dim,
    disabledActiveTrackColor = LocalMazeColors.current.dim,
)

@Composable
private fun MediaEmpty(title: String, body: String) {
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

private fun whyNoMediaTarget(devices: List<DeviceRow>): String {
    val paired = devices.filter { it.paired }
    return when {
        paired.isEmpty() -> "Pair with a computer first."
        paired.none { it.connected } -> "No paired computer is reachable right now."
        else -> "That computer's Maze Connect is too old for media control — update it to 1.2 or later."
    }
}

internal fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

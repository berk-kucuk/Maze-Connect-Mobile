package com.mazeconnect.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mazeconnect.app.data.DeviceRow
import com.mazeconnect.app.ui.components.Hairline
import com.mazeconnect.app.ui.components.MazeButton
import com.mazeconnect.app.ui.components.MazeLabel
import com.mazeconnect.app.ui.theme.LocalMazeColors
import com.mazeconnect.app.ui.theme.MazeColors
import com.mazeconnect.core.AiState
import com.mazeconnect.core.protocol.Capability

private val AI_CAPABILITY = Capability.AI.wire

/**
 * Maze AI, running on the computer rather than on the phone.
 *
 * The model is the user's own, on their own machine, over the link — nothing
 * typed here goes to a third party. It is chat, not an agent: Maze AI can
 * explain and write a command, and cannot run one. That is deliberate, and the
 * reason lives in the desktop's `AiBridge`.
 */
@Composable
fun AiChatScreen(
    devices: List<DeviceRow>,
    selectedDeviceId: String?,
    state: AiState?,
    onRefreshModels: (String) -> Unit,
    onSend: (String, String) -> Unit,
    onPickModel: (String, String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMazeColors.current

    val target = devices.firstOrNull { it.deviceId == selectedDeviceId }
        ?.takeIf { it.paired && it.connected && AI_CAPABILITY in it.capabilities }
    val current = state?.takeIf { it.deviceId == target?.deviceId }

    // Asked for when the screen opens, not on a timer: Ollama's model list
    // changes when somebody pulls a model, which is not worth polling for.
    LaunchedEffect(target?.deviceId) {
        target?.deviceId?.let(onRefreshModels)
    }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MazeLabel("Maze AI")
            Spacer(Modifier.width(12.dp))
            Hairline(Modifier.weight(1f).height(1.dp))
            Spacer(Modifier.width(12.dp))
            if (current?.turns?.isNotEmpty() == true) {
                Box(
                    Modifier.clickable(indication = null, interactionSource = null) { onClear() }
                ) {
                    MazeLabel("New chat")
                }
            } else {
                MazeLabel(target?.name ?: "no computer", maxLines = 1)
            }
        }

        when {
            target == null -> Empty("Nothing to ask", whyNoAiTarget(devices))

            current == null || current.models.isEmpty() -> Empty(
                title = "Maze AI is not available",
                body = current?.error
                    ?: "Asking the computer which models its Ollama has…",
            )

            else -> {
                ModelPicker(current) { onPickModel(target.deviceId, it) }
                Transcript(current, Modifier.weight(1f))
                Composer(
                    busy = current.streaming,
                    onSend = { onSend(target.deviceId, it) },
                )
            }
        }
    }
}

@Composable
private fun ModelPicker(state: AiState, onPick: (String) -> Unit) {
    val colors = LocalMazeColors.current

    // A scrolling row of names rather than a dropdown: there are usually two
    // or three, and one tap beats two.
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.models.forEach { name ->
            val active = name == state.model
            Box(
                Modifier
                    .border(1.dp, if (active) MazeColors.Paper else colors.hairline, RectangleShape)
                    .background(if (active) MazeColors.Paper else MazeColors.Void, RectangleShape)
                    .clickable(indication = null, interactionSource = null) { onPick(name) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) MazeColors.Void else colors.dim,
                )
            }
        }
    }
}

@Composable
private fun Transcript(state: AiState, modifier: Modifier = Modifier) {
    val colors = LocalMazeColors.current
    val listState = rememberLazyListState()

    // Follow the stream, so a long answer does not scroll off unseen.
    LaunchedEffect(state.turns.size, state.turns.lastOrNull()?.text?.length) {
        if (state.turns.isNotEmpty()) {
            listState.animateScrollToItem(state.turns.size - 1)
        }
    }

    if (state.turns.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Ask Maze AI",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Runs on your computer, through your own Ollama. It can " +
                        "explain and write commands — it cannot run them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.dim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.turns.size) { index ->
            val turn = state.turns[index]
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = if (turn.fromUser) Arrangement.End else Arrangement.Start,
            ) {
                Box(
                    Modifier
                        .widthIn(max = 300.dp)
                        .border(1.dp, colors.hairline, RectangleShape)
                        .padding(12.dp),
                ) {
                    Text(
                        // A waiting bubble says so rather than sitting blank,
                        // which would read as an answer of nothing.
                        text = turn.text.ifEmpty { "…" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            turn.failed -> colors.dim
                            turn.fromUser -> MazeColors.Paper
                            else -> MazeColors.Paper
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(busy: Boolean, onSend: (String) -> Unit) {
    val colors = LocalMazeColors.current
    var text by remember { mutableStateOf("") }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            enabled = !busy,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onBackground,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground),
            modifier = Modifier
                .weight(1f)
                .border(1.dp, colors.hairline, RectangleShape)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            decorationBox = { inner ->
                if (text.isEmpty()) {
                    Text(
                        text = if (busy) "Waiting for the model…" else "Ask something…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.dim,
                    )
                }
                inner()
            },
        )
        Spacer(Modifier.width(10.dp))
        MazeButton(
            text = "Send",
            enabled = !busy && text.isNotBlank(),
            onClick = {
                onSend(text)
                text = ""
            },
        )
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

private fun whyNoAiTarget(devices: List<DeviceRow>): String {
    val paired = devices.filter { it.paired }
    return when {
        paired.isEmpty() -> "Pair with a computer first. Maze AI runs there, not here."
        paired.none { it.connected } -> "No paired computer is reachable right now."
        else -> "Turn on Maze AI for the computer under Devices."
    }
}

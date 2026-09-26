package com.mazeconnect.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mazeconnect.app.data.DeviceRow
import com.mazeconnect.app.ui.components.Hairline
import com.mazeconnect.app.ui.components.MazeButton
import com.mazeconnect.app.ui.components.MazeLabel
import com.mazeconnect.app.ui.theme.LocalMazeColors
import com.mazeconnect.app.ui.theme.MazeColors
import com.mazeconnect.core.InputSessionState
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * Hardware keys routed to the Remote screen while presenting: volume up/down
 * as next/previous slide, so the phone can stay in a pocket-sized grip with
 * the screen off to the side. MainActivity consults it; null means the keys
 * do what they always do.
 */
object VolumeKeyRouter {
    @Volatile var handler: ((up: Boolean) -> Unit)? = null
}

private enum class RemoteMode(val title: String, val full: Boolean) {
    TOUCHPAD("Touchpad", true),
    KEYBOARD("Keyboard", true),
    PRESENTER("Presenter", false),
}

/**
 * Drive the computer: a touchpad, a keyboard, or a slide clicker.
 *
 * Touchpad and keyboard are full control, which the computer's owner has to
 * allow for this phone and confirm once on the computer itself; until then
 * this screen says so. Presenter mode is a handful of slide keys and needs
 * neither.
 */
@Composable
fun RemoteScreen(
    devices: List<DeviceRow>,
    selectedDeviceId: String?,
    state: InputSessionState?,
    onStart: (full: Boolean) -> Unit,
    onStop: () -> Unit,
    onEvent: (JSONObject) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMazeColors.current
    val target = devices.firstOrNull { it.deviceId == selectedDeviceId && it.connected }
    var mode by rememberSaveable { mutableStateOf(RemoteMode.TOUCHPAD) }

    val current = state?.takeIf { it.deviceId == target?.deviceId }
    val active = current?.active == true && (current.mode == "full") == mode.full

    // Keep the screen on while driving: a touchpad that times out mid-slide
    // deck is a touchpad that fails exactly when it is needed.
    val view = LocalView.current
    DisposableEffect(active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MazeLabel("Remote")
            Spacer(Modifier.width(12.dp))
            Hairline(Modifier.weight(1f).height(1.dp))
            Spacer(Modifier.width(12.dp))
            MazeLabel(target?.name ?: "no computer")
            if (active) {
                Spacer(Modifier.width(12.dp))
                MazeButton("Stop", onStop, primary = false)
            }
        }

        // The three modes.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteMode.entries.forEach { m ->
                MazeButton(
                    text = m.title,
                    onClick = {
                        if (m.full != mode.full && current?.active == true) onStop()
                        mode = m
                    },
                    primary = m == mode,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        when {
            target == null -> Centered(
                "No computer",
                "Link a computer first — the remote drives the one selected at the top.",
            )
            !active -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (mode.full) "Control ${target.name}" else "Present on ${target.name}",
                    style = MaterialTheme.typography.titleLarge,
                    color = MazeColors.Paper,
                )
                Spacer(Modifier.height(10.dp))
                // "Pending" from the computer comes with its reason; a bare
                // "starting" is just the second before any answer.
                val waiting = current?.starting == true && current.error != null
                val problem = current?.error?.takeIf { current.starting != true }
                if (waiting) {
                    Text(
                        text = "Approve it on ${target.name}: a prompt is open in Maze Connect " +
                            "there. It closes by itself after a minute.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MazeColors.Paper,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .border(1.dp, MazeColors.Paper, RectangleShape)
                            .padding(14.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                } else if (problem != null) {
                    // Said where it cannot be missed: the reason is the whole
                    // answer to "why is Start back?".
                    Text(
                        text = problem.replaceFirstChar { it.uppercase() } + ".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MazeColors.Paper,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .border(1.dp, MazeColors.Paper, RectangleShape)
                            .padding(14.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Text(
                    text = if (mode.full) {
                        "Moves the pointer and types on the computer. The first time, the " +
                            "computer asks its owner to allow this phone — once, or always."
                    } else {
                        "Next, previous, start and end your slides — nothing else. The volume " +
                            "keys work too."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.dim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Spacer(Modifier.height(20.dp))
                MazeButton(
                    text = when {
                        waiting -> "Waiting for approval…"
                        current?.starting == true -> "Starting…"
                        current?.error != null -> "Try again"
                        else -> "Start"
                    },
                    onClick = { onStart(mode.full) },
                    enabled = current?.starting != true,
                )
            }
            mode == RemoteMode.TOUCHPAD -> Touchpad(onEvent)
            mode == RemoteMode.KEYBOARD -> Keyboard(onEvent)
            else -> Presenter(onEvent)
        }
    }
}

@Composable
private fun Centered(title: String, body: String) {
    val colors = LocalMazeColors.current
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MazeColors.Paper)
        Spacer(Modifier.height(10.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.dim,
            textAlign = TextAlign.Center,
        )
    }
}

private fun key(name: String, mods: List<String> = emptyList()) = JSONObject()
    .put("kind", "key").put("key", name).put("action", "tap").put("mods", JSONArray(mods))

private fun button(name: String, action: String = "click") = JSONObject()
    .put("kind", "button").put("button", name).put("action", action)

// ---- Touchpad ------------------------------------------------------------------

@Composable
private fun Touchpad(onEvent: (JSONObject) -> Unit) {
    val colors = LocalMazeColors.current
    // Motion is summed here and sent at most every 16 ms: one message per
    // frame rather than per touch sample, well under the computer's limit.
    val pending = remember { floatArrayOf(0f, 0f) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(16)
            val dx: Float
            val dy: Float
            synchronized(pending) {
                dx = pending[0]
                dy = pending[1]
                pending[0] = 0f
                pending[1] = 0f
            }
            if (dx != 0f || dy != 0f) {
                onEvent(JSONObject().put("kind", "move").put("dx", dx.toDouble()).put("dy", dy.toDouble()))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MazeColors.Panel)
                .border(1.dp, colors.hairline, RectangleShape)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.uptimeMillis
                        var travelled = 0f
                        var fingers = 1
                        var scroll = 0f
                        var end = start
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            end = event.changes.maxOf { it.uptimeMillis }
                            if (pressed.isEmpty()) break
                            fingers = maxOf(fingers, pressed.size)
                            if (pressed.size >= 2) {
                                // Two fingers scroll, the natural way round:
                                // push the page up to go down.
                                val dy = pressed.map { it.position.y - it.previousPosition.y }.average().toFloat()
                                travelled += abs(dy)
                                scroll += dy
                                while (abs(scroll) >= SCROLL_STEP_PX) {
                                    val dir = sign(scroll)
                                    onEvent(JSONObject().put("kind", "scroll").put("dy", (-dir).toDouble()))
                                    scroll -= dir * SCROLL_STEP_PX
                                }
                            } else {
                                val c = pressed.first()
                                val dx = c.position.x - c.previousPosition.x
                                val dy = c.position.y - c.previousPosition.y
                                travelled += abs(dx) + abs(dy)
                                // A little acceleration: slow movements stay
                                // precise, fast flicks cross the screen.
                                val speed = abs(dx) + abs(dy)
                                val gain = 1.2f + min(speed / 18f, 2.3f)
                                synchronized(pending) {
                                    pending[0] += dx * gain
                                    pending[1] += dy * gain
                                }
                            }
                            event.changes.forEach { it.consume() }
                        }
                        // A tap is short and still: one finger clicks,
                        // two right-click.
                        if (travelled < TAP_SLOP_PX && end - start < TAP_MS) {
                            onEvent(button(if (fingers >= 2) "right" else "left"))
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Drag to move · tap to click · two-finger tap to right-click · " +
                    "two fingers to scroll",
                style = MaterialTheme.typography.labelSmall,
                color = colors.dim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MazeButton("Left", { onEvent(button("left")) }, primary = false, modifier = Modifier.weight(1f))
            MazeButton(
                text = if (dragging) "Drop" else "Drag",
                onClick = {
                    dragging = !dragging
                    onEvent(button("left", if (dragging) "press" else "release"))
                },
                primary = dragging,
                modifier = Modifier.weight(1f),
            )
            MazeButton("Right", { onEvent(button("right")) }, primary = false, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
    }
}

private const val SCROLL_STEP_PX = 36f
private const val TAP_SLOP_PX = 24f
private const val TAP_MS = 260L

// ---- Keyboard -------------------------------------------------------------------

@Composable
private fun Keyboard(onEvent: (JSONObject) -> Unit) {
    val colors = LocalMazeColors.current
    // Typed text is sent as it is typed: what was added goes as text, what
    // was deleted as Backspace. The field is a window onto the last few
    // words, cleared on Enter, not a document.
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var mods by remember { mutableStateOf(setOf<String>()) }

    fun sendKey(name: String) {
        onEvent(key(name, mods.toList()))
        mods = emptySet()
    }

    Column(Modifier.fillMaxSize()) {
        BasicTextField(
            value = field,
            onValueChange = { next ->
                val old = field.text
                val new = next.text
                when {
                    mods.isNotEmpty() && new.length == old.length + 1 && new.startsWith(old) -> {
                        // With a modifier held, a single letter is a shortcut
                        // (Ctrl+C), not text.
                        val ch = new.last().lowercaseChar()
                        if (ch in 'a'..'z' || ch in '0'..'9') sendKey(ch.toString())
                        field = TextFieldValue(old, TextRange(old.length))
                        return@BasicTextField
                    }
                    new.startsWith(old) && new.length > old.length ->
                        onEvent(JSONObject().put("kind", "text").put("text", new.substring(old.length)))
                    old.startsWith(new) && new.length < old.length ->
                        repeat(min(old.length - new.length, 64)) { onEvent(key("backspace")) }
                    new != old -> {
                        repeat(min(old.length, 64)) { onEvent(key("backspace")) }
                        if (new.isNotEmpty()) onEvent(JSONObject().put("kind", "text").put("text", new))
                    }
                }
                field = if (next.text.length > 200) TextFieldValue("") else next
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MazeColors.Paper),
            cursorBrush = SolidColor(MazeColors.Paper),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSend = {
                onEvent(key("enter"))
                field = TextFieldValue("")
            }),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, colors.hairline, RectangleShape)
                .padding(14.dp),
            decorationBox = { inner ->
                if (field.text.isEmpty()) {
                    Text("Type here — it appears on the computer", color = colors.dim,
                        style = MaterialTheme.typography.bodyLarge)
                }
                inner()
            },
        )
        Spacer(Modifier.height(14.dp))
        MazeLabel("Modifiers — apply to the next key")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ctrl" to "Ctrl", "alt" to "Alt", "shift" to "Shift", "super" to "Super").forEach { (id, label) ->
                MazeButton(
                    text = label,
                    onClick = { mods = if (id in mods) mods - id else mods + id },
                    primary = id in mods,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        MazeLabel("Keys")
        Spacer(Modifier.height(8.dp))
        KeyRow(listOf("escape" to "Esc", "tab" to "Tab", "backspace" to "⌫", "enter" to "Enter"), ::sendKey)
        Spacer(Modifier.height(8.dp))
        KeyRow(listOf("left" to "←", "up" to "↑", "down" to "↓", "right" to "→"), ::sendKey)
        Spacer(Modifier.height(8.dp))
        KeyRow(listOf("home" to "Home", "end" to "End", "pageUp" to "PgUp", "pageDown" to "PgDn"), ::sendKey)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "super" to "Super", "delete" to "Del", "volumeDown" to "Vol −", "volumeUp" to "Vol +",
                "mute" to "Mute", "playPause" to "Play", "f5" to "F5", "f11" to "F11",
            ).forEach { (id, label) -> MazeButton(label, { sendKey(id) }, primary = false) }
        }
    }
}

@Composable
private fun KeyRow(keys: List<Pair<String, String>>, onKey: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { (id, label) ->
            MazeButton(label, { onKey(id) }, primary = false, modifier = Modifier.weight(1f))
        }
    }
}

// ---- Presenter --------------------------------------------------------------------

@Composable
private fun Presenter(onEvent: (JSONObject) -> Unit) {
    val colors = LocalMazeColors.current
    DisposableEffect(Unit) {
        VolumeKeyRouter.handler = { up -> onEvent(key(if (up) "left" else "right")) }
        onDispose { VolumeKeyRouter.handler = null }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MazeColors.Paper)
                .clickable { onEvent(key("right")) },
            contentAlignment = Alignment.Center,
        ) {
            Text("NEXT  →", style = MaterialTheme.typography.headlineMedium, color = MazeColors.Void)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .border(1.dp, colors.hairline, RectangleShape)
                .clickable { onEvent(key("left")) },
            contentAlignment = Alignment.Center,
        ) {
            Text("←  PREVIOUS", style = MaterialTheme.typography.titleLarge, color = MazeColors.Paper)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MazeButton("Start", { onEvent(key("f5")) }, primary = false, modifier = Modifier.weight(1f))
            MazeButton("Black", { onEvent(key("blank")) }, primary = false, modifier = Modifier.weight(1f))
            MazeButton("End", { onEvent(key("escape")) }, primary = false, modifier = Modifier.weight(1f))
        }
        Text(
            "Volume down: next · volume up: previous",
            style = MaterialTheme.typography.labelSmall,
            color = colors.dim,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp),
        )
    }
}

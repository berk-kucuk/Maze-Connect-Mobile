package com.mazeconnect.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mazeconnect.app.ui.theme.LocalMazeColors
import com.mazeconnect.app.ui.theme.MazeColors
import kotlin.math.roundToInt

/**
 * Tracked uppercase mono label — the recurring structural marker from the
 * Maze identity. Used for section headings and field names, never for text
 * the user reads at length.
 */
@Composable
fun MazeLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalMazeColors.current.dim,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = maxLines,
        modifier = modifier,
    )
}

/**
 * The inverted-block primitive: a filled paper block that hollows out to an
 * outline when pressed. With no accent colour available, inversion is what
 * carries emphasis. Touch has no hover state, so the press state does the
 * work the desktop build gives to hover.
 */
@Composable
fun MazeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val colors = LocalMazeColors.current

    val fill by animateColorAsState(
        targetValue = when {
            !primary -> Color.Transparent
            pressed -> Color.Transparent
            else -> MazeColors.Paper
        },
        animationSpec = tween(120),
        label = "fill",
    )
    val content by animateColorAsState(
        targetValue = if (primary && !pressed) MazeColors.Void else MazeColors.Paper,
        animationSpec = tween(120),
        label = "content",
    )
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.35f,
        animationSpec = tween(120),
        label = "alpha",
    )

    Box(
        modifier = modifier
            .background(fill.copy(alpha = fill.alpha * alpha), RectangleShape)
            .border(
                BorderStroke(
                    1.dp,
                    if (primary) MazeColors.Paper.copy(alpha = alpha)
                    else colors.hairline,
                ),
                RectangleShape,
            )
            .clickable(
                interactionSource = interaction,
                // No ripple: a coloured ripple would be the only hue in an
                // otherwise strictly monochrome interface.
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = content.copy(alpha = alpha),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Connection state as a filled or hollow square.
 *
 * The Maze palette has no accent colour, so the usual green/grey status dot
 * is unavailable — and would fail colour-blind users anyway. Fill carries
 * the state instead, and it is always paired with a text label, never alone.
 */
@Composable
fun StatusGlyph(active: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalMazeColors.current
    val fill by animateColorAsState(
        targetValue = if (active) MazeColors.Paper else Color.Transparent,
        animationSpec = tween(150),
        label = "glyph",
    )
    Box(
        modifier = modifier
            .size(7.dp)
            .background(fill, RectangleShape)
            .border(1.dp, if (active) MazeColors.Paper else colors.dim, RectangleShape),
    )
}

/**
 * A tri-state glyph, for readings that can also be "unknown".
 *
 * [StatusGlyph] is a boolean: connected or not. A probe on the computer has a
 * third answer — a service that is not installed has not failed — and
 * collapsing that into "off" would report a machine as unprotected on the
 * strength of a question it never answered. Filled means yes, hollow means no,
 * and a single dash means the machine did not say.
 */
@Composable
fun TriStateGlyph(state: Boolean?, modifier: Modifier = Modifier) {
    val colors = LocalMazeColors.current
    if (state == null) {
        Box(
            modifier = modifier.size(7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 7.dp, height = 1.dp).background(colors.dim))
        }
        return
    }
    StatusGlyph(active = state, modifier = modifier)
}

/**
 * A labelled bar, for CPU / memory / disk.
 *
 * The palette has no accent colour, so a bar cannot go red as it fills. The
 * number beside it is what carries the reading; the bar is there to make a set
 * of them comparable at a glance, not to raise an alarm on its own.
 */
@Composable
fun MazeMeter(
    label: String,
    percent: Float,
    detail: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMazeColors.current
    val clamped = percent.coerceIn(0f, 100f)
    val filled by animateFloatAsState(
        targetValue = clamped / 100f,
        animationSpec = tween(300),
        label = "meter",
    )

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MazeColors.Paper,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (detail.isEmpty()) "${clamped.roundToInt()}%"
                       else "${clamped.roundToInt()}%  ·  $detail",
                style = MaterialTheme.typography.labelSmall,
                color = colors.dim,
            )
        }
        Spacer(Modifier.size(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(colors.hairline, RectangleShape),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(filled)
                    .height(3.dp)
                    .background(MazeColors.Paper, RectangleShape),
            )
        }
    }
}

/** Hairline rule at the brand's 12% opacity. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(LocalMazeColors.current.hairline)
    )
}

/**
 * The six-digit pairing code, set as a readout rather than as text.
 *
 * This is the one moment where security depends entirely on a person: if the
 * digits here differ from those on the other screen, someone is in the
 * middle. So it is the loudest element in the app — each digit in its own
 * hairline cell, at display size, in mono, so comparing two screens is a
 * glance rather than a squint.
 */
@Composable
fun VerificationCode(code: String, modifier: Modifier = Modifier) {
    val colors = LocalMazeColors.current
    // Cells share the available width rather than sizing to their content:
    // fixed-width cells overflowed the dialog on a 1080px screen and clipped
    // the last digit, which on this particular element means asking someone
    // to compare a code they cannot fully see.
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        code.forEach { digit ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(0.68f)
                    .background(MaterialTheme.colorScheme.surface, RectangleShape)
                    .border(1.dp, colors.hairline, RectangleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = digit.toString(),
                    style = MaterialTheme.typography.displayLarge,
                    color = MazeColors.Paper,
                    maxLines = 1,
                )
            }
        }
    }
}

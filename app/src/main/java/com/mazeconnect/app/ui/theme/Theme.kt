package com.mazeconnect.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/** Tokens Material 3's ColorScheme has no slot for. */
data class MazeExtendedColors(
    val hairline: androidx.compose.ui.graphics.Color,
    val hairlineStrong: androidx.compose.ui.graphics.Color,
    val dim: androidx.compose.ui.graphics.Color,
)

val LocalMazeColors = staticCompositionLocalOf {
    MazeExtendedColors(
        hairline = MazeColors.Hairline,
        hairlineStrong = MazeColors.HairlineStrong,
        dim = MazeColors.Dim,
    )
}

/**
 * Dark-only theme — no light variant, matching the Maze identity's
 * `color-scheme: dark`.
 *
 * There is no OLED toggle. Maze Linux is an OLED-first distribution and the
 * whole suite is true black already, so the switch offered a choice between
 * the shipped look and a slightly lighter version of it — the desktop client
 * dropped its own for the same reason. One surface, no setting.
 */
@Composable
fun MazeConnectTheme(
    content: @Composable () -> Unit,
) {
    val surface = MazeColors.Panel

    val colorScheme = darkColorScheme(
        background = MazeColors.Void,
        onBackground = MazeColors.Paper,
        surface = surface,
        onSurface = MazeColors.Paper,
        surfaceVariant = surface,
        onSurfaceVariant = MazeColors.Dim,
        outline = MazeColors.Hairline,
        outlineVariant = MazeColors.Hairline,
        // No accent colour in this identity: emphasis is carried by
        // inversion (paper block on void) rather than by hue.
        primary = MazeColors.Paper,
        onPrimary = MazeColors.Void,
        secondary = MazeColors.Paper,
        onSecondary = MazeColors.Void,
        error = MazeColors.Paper,
        onError = MazeColors.Void,
    )

    CompositionLocalProvider(
        LocalMazeColors provides MazeExtendedColors(
            hairline = MazeColors.Hairline,
            hairlineStrong = MazeColors.HairlineStrong,
            dim = MazeColors.Dim,
        )
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MazeTypography,
            content = content,
        )
    }
}

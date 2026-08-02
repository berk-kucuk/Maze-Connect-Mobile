package com.mazeconnect.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type roles for the Maze identity.
 *
 * The brand faces (Bricolage Grotesque / IBM Plex Sans / IBM Plex Mono) are
 * all on Google Fonts and are meant to load through Compose's
 * downloadable-fonts API. That needs a provider certificate array, which is
 * security-adjacent trust data — it is left for a deliberate, verified pass
 * rather than guessed at here, since a wrong array fails silently back to
 * the default face. Until then the roles are correct and only the faces are
 * substituted, so switching them over is a one-file change.
 *
 * The mono role carries the wide tracking (0.22em) that the Maze identity
 * uses for labels, and is what the pairing code is set in.
 */
val MazeTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Light,
        // Sized so six cells fit a phone dialog without clipping.
        fontSize = 34.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    // The tracked uppercase label — the recurring structural marker.
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        letterSpacing = 2.4.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        letterSpacing = 1.4.sp,
    ),
)

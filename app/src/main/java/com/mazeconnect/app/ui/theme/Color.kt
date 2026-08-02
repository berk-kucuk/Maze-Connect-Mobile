package com.mazeconnect.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Maze Linux brand tokens — see MazeLinuxWeb/tailwind.config.ts.
 *
 * Deliberately monochrome: this identity has no accent colour, so state is
 * carried by fill, weight and glyph rather than by hue. Mirrors the desktop
 * client's Theme.qml; keep both in lock-step.
 */
object MazeColors {
    val Void = Color(0xFF000000)
    val Paper = Color(0xFFF2F1EC)
    val Dim = Color(0xFF7A7A7F)
    val Panel = Color(0xFF080808)

    val Hairline = Paper.copy(alpha = 0.12f)
    val HairlineStrong = Paper.copy(alpha = 0.45f)
}

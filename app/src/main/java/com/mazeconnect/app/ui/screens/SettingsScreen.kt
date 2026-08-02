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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.mazeconnect.app.ui.components.MazeButton
import com.mazeconnect.app.ui.components.MazeLabel
import com.mazeconnect.app.ui.theme.LocalMazeColors
import com.mazeconnect.app.ui.theme.MazeColors

@Composable
fun SettingsScreen(
    fingerprint: String,
    displayFingerprint: (String) -> String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMazeColors.current

    Column(modifier = modifier.fillMaxSize().padding(20.dp)) {
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
    }
}


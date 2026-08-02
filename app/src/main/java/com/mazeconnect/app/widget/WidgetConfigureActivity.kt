package com.mazeconnect.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mazeconnect.app.service.MazeConnectService
import com.mazeconnect.app.ui.components.MazeLabel
import com.mazeconnect.app.ui.theme.LocalMazeColors
import com.mazeconnect.app.ui.theme.MazeColors
import com.mazeconnect.app.ui.theme.MazeConnectTheme
import com.mazeconnect.core.pairing.PairedDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shown once, when a widget is placed: pick which paired computer this
 * particular widget instance draws.
 *
 * One base class for all three widget kinds — Dashboard (shared with its
 * Compact/Mini sizes), Commands, Controls — since the flow is identical:
 * list paired computers, save the pick against this `appWidgetId`, redraw,
 * finish. Without this, two widgets of the same kind would necessarily
 * fight over one computer, the way a single shared snapshot file used to
 * before there was more than one computer to pick from.
 */
abstract class WidgetConfigureActivity : ComponentActivity() {

    /** Redraw just this one freshly-configured instance. */
    protected abstract fun refreshOne(context: Context, appWidgetId: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A widget host that never gets RESULT_OK treats the placement as
        // cancelled and removes the widget — the default has to be set
        // before any early return, or backing out leaves a broken instance.
        setResult(RESULT_CANCELED)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // Needed to read the paired device list, whether or not the app was
        // already running — placing a widget is as likely to be the first
        // thing a user does as opening the app is.
        MazeConnectService.start(this)

        setContent {
            MazeConnectTheme {
                var devices by remember { mutableStateOf<List<PairedDevice>?>(null) }

                LaunchedEffect(Unit) {
                    val manager = withTimeoutOrNull(LINK_WAIT_MS) {
                        while (MazeConnectService.manager() == null) delay(POLL_MS)
                        MazeConnectService.manager()
                    }
                    devices = manager?.pairedDevices?.value ?: emptyList()
                }

                PickDevice(
                    devices = devices,
                    onPick = { deviceId ->
                        WidgetDeviceConfig.save(this@WidgetConfigureActivity, appWidgetId, deviceId)
                        refreshOne(this@WidgetConfigureActivity, appWidgetId)
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                        )
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        /** Long enough for a dial and a TLS handshake on a slow network,
         *  short enough that a picker waiting on nothing still gives up. */
        private const val LINK_WAIT_MS = 8_000L
        private const val POLL_MS = 150L
    }
}

class DashboardWidgetConfigureActivity : WidgetConfigureActivity() {
    override fun refreshOne(context: Context, appWidgetId: Int) = DashboardWidget.refreshOne(context, appWidgetId)
}

class CommandsWidgetConfigureActivity : WidgetConfigureActivity() {
    override fun refreshOne(context: Context, appWidgetId: Int) = CommandsWidget.refreshOne(context, appWidgetId)
}

class ControlsWidgetConfigureActivity : WidgetConfigureActivity() {
    override fun refreshOne(context: Context, appWidgetId: Int) = ControlsWidget.refreshOne(context, appWidgetId)
}

@Composable
private fun PickDevice(
    devices: List<PairedDevice>?,
    onPick: (String) -> Unit,
) {
    val colors = LocalMazeColors.current

    Column(
        Modifier
            .fillMaxSize()
            .background(MazeColors.Void)
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        MazeLabel("Pick a computer")
        Spacer(Modifier.height(16.dp))

        when {
            devices == null -> Text(
                "Looking for paired computers…",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.dim,
            )
            devices.isEmpty() -> Text(
                "No paired computer yet. Open Maze Connect and pair one first, " +
                    "then place this widget again.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.dim,
            )
            else -> LazyColumn {
                items(devices) { device ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(device.deviceId) }
                            .padding(vertical = 16.dp),
                    ) {
                        Text(
                            device.deviceName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MazeColors.Paper,
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
                }
            }
        }
    }
}

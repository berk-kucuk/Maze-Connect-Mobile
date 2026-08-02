package com.mazeconnect.app.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import com.mazeconnect.app.R
import com.mazeconnect.app.service.MazeConnectService
import com.mazeconnect.app.ui.components.MazeButton
import com.mazeconnect.app.ui.components.MazeLabel
import com.mazeconnect.app.ui.theme.LocalMazeColors
import com.mazeconnect.app.ui.theme.MazeColors
import com.mazeconnect.app.ui.theme.MazeConnectTheme
import com.mazeconnect.core.protocol.Capability
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the dialog is showing. Every end state is one the user can read. */
private sealed interface Stage {
    data object Asking : Stage
    data object Working : Stage
    data class Done(val sent: Int, val failed: Int) : Stage
    data class Failed(val message: String) : Stage
}

/**
 * The Android share-sheet target: "Share" a file from any app straight to
 * a paired computer, no need to open Maze Connect first.
 *
 * Mirrors [com.mazeconnect.app.widget.GuardConfirmActivity]'s shape — start
 * the link if needed, wait for a computer that actually allows file
 * transfer, then act and report — for the same reason: a share-sheet tap
 * has no other way to say whether it worked, and this may be the very thing
 * that starts the app.
 *
 * Sending itself is fire-and-forget from here. Once
 * [com.mazeconnect.core.DeviceManager.sendFile] returns true the transfer is
 * owned by the process-scoped link, same as every other transfer; closing
 * this activity does not stop it.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris = extractUris(intent)
        if (uris.isEmpty()) {
            finish()
            return
        }

        setContent {
            MazeConnectTheme {
                var stage: Stage by remember { mutableStateOf(Stage.Asking) }

                Confirm(
                    count = uris.size,
                    stage = stage,
                    onConfirm = {
                        stage = Stage.Working
                        lifecycleScope.launch { stage = send(uris) }
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }

    private suspend fun send(uris: List<Uri>): Stage {
        // Starting an already-running service is a no-op — safe unconditionally,
        // and the case that matters here: a share may arrive with the app not
        // running at all.
        MazeConnectService.start(this)

        val manager = withTimeoutOrNull(LINK_WAIT_MS) {
            while (MazeConnectService.manager() == null) delay(POLL_MS)
            MazeConnectService.manager()
        } ?: return Stage.Failed(getString(R.string.guard_widget_no_service))

        // Wait for a computer that is both connected *and* allowed to take
        // files. Waiting on connectedIds alone would send to a link that is
        // going to refuse.
        val ready = withTimeoutOrNull(LINK_WAIT_MS) {
            while (manager.connectedIds.value.none { manager.allows(it, Capability.FILE_TRANSFER) }) {
                delay(POLL_MS)
            }
            true
        } ?: false

        if (!ready) {
            return Stage.Failed(getString(R.string.guard_widget_unreachable))
        }

        val targetId = manager.connectedIds.value.first {
            manager.allows(it, Capability.FILE_TRANSFER)
        }
        var sent = 0
        var failed = 0
        for (uri in uris) {
            if (manager.sendFile(targetId, uri)) sent++ else failed++
        }
        return Stage.Done(sent, failed)
    }

    companion object {
        private const val LINK_WAIT_MS = 12_000L
        private const val POLL_MS = 150L

        /** Both the single- and multi-file share actions carry their content
         *  under the same historical extra name. */
        private fun extractUris(intent: Intent): List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let { listOf(it) }
                    ?: emptyList()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            }
            else -> emptyList()
        }
    }
}

@Composable
private fun Confirm(count: Int, stage: Stage, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalMazeColors.current
    val failed = stage as? Stage.Failed
    val done = stage as? Stage.Done
    val working = stage is Stage.Working

    Dialog(onDismissRequest = { if (!working) onDismiss() }) {
        Column(
            Modifier
                .background(MazeColors.Void)
                .border(1.dp, colors.hairline, RectangleShape)
                .padding(24.dp),
        ) {
            MazeLabel(
                when {
                    failed != null -> "Not sent"
                    done != null -> "Sent"
                    count == 1 -> "Send this file?"
                    else -> "Send $count files?"
                },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = when {
                    failed != null -> failed.message
                    done != null -> if (done.failed == 0) {
                        "Sent to your computer."
                    } else {
                        "${done.sent} sent, ${done.failed} failed."
                    }
                    working -> "Reaching your computer…"
                    else -> "Goes to the paired computer that's reachable right now."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.dim,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (failed != null || done != null) {
                    MazeButton("Close", onDismiss, modifier = Modifier.weight(1f))
                } else {
                    MazeButton(
                        "Cancel",
                        onDismiss,
                        primary = false,
                        enabled = !working,
                        modifier = Modifier.weight(1f),
                    )
                    MazeButton(
                        "Send",
                        onConfirm,
                        enabled = !working,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

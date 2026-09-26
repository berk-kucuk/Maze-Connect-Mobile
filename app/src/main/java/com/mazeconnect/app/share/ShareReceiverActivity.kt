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

/** What was shared: files, or a piece of text (often a link). */
internal sealed interface Shared {
    data class Files(val uris: List<Uri>) : Shared
    data class Text(val text: String) : Shared
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
open class ShareReceiverActivity : ComponentActivity() {

    /**
     * Whether what is shared can only be read once the window has focus.
     * True for the clipboard: Android 10+ hands an app its contents only
     * while one of its windows is focused, and onCreate is too early.
     */
    protected open val readsOnFocus: Boolean = false

    /** What to offer. The share sheet's intent, here; see subclasses. */
    internal open fun readShared(): Shared? = extractShare(intent)

    /** Said when there is nothing to send, instead of closing silently. */
    protected open val nothingToSend: String? = null

    private var shown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!readsOnFocus) present(readShared())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && readsOnFocus && !shown) present(readShared())
    }

    private fun present(shared: Shared?) {
        shown = true
        if (shared == null) {
            val message = nothingToSend
            if (message == null) {
                finish()
                return
            }
            setContent {
                MazeConnectTheme {
                    Confirm(
                        shared = Shared.Text(""),
                        stage = Stage.Failed(message),
                        onConfirm = {},
                        onDismiss = { finish() },
                    )
                }
            }
            return
        }

        setContent {
            MazeConnectTheme {
                var stage: Stage by remember { mutableStateOf(Stage.Asking) }

                Confirm(
                    shared = shared,
                    stage = stage,
                    onConfirm = {
                        stage = Stage.Working
                        lifecycleScope.launch {
                            stage = when (shared) {
                                is Shared.Files -> send(shared.uris)
                                is Shared.Text -> sendText(shared.text)
                            }
                        }
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }

    /** Text goes to the first reachable computer that takes it, onto its
     *  clipboard — the computer opens a link only if its user clicks it. */
    private suspend fun sendText(text: String): Stage {
        MazeConnectService.start(this)
        val manager = withTimeoutOrNull(LINK_WAIT_MS) {
            while (MazeConnectService.manager() == null) delay(POLL_MS)
            MazeConnectService.manager()
        } ?: return Stage.Failed(getString(R.string.guard_widget_no_service))

        val target = withTimeoutOrNull(LINK_WAIT_MS) {
            var found: String? = null
            while (found == null) {
                found = manager.connectedIds.value.firstOrNull {
                    manager.allows(it, Capability.SHARE_TEXT)
                }
                if (found == null) delay(POLL_MS)
            }
            found
        } ?: return Stage.Failed(
            "No paired computer that accepts text is reachable. If it is running, update " +
                "Maze Connect on the computer to 1.3.0 or later."
        )
        return if (manager.shareText(target, text)) {
            Stage.Done(1, 0)
        } else {
            Stage.Failed("That text cannot be sent — it is too long or contains control characters.")
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

        /**
         * Files if the share carries any, otherwise its text.
         *
         * Only content:// URIs are taken. This activity is exported — any app
         * can start it with any URI — and it reads with this app's own file
         * access, so a file:// URI naming one of Maze Connect's private files
         * would be read with permissions the sending app never had. A real
         * share always arrives as content://, granted by the app that owns it.
         */
        private fun extractShare(intent: Intent): Shared? {
            val uris = when (intent.action) {
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
            }.filter { it.scheme.equals("content", ignoreCase = true) }
            if (uris.isNotEmpty()) return Shared.Files(uris)

            if (intent.action != Intent.ACTION_SEND) return null
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
            return text?.takeIf { it.isNotEmpty() }?.let { Shared.Text(it) }
        }
    }
}

@Composable
private fun Confirm(shared: Shared, stage: Stage, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val count = (shared as? Shared.Files)?.uris?.size ?: 0
    val text = (shared as? Shared.Text)?.text
    val isLink = text != null && text.none { it.isWhitespace() } &&
        android.util.Patterns.WEB_URL.matcher(text).matches()
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
                    isLink -> "Send this link?"
                    text != null -> "Send this text?"
                    count == 1 -> "Send this file?"
                    else -> "Send $count files?"
                },
            )
            if (text != null && failed == null && done == null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MazeColors.Paper,
                    maxLines = 4,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = when {
                    failed != null -> failed.message
                    done != null && text != null ->
                        "It is on your computer's clipboard now."
                    done != null -> if (done.failed == 0) {
                        "Sent to your computer."
                    } else {
                        "${done.sent} sent, ${done.failed} failed."
                    }
                    working -> "Reaching your computer…"
                    text != null ->
                        "Goes to the clipboard of the paired computer that's reachable right now."
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

/**
 * "Send clipboard to computer", from the Quick Settings tile and the app
 * shortcut.
 *
 * Reads the clipboard only once its window has focus (the only moment
 * Android allows it) and then asks, with the text in view, exactly like a
 * share from another app — nothing is sent without that tap. Not exported:
 * only this app's own tile and shortcut can start it.
 */
class ClipboardSendActivity : ShareReceiverActivity() {
    override val readsOnFocus: Boolean = true

    override val nothingToSend: String = "There is no text on the clipboard."

    override fun readShared(): Shared? {
        val clip = getSystemService(android.content.ClipboardManager::class.java)
            ?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        return text.takeIf { it.isNotEmpty() }?.let { Shared.Text(it) }
    }
}

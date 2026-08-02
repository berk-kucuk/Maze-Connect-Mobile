package com.mazeconnect.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.mazeconnect.app.ui.components.Hairline
import com.mazeconnect.app.ui.components.MazeButton
import com.mazeconnect.app.ui.components.MazeLabel
import com.mazeconnect.app.ui.theme.LocalMazeColors
import com.mazeconnect.app.ui.theme.MazeColors
import com.mazeconnect.core.IncomingTransfer
import java.io.File

/**
 * Files this phone has received.
 *
 * A received file is only useful if it can be opened, and the inbox is inside
 * the app's own directory — so each finished row offers to hand the file to
 * another app through a FileProvider URI. That grants read access to the one
 * file the user picked, for as long as the receiving app needs it, rather
 * than exposing the directory.
 */
@Composable
fun TransfersScreen(
    transfers: List<IncomingTransfer>,
    inboxPath: String,
    onClearFinished: () -> Unit,
    onSendFile: (android.net.Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMazeColors.current
    val context = LocalContext.current

    // The system picker, so this app never needs storage permission and the
    // user grants access to exactly the file they chose.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onSendFile) }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MazeLabel("Files")
            Spacer(Modifier.width(12.dp))
            Hairline(Modifier.weight(1f).height(1.dp))
            Spacer(Modifier.width(12.dp))
            if (transfers.any { it.done }) {
                MazeButton("Clear", onClearFinished, primary = false)
                Spacer(Modifier.width(8.dp))
            }
            MazeButton("Send", { picker.launch(arrayOf("*/*")) })
        }

        if (transfers.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "No transfers yet",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "Send a file to the computer with the button above, or " +
                            "send one from the computer — it will ask before anything " +
                            "is written here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.dim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = inboxPath,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.dim,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(transfers.size) { index ->
                val transfer = transfers[index]
                Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = transfer.filename,
                                style = MaterialTheme.typography.titleMedium,
                                color = MazeColors.Paper,
                            )
                            Text(
                                text = when {
                                    transfer.error != null -> transfer.error!!
                                    transfer.done && transfer.outgoing ->
                                        "Sent · ${humanSize(transfer.total)}"
                                    transfer.done -> "Saved · ${humanSize(transfer.total)}"
                                    else -> {
                                        val verb = if (transfer.outgoing) "Sending" else "Receiving"
                                        "$verb · ${humanSize(transfer.received)} of " +
                                            humanSize(transfer.total)
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.dim,
                            )
                        }
                        if (transfer.done && transfer.error == null && transfer.path != null) {
                            MazeButton("Open", { openFile(context, transfer.path!!) })
                        }
                    }

                    if (!transfer.done) {
                        Spacer(Modifier.height(8.dp))
                        val fraction =
                            if (transfer.total > 0) {
                                (transfer.received.toFloat() / transfer.total).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        Box(
                            Modifier.fillMaxWidth().height(3.dp)
                                .background(colors.hairline, RectangleShape)
                        ) {
                            Box(
                                Modifier.fillMaxWidth(fraction).height(3.dp)
                                    .background(MazeColors.Paper, RectangleShape)
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
            }
        }
    }
}

/**
 * Hand one file to another app.
 *
 * A FileProvider URI rather than a file:// path: the latter throws on modern
 * Android, and this grants read access to exactly the file the user chose
 * instead of to the directory it sits in.
 */
private fun openFile(context: Context, path: String) {
    val file = File(path)
    // A path outside what file_paths.xml declares throws here, and that is a
    // packaging mistake rather than a user error — so it goes to the log
    // instead of leaving a button that silently does nothing.
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }.onFailure {
        android.util.Log.e("MazeTransfers", "cannot share $path: ${it.message}")
    }.getOrNull() ?: return

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Open with")) }
}

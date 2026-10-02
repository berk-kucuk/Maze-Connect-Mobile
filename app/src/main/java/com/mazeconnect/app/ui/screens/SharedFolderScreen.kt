package com.mazeconnect.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mazeconnect.app.R
import com.mazeconnect.app.data.DeviceRow
import com.mazeconnect.app.ui.components.Hairline
import com.mazeconnect.app.ui.components.MazeButton
import com.mazeconnect.app.ui.components.MazeLabel
import com.mazeconnect.app.ui.theme.LocalMazeColors
import com.mazeconnect.app.ui.theme.MazeColors
import com.mazeconnect.core.FolderDownload
import com.mazeconnect.core.IncomingTransfer
import com.mazeconnect.core.SharedFolderState

/**
 * The computer's shared folder — and nothing else of the computer's.
 *
 * The computer decides what is in it (~/Maze Connect Shared) and refuses any
 * path out of it, hidden files and links. Tapping a folder opens it; tapping
 * a picture opens a preview first, with Download beneath it; tapping any
 * other file downloads it into Files, without the usual "accept?" prompt,
 * because the tap was the request.
 *
 * Every file row carries its own download state — a button, then progress,
 * then Open — because a download that shows nothing on the row it started
 * from looks exactly like a button that does nothing. Downloads land in
 * Download/Maze Connect, where the Files app finds them.
 */
@Composable
fun SharedFolderScreen(
    devices: List<DeviceRow>,
    selectedDeviceId: String?,
    state: SharedFolderState?,
    onList: (String) -> Unit,
    onFetch: (String) -> Unit,
    modifier: Modifier = Modifier,
    previews: Map<String, com.mazeconnect.core.Preview> = emptyMap(),
    onPreview: (path: String, large: Boolean) -> Unit = { _, _ -> },
    downloads: Map<String, FolderDownload> = emptyMap(),
    transfers: List<IncomingTransfer> = emptyList(),
    onForgetDownload: (String) -> Unit = {},
) {
    val colors = LocalMazeColors.current
    val context = LocalContext.current
    fun stateOf(full: String) = downloadState(downloads[full], transfers)
    // One action for the row, the button and the viewer alike.
    fun act(full: String) {
        when (val st = stateOf(full)) {
            is DownloadState.Done -> openReceived(context, st.transfer)
            is DownloadState.Failed -> {
                onForgetDownload(full)
                onFetch(full)
            }
            DownloadState.Idle -> onFetch(full)
            else -> Unit
        }
    }
    var viewing by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    val target = devices.firstOrNull { it.deviceId == selectedDeviceId && it.connected }
    val current = state?.takeIf { it.deviceId == target?.deviceId }
    val path = current?.path ?: ""

    LaunchedEffect(target?.deviceId) {
        if (target != null) onList("")
    }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (path.isNotEmpty()) {
                Text(
                    "‹",
                    style = MaterialTheme.typography.titleLarge,
                    color = MazeColors.Paper,
                    modifier = Modifier
                        .clickable { onList(path.substringBeforeLast('/', "")) }
                        .padding(end = 12.dp),
                )
            }
            Text(
                text = if (path.isEmpty()) "Shared folder" else path.replace("/", " / "),
                style = MaterialTheme.typography.titleMedium,
                color = MazeColors.Paper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (target != null) MazeButton("Refresh", { onList(path) }, primary = false)
        }
        Hairline(Modifier.fillMaxWidth().height(1.dp))

        when {
            target == null -> FolderMessage("No computer", "Link a computer to browse its shared folder.")
            current == null || (current.loading && current.entries.isEmpty()) ->
                FolderMessage("Opening…", "Asking ${target.name} for its shared folder.")
            current.error != null -> FolderMessage("Not available", current.error!!)
            current.entries.isEmpty() -> FolderMessage(
                "Empty",
                "Put files in “Maze Connect Shared” in your home folder on ${target.name}, " +
                    "and they appear here.",
            )
            else -> {
                current.fetchError?.let {
                    Text(
                        "Could not download: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MazeColors.Paper,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(current.entries, key = { it.name }) { entry ->
                        val full = if (path.isEmpty()) entry.name else "$path/${entry.name}"
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    when {
                                        entry.dir -> onList(full)
                                        isPicture(entry.name) -> viewing = full
                                        else -> act(full)
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!entry.dir && isPicture(entry.name)) {
                                // Asked for as the row appears, so only what is
                                // on screen is fetched; the computer rate-limits
                                // the rest and the list scrolls on regardless.
                                LaunchedEffect(full) { onPreview(full, false) }
                                val thumb = previews["${target.deviceId}|$full|thumb"]
                                Box(
                                    Modifier.size(44.dp).background(MazeColors.Panel),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    com.mazeconnect.app.ui.components.PreviewImage(
                                        jpeg = thumb?.jpeg,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.size(44.dp),
                                    )
                                }
                            } else {
                                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(if (entry.dir) R.drawable.ic_files else R.drawable.ic_transfers),
                                        contentDescription = null,
                                        tint = if (entry.dir) MazeColors.Paper else colors.dim,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            val dl = if (entry.dir) DownloadState.Idle else stateOf(full)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MazeColors.Paper,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (entry.dir) "Folder" else dl.caption(entry.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (dl is DownloadState.Failed) MazeColors.Paper else colors.dim,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            if (entry.dir) {
                                Text("›", style = MaterialTheme.typography.titleLarge, color = colors.dim)
                            } else {
                                DownloadAction(dl, onClick = { act(full) })
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
                    }
                }
            }
        }
    }
    val shown = viewing
    val entry = shown?.let { p -> current?.entries?.firstOrNull { (if (path.isEmpty()) it.name else "$path/${it.name}") == p } }
    if (shown != null && entry != null && target != null) {
        LaunchedEffect(shown) { onPreview(shown, true) }
        PicturePreview(
            name = entry.name,
            size = entry.size,
            preview = previews["${target.deviceId}|$shown|large"],
            download = stateOf(shown),
            // The viewer stays open: the button below turns into the
            // download's progress, then into Open.
            onDownload = { act(shown) },
            onClose = { viewing = null },
        )
    }
}

/** Pictures get a preview before download; everything else downloads. */
private fun isPicture(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

/**
 * One picture, larger, before deciding. The computer made this preview from
 * the file (at most 1280 px, JPEG); the file itself only moves on Download.
 */
@Composable
fun PicturePreview(
    name: String,
    size: Long,
    preview: com.mazeconnect.core.Preview?,
    download: DownloadState = DownloadState.Idle,
    onDownload: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalMazeColors.current
    androidx.activity.compose.BackHandler(onBack = onClose)
    Box(
        Modifier
            .fillMaxSize()
            .background(MazeColors.Void)
            .clickable(indication = null, interactionSource = null) {},
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MazeColors.Paper,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(humanBytes(size), style = MaterialTheme.typography.labelSmall, color = colors.dim)
                }
                MazeButton("Close", onClose, primary = false)
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                when {
                    preview?.jpeg != null -> com.mazeconnect.app.ui.components.PreviewImage(
                        jpeg = preview.jpeg,
                        modifier = Modifier.fillMaxSize(),
                    )
                    preview?.error != null -> Text(
                        "No preview: ${preview.error}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.dim,
                    )
                    else -> Text("Loading preview…", style = MaterialTheme.typography.bodyMedium, color = colors.dim)
                }
            }
            Spacer(Modifier.height(12.dp))
            when (download) {
                is DownloadState.Waiting, is DownloadState.Receiving, DownloadState.Saving -> {
                    val fraction = (download as? DownloadState.Receiving)?.fraction
                    Text(
                        download.caption(size),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.dim,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (fraction == null) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = MazeColors.Paper,
                            trackColor = colors.hairline,
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = MazeColors.Paper,
                            trackColor = colors.hairline,
                            drawStopIndicator = {},
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }
                is DownloadState.Done -> {
                    Text(
                        download.caption(size),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.dim,
                    )
                    Spacer(Modifier.height(8.dp))
                    MazeButton("Open", onDownload, modifier = Modifier.fillMaxWidth())
                }
                is DownloadState.Failed -> {
                    Text(
                        download.caption(size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MazeColors.Paper,
                    )
                    Spacer(Modifier.height(8.dp))
                    MazeButton("Try again", onDownload, modifier = Modifier.fillMaxWidth())
                }
                DownloadState.Idle ->
                    MazeButton("Download ${humanBytes(size)}", onDownload, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** Where one file's download is, joined from its fetch and its transfer. */
sealed interface DownloadState {
    data object Idle : DownloadState
    /** Asked for; the computer has not started sending yet. */
    data object Waiting : DownloadState
    data class Receiving(val received: Long, val total: Long) : DownloadState {
        val fraction: Float get() = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    /** All bytes are here; being moved into Downloads. */
    data object Saving : DownloadState
    data class Done(val transfer: IncomingTransfer) : DownloadState
    data class Failed(val reason: String) : DownloadState
}

fun downloadState(fetch: FolderDownload?, transfers: List<IncomingTransfer>): DownloadState {
    if (fetch == null) return DownloadState.Idle
    fetch.error?.let { return DownloadState.Failed(it) }
    val id = fetch.transferId ?: return DownloadState.Waiting
    // The row appears when the offer is accepted, a moment after the id.
    val t = transfers.firstOrNull { it.transferId == id && !it.outgoing } ?: return DownloadState.Waiting
    return when {
        t.error != null -> DownloadState.Failed(t.error!!)
        t.done -> DownloadState.Done(t)
        t.total > 0 && t.received >= t.total -> DownloadState.Saving
        else -> DownloadState.Receiving(t.received, t.total)
    }
}

private fun DownloadState.caption(size: Long): String = when (this) {
    DownloadState.Idle -> humanBytes(size)
    DownloadState.Waiting -> "Waiting for the computer…"
    is DownloadState.Receiving -> "${humanBytes(received)} of ${humanBytes(total)} · ${(fraction * 100).toInt()}%"
    DownloadState.Saving -> "Saving to Downloads…"
    is DownloadState.Done -> transfer.savedTo?.let { "In $it · tap to open" }
        ?: "Downloaded · ${humanBytes(size)}"
    is DownloadState.Failed -> "Failed: $reason"
}

/** The row's trailing control: Download, progress, Open or retry. */
@Composable
private fun DownloadAction(state: DownloadState, onClick: () -> Unit) {
    val colors = LocalMazeColors.current
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        when (state) {
            DownloadState.Waiting, DownloadState.Saving -> CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = MazeColors.Paper,
                trackColor = colors.hairline,
                strokeWidth = 2.dp,
            )
            is DownloadState.Receiving -> CircularProgressIndicator(
                progress = { state.fraction },
                modifier = Modifier.size(22.dp),
                color = MazeColors.Paper,
                trackColor = colors.hairline,
                strokeWidth = 2.dp,
                gapSize = 0.dp,
            )
            is DownloadState.Done -> MazeButton("Open", onClick, primary = false)
            else -> Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .border(1.dp, colors.hairlineStrong, CircleShape)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                if (state is DownloadState.Failed) {
                    Text("↻", style = MaterialTheme.typography.titleMedium, color = MazeColors.Paper)
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = "Download",
                        tint = MazeColors.Paper,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderMessage(title: String, body: String) {
    val colors = LocalMazeColors.current
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MazeColors.Paper)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.dim,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

private fun humanBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

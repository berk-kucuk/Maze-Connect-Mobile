package com.mazeconnect.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.mazeconnect.core.SharedFolderState

/**
 * The computer's shared folder — and nothing else of the computer's.
 *
 * The computer decides what is in it (~/Maze Connect Shared) and refuses any
 * path out of it, hidden files and links. Tapping a folder opens it; tapping
 * a picture opens a preview first, with Download beneath it; tapping any
 * other file downloads it into Files, without the usual "accept?" prompt,
 * because the tap was the request.
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
) {
    val colors = LocalMazeColors.current
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
                                        else -> onFetch(full)
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
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MazeColors.Paper,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (entry.dir) "Folder" else humanBytes(entry.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.dim,
                                )
                            }
                            Text(
                                if (entry.dir) "›" else if (isPicture(entry.name)) "◉" else "↓",
                                style = MaterialTheme.typography.titleLarge,
                                color = colors.dim,
                            )
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
            onDownload = {
                onFetch(shown)
                viewing = null
            },
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
            MazeButton("Download ${humanBytes(size)}", onDownload, modifier = Modifier.fillMaxWidth())
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

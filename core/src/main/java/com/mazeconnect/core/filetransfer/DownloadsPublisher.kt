package com.mazeconnect.core.filetransfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File

/**
 * Puts a received file in the shared Downloads collection, where the Files
 * app, the browser's downloads and every file manager look for it.
 *
 * Through MediaStore, so no storage permission is involved: an app may
 * always add to Downloads, and the entry stays this app's until the user
 * moves or deletes it. MediaStore also picks a free name ("a (1).jpg") when
 * one is taken, so nothing already there is ever overwritten.
 */
object DownloadsPublisher {

    /** Where the files go, relative to shared storage. */
    private val RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/Maze Connect"

    /** The same place, the way the Files app names it. */
    const val FOLDER_LABEL = "Download/Maze Connect"

    /** The new entry, or null when it could not be written (the caller
     *  then leaves the file where it is). Null below Android 10. */
    fun publish(context: Context, file: File): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeOf(file.name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            // Hidden from other apps until it is complete.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        val copied = runCatching {
            resolver.openOutputStream(uri, "w")?.use { out ->
                file.inputStream().use { it.copyTo(out, 256 * 1024) }
                true
            } ?: false
        }.getOrDefault(false)
        if (!copied) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        runCatching {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null, null,
            )
        }
        return uri
    }

    fun mimeTypeOf(name: String): String =
        MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
}

package com.mazeconnect.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A computer-sent JPEG preview, decoded off the main thread.
 *
 * The bytes have already been bounded (size, JPEG marker, pixel dimensions —
 * see DeviceManager.validatedPreview), so decoding cannot be asked to build
 * an enormous bitmap. Nothing is drawn until it decodes; a preview that does
 * not decode simply stays empty.
 */
@Composable
fun PreviewImage(
    jpeg: ByteArray?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, jpeg) {
        value = jpeg?.let { bytes ->
            withContext(Dispatchers.Default) {
                runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    bitmap?.let {
        Image(bitmap = it, contentDescription = null, contentScale = contentScale, modifier = modifier)
    }
}

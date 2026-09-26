package com.mazeconnect.core

import com.mazeconnect.core.protocol.MessageType
import com.mazeconnect.core.protocol.PreviewImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/** What a computer-sent preview must be before it reaches the decoder. */
class PreviewImageTest {

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun aJpegPasses() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
        assertNotNull(PreviewImage.decode(b64(jpeg)))
    }

    @Test
    fun anythingElseIsRefused() {
        // A PNG, an executable, text: the computer only makes JPEGs.
        assertNull(PreviewImage.decode(b64(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))))
        assertNull(PreviewImage.decode(b64("#!/bin/sh\nrm -rf ~".toByteArray())))
        assertNull(PreviewImage.decode("not base64 at all!!"))
        assertNull(PreviewImage.decode(""))
        assertNull(PreviewImage.decode(null))
        // Over the cap: refused before a byte is decoded.
        assertNull(PreviewImage.decode("A".repeat(PreviewImage.MAX_BASE64_CHARS + 4)))
    }

    @Test
    fun wireNames() {
        // Duplicated in TestInterop (desktop): folderPreview / folderPreviewResult.
        assertEquals("folderPreview", MessageType.FOLDER_PREVIEW.wire)
        assertEquals("folderPreviewResult", MessageType.FOLDER_PREVIEW_RESULT.wire)
    }
}

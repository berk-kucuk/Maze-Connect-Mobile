package com.mazeconnect.core

import com.mazeconnect.core.filetransfer.FileTransferReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Receiving a file, and the ways a sender might try to write outside the box.
 *
 * A filename and a byte count from a peer are entirely attacker-controlled,
 * so each defence is tested on its own rather than trusting the combination:
 * the name is reduced to one safe component, the bytes go to a temp file
 * first, the count is kept independently of what was declared, and the final
 * path is re-resolved after the rename.
 *
 * That last one is the only check that can see a symlink already sitting at
 * the destination, which is why `refusesAFileThatResolvesOutsideTheInbox`
 * exists as its own case.
 */
class FileReceiverTest {

    private lateinit var root: File
    private lateinit var receiver: FileTransferReceiver

    @Before
    fun setUp() {
        root = Files.createTempDirectory("maze-inbox").toFile()
        receiver = FileTransferReceiver(File(root, "inbox"))
    }

    @After
    fun tearDown() {
        receiver.abortAll()
        root.deleteRecursively()
    }

    private fun send(id: Long, name: String, body: ByteArray): Result<File> {
        assertNull(receiver.begin(id, name, body.size.toLong()))
        assertNull(receiver.appendChunk(id, body))
        return receiver.finish(id)
    }

    @Test
    fun receivesAFile() {
        val body = "hello from the desktop".toByteArray()
        val file = send(1, "notes.txt", body).getOrThrow()

        assertTrue(file.exists())
        assertEquals("notes.txt", file.name)
        assertArrayEqualsBytes(body, file.readBytes())
    }

    @Test
    fun nothingIsWrittenUnderTheFinalNameUntilItIsComplete() {
        // A half-received file must never wear the name a complete one would:
        // otherwise an interrupted transfer looks like a finished download.
        assertNull(receiver.begin(1, "report.pdf", 100))
        assertNull(receiver.appendChunk(1, ByteArray(40)))

        val inbox = File(root, "inbox")
        assertFalse("a partial transfer took the final name",
            File(inbox, "report.pdf").exists())
        assertTrue("nothing was written at all",
            inbox.listFiles().orEmpty().any { it.name.startsWith(".part-") })
    }

    @Test
    fun aTruncatedTransferIsNotSaved() {
        assertNull(receiver.begin(1, "report.pdf", 100))
        assertNull(receiver.appendChunk(1, ByteArray(40)))

        // The sender says it is done, but 60 bytes never arrived.
        assertTrue(receiver.finish(1).isFailure)
        assertFalse(File(File(root, "inbox"), "report.pdf").exists())
        assertEquals(0, receiver.activeCount)
    }

    @Test
    fun aSenderCannotExceedTheSizeItDeclared() {
        // The count is kept here, not taken from the offer. A peer that
        // declares a kilobyte and streams a gigabyte is cut off at a kilobyte.
        assertNull(receiver.begin(1, "small.bin", 10))
        assertNotNull(
            "a sender wrote past its declared size",
            receiver.appendChunk(1, ByteArray(11)),
        )
        assertFalse(receiver.has(1))
        assertTrue(
            "a partial file survived the abort",
            File(root, "inbox").listFiles().orEmpty().isEmpty(),
        )
    }

    @Test
    fun pathTraversalNamesAreReducedToOneComponent() {
        // Not rejected outright — reduced. The point is that whatever arrives,
        // the write lands in the inbox and nowhere else.
        val inbox = File(root, "inbox")
        for (name in listOf("../../etc/passwd", "/etc/passwd", "a/b/c.txt", "..")) {
            val body = "x".toByteArray()
            val outcome = runCatching { send(System.nanoTime(), name, body) }.getOrNull()
            val file = outcome?.getOrNull() ?: continue
            assertEquals(
                "a received file escaped the inbox",
                inbox.canonicalPath,
                file.canonicalFile.parentFile!!.canonicalPath,
            )
        }
        // And nothing was created outside.
        assertFalse(File(root, "etc").exists())
    }

    @Test
    fun aResentNameDoesNotOverwrite() {
        val first = send(1, "photo.jpg", "first".toByteArray()).getOrThrow()
        val second = send(2, "photo.jpg", "second".toByteArray()).getOrThrow()

        assertTrue(first.exists())
        assertTrue(second.exists())
        assertFalse("the second transfer overwrote the first", first.path == second.path)
        assertEquals("first", first.readText())
    }

    @Test
    fun refusesAFileThatResolvesOutsideTheInbox() {
        // A symlink already sitting at the destination. No amount of
        // inspecting the *name* can see this — only re-resolving the path
        // after the rename can.
        val inbox = File(root, "inbox")
        inbox.mkdirs()
        val outside = File(root, "outside").apply { mkdirs() }

        val link = File(inbox, "escape.txt").toPath()
        val target = File(outside, "escape.txt").toPath()
        val made = runCatching { Files.createSymbolicLink(link, target) }.isSuccess
        org.junit.Assume.assumeTrue("symlinks unavailable on this filesystem", made)

        assertNull(receiver.begin(1, "escape.txt", 4))
        assertNull(receiver.appendChunk(1, "data".toByteArray()))
        val result = receiver.finish(1)

        // The property is "nothing lands outside the inbox" — not any
        // particular outcome. In practice rename(2) replaces the symlink
        // rather than following it, so the write stays inside and the
        // transfer succeeds; the post-rename resolve is defence in depth for
        // the filesystems where that is not true.
        assertFalse("bytes landed outside the inbox", File(outside, "escape.txt").exists())
        result.getOrNull()?.let { file ->
            assertEquals(
                "a received file escaped the inbox",
                inbox.canonicalPath,
                file.canonicalFile.parentFile!!.canonicalPath,
            )
        }
    }

    @Test
    fun refusesAnOversizedOrDuplicateOffer() {
        assertNotNull(receiver.validateOffer(1, "huge.bin", Limits.MAX_FILE_BYTES + 1))
        assertNotNull(receiver.validateOffer(1, "bad/../name", -1))

        assertNull(receiver.begin(1, "ok.txt", 4))
        assertNotNull(
            "a second transfer reused a live id",
            receiver.validateOffer(1, "other.txt", 4),
        )
    }

    @Test
    fun concurrentTransfersAreCapped() {
        for (i in 1..FileTransferReceiver.MAX_CONCURRENT) {
            assertNull(receiver.begin(i.toLong(), "f$i.bin", 10))
        }
        assertNotNull(
            "a peer opened more transfers than the cap allows",
            receiver.validateOffer(99, "one-too-many.bin", 10),
        )
    }

    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}

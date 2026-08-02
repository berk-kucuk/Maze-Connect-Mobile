package com.mazeconnect.core.filetransfer

import android.util.Log
import com.mazeconnect.core.Limits
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom

/**
 * Receives incoming files into a confined inbox.
 *
 * The Kotlin counterpart of the desktop's `FileTransferReceiver`, with the
 * same layered defences — because a filename and a byte count from a peer are
 * fully attacker-controlled, and one check is never enough:
 *
 *  1. The name is reduced to a safe single component ([PathSanitizer]).
 *  2. Bytes go to a randomly-named temp file, never straight to the final
 *     name, so a partly-received transfer cannot be mistaken for a complete
 *     one and a race on the final name has nothing to win.
 *  3. Bytes written are counted independently of the announced size; a peer
 *     that sends more than it declared is cut off mid-stream.
 *  4. After the rename the resulting path is re-resolved and checked to still
 *     be inside the inbox. This is the one that catches a pre-existing
 *     symlink, which no amount of name inspection can see.
 *
 * The inbox is the app's own external files directory. That needs no runtime
 * permission on any supported API, is visible to a file manager so a received
 * file is actually reachable, and goes away when the app is uninstalled.
 */
class FileTransferReceiver(private val inboxRoot: File) {

    /** One transfer in flight. */
    private class Incoming(
        val transferId: Long,
        val requestedName: String,
        val sanitizedName: String,
        val declaredSize: Long,
        val tempFile: File,
        val stream: FileOutputStream,
        var receivedBytes: Long = 0,
    )

    private val transfers = HashMap<Long, Incoming>()

    val root: File get() = inboxRoot

    fun has(transferId: Long): Boolean = transfers.containsKey(transferId)

    val activeCount: Int get() = transfers.size

    /**
     * Check an offer without creating anything.
     *
     * Accepting is a decision for the user; this only establishes that the
     * offer is one we *could* accept. Returns the reason it is not, or null.
     */
    fun validateOffer(transferId: Long, filename: String, sizeBytes: Long): String? {
        if (transfers.containsKey(transferId)) return "that transfer id is already in use"
        if (sizeBytes < 0 || sizeBytes > Limits.MAX_FILE_BYTES) return "file is too large"
        if (transfers.size >= MAX_CONCURRENT) return "too many transfers already running"
        PathSanitizer.sanitizeFilename(filename) ?: return "unsafe filename"
        return null
    }

    /** Open the temp file for an offer the user accepted. */
    fun begin(transferId: Long, filename: String, sizeBytes: Long): String? {
        validateOffer(transferId, filename, sizeBytes)?.let { return it }

        val safe = PathSanitizer.sanitizeFilename(filename) ?: return "unsafe filename"
        if (!inboxRoot.exists() && !inboxRoot.mkdirs()) return "could not create the inbox"

        // Random temp name: the final name is only taken at the very end, so
        // there is no window in which a half-written file wears it.
        val suffix = ByteArray(9).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val temp = File(inboxRoot, ".part-$suffix")

        val stream = try {
            FileOutputStream(temp)
        } catch (e: Exception) {
            return e.message ?: "could not open the file"
        }
        transfers[transferId] = Incoming(transferId, filename, safe, sizeBytes, temp, stream)
        return null
    }

    /**
     * Append a chunk. Returns the reason it was refused, or null.
     *
     * A refusal aborts the transfer and removes the partial file: there is no
     * state in which some of a rejected transfer survives on disk.
     */
    fun appendChunk(transferId: Long, chunk: ByteArray): String? {
        val incoming = transfers[transferId] ?: return "no such transfer"

        // Counted here rather than trusted from the offer. A peer that
        // declared 1 KB and streams a gigabyte is cut off at 1 KB.
        if (incoming.receivedBytes + chunk.size > incoming.declaredSize) {
            abort(transferId)
            return "sender exceeded the size it declared"
        }
        return try {
            incoming.stream.write(chunk)
            incoming.receivedBytes += chunk.size
            null
        } catch (e: Exception) {
            abort(transferId)
            e.message ?: "could not write"
        }
    }

    /**
     * Finalise: verify the size, take a free name, rename, re-check
     * confinement. Returns the final file, or null with [error] set.
     */
    fun finish(transferId: Long): Result<File> {
        val incoming = transfers[transferId]
            ?: return Result.failure(IllegalStateException("no such transfer"))

        val failure = { reason: String ->
            abort(transferId)
            Result.failure<File>(IllegalStateException(reason))
        }

        runCatching { incoming.stream.close() }

        // Short is a truncated transfer, not a complete small one.
        if (incoming.receivedBytes != incoming.declaredSize) {
            return failure("transfer ended early")
        }

        val name = PathSanitizer.uniqueName(incoming.sanitizedName) { candidate ->
            File(inboxRoot, candidate).exists()
        } ?: return failure("could not find a free filename")

        val target = File(inboxRoot, name)
        if (!incoming.tempFile.renameTo(target)) {
            return failure("could not move the file into place")
        }
        transfers.remove(transferId)

        // Re-resolved *after* the rename. A name check cannot see a symlink
        // that was already sitting at the destination; comparing the resolved
        // path against the inbox can.
        val resolved = runCatching { target.canonicalFile }.getOrNull()
        val rootResolved = runCatching { inboxRoot.canonicalFile }.getOrNull()
        if (resolved == null || rootResolved == null ||
            !resolved.path.startsWith(rootResolved.path + File.separator)
        ) {
            runCatching { target.delete() }
            Log.w(TAG, "received file resolved outside the inbox; deleted")
            return Result.failure(IllegalStateException("file landed outside the inbox"))
        }
        return Result.success(target)
    }

    /** Abort one transfer and delete whatever was written. */
    fun abort(transferId: Long) {
        val incoming = transfers.remove(transferId) ?: return
        runCatching { incoming.stream.close() }
        runCatching { incoming.tempFile.delete() }
    }

    fun abortAll() {
        for (id in transfers.keys.toList()) abort(id)
    }

    /** Bytes received so far, for progress. */
    fun progressOf(transferId: Long): Pair<Long, Long>? =
        transfers[transferId]?.let { it.receivedBytes to it.declaredSize }

    companion object {
        private const val TAG = "MazeFileReceiver"

        /** Matches the desktop: enough to be useful, bounded so a peer
         *  cannot open files without limit. */
        const val MAX_CONCURRENT = 4
    }
}

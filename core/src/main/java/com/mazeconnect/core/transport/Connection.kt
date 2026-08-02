package com.mazeconnect.core.transport

import com.mazeconnect.core.Limits
import com.mazeconnect.core.protocol.DataChunk
import com.mazeconnect.core.protocol.FrameParser
import com.mazeconnect.core.protocol.FrameType
import com.mazeconnect.core.protocol.Message
import com.mazeconnect.core.protocol.MessageType
import com.mazeconnect.core.protocol.ReplayWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket

/**
 * One mutually-authenticated TLS 1.3 link to a peer.
 *
 * Mirrors the desktop client's `Connection`, including the order of checks:
 * the pinned-key test happens in [PinnedTrustManager] during the handshake,
 * and the negotiated protocol is re-checked here rather than assumed, so a
 * mis-set configuration cannot turn into a silent downgrade.
 *
 * Replay checking runs before any message reaches a handler, so no side
 * effect can happen on a replayed message.
 */
class Connection(
    private val socket: SSLSocket,
    private val trustManager: PinnedTrustManager,
    private val scope: CoroutineScope,
) {
    /** The peer's SPKI DER public key, known once the handshake completed. */
    val peerPublicKey: ByteArray? get() = trustManager.peerPublicKey

    /** True when the peer's key was already pinned before this connection. */
    val peerWasTrusted: Boolean get() = trustManager.peerWasTrusted

    var onMessage: ((Message) -> Unit)? = null
    var onData: ((Long, ByteArray) -> Unit)? = null
    var onClosed: ((String?) -> Unit)? = null

    private val parser = FrameParser()
    private val replay = ReplayWindow()
    private val outgoingCounter = AtomicLong(0)

    @Volatile
    private var closed = false
    private var readJob: Job? = null
    private var heartbeatJob: Job? = null

    /** Wall-clock time of the last byte received, including heartbeat
     *  traffic. Updated off the coroutine that reads it, hence [Volatile]. */
    @Volatile
    private var lastActivityMs = System.currentTimeMillis()

    fun nextCounter(): Long = outgoingCounter.incrementAndGet()

    /** Verify the negotiated protocol, then begin reading. */
    fun start(): Boolean {
        if (!TlsFactory.negotiatedTls13(socket)) {
            close("negotiated protocol is not TLS 1.3")
            return false
        }
        if (peerPublicKey == null) {
            close("peer presented no usable key")
            return false
        }
        // The handshake timeout must not stay on the socket, or an idle but
        // healthy link would be torn down as if it had failed.
        socket.soTimeout = 0

        lastActivityMs = System.currentTimeMillis()
        readJob = scope.launch(Dispatchers.IO) { readLoop() }
        heartbeatJob = scope.launch(Dispatchers.IO) { heartbeatLoop() }
        return true
    }

    /**
     * Prove the peer is still there, or give up on it.
     *
     * TCP alone does not catch a half-open connection: a link can survive a
     * Wi-Fi reassociation, a NAT table eviction, or a DHCP lease change with
     * no FIN/RST ever arriving here, and [readLoop]'s blocking read would
     * simply wait forever. Without this, such a link sits "connected" and
     * dead until the app is force-restarted — which is the whole reason
     * this exists.
     */
    private suspend fun heartbeatLoop() {
        while (!closed) {
            delay(Limits.HEARTBEAT_INTERVAL_MS)
            if (closed) return
            val idleMs = System.currentTimeMillis() - lastActivityMs
            if (idleMs > Limits.HEARTBEAT_TIMEOUT_MS) {
                close("heartbeat timeout")
                return
            }
            if (idleMs >= Limits.HEARTBEAT_INTERVAL_MS) {
                send(Message.ping(nextCounter()))
            }
        }
    }

    suspend fun send(message: Message): Boolean = withContext(Dispatchers.IO) {
        val frame = FrameParser.encode(FrameType.CONTROL, message.toJson())
            ?: return@withContext false // over cap: refuse rather than truncate
        write(frame)
    }

    suspend fun sendData(transferId: Long, chunk: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            val frame = FrameParser.encode(FrameType.DATA, DataChunk.encode(transferId, chunk))
                ?: return@withContext false
            write(frame)
        }

    private fun write(frame: ByteArray): Boolean = try {
        if (closed) {
            false
        } else {
            socket.outputStream.write(frame)
            socket.outputStream.flush()
            true
        }
    } catch (e: IOException) {
        close(e.message)
        false
    }

    private fun readLoop() {
        val buffer = ByteArray(16 * 1024)
        try {
            while (!closed) {
                val read = socket.inputStream.read(buffer)
                if (read < 0) {
                    close(null) // clean EOF
                    return
                }
                // Any bytes at all prove the peer is alive, independent of
                // what they turn out to decode to.
                lastActivityMs = System.currentTimeMillis()
                parser.append(buffer, read)
                if (!drainFrames()) return
            }
        } catch (e: IOException) {
            close(if (closed) null else e.message)
        }
    }

    /** Returns false once the connection has been torn down. */
    private fun drainFrames(): Boolean {
        while (true) {
            when (val result = parser.next()) {
                is FrameParser.Result.Incomplete -> return true

                is FrameParser.Result.Error -> {
                    close("framing violation: ${result.reason}")
                    return false
                }

                is FrameParser.Result.Ready -> {
                    val frame = result.frame
                    if (frame.type == FrameType.CONTROL) {
                        val message = Message.parse(frame.payload)
                        if (message == null) {
                            close("malformed control message")
                            return false
                        }
                        // Before any handler runs, so a replayed message can
                        // never cause a side effect.
                        if (!replay.accept(message.counter)) {
                            close("replayed or out-of-window counter ${message.counter}")
                            return false
                        }
                        // Heartbeat traffic is a transport concern, not an
                        // application one: answered (or simply absorbed)
                        // here, never handed to DeviceManager. The activity
                        // stamp in readLoop already covers "the peer is
                        // alive" for both.
                        when (message.type) {
                            MessageType.PING -> {
                                scope.launch(Dispatchers.IO) { send(Message.pong(nextCounter())) }
                            }
                            MessageType.PONG -> {}
                            else -> onMessage?.invoke(message)
                        }
                    } else {
                        val decoded = DataChunk.decode(frame.payload)
                        if (decoded == null) {
                            close("malformed data frame")
                            return false
                        }
                        onData?.invoke(decoded.first, decoded.second)
                    }
                }
            }
        }
    }

    fun close(reason: String? = null) {
        if (closed) return
        closed = true
        readJob?.cancel()
        heartbeatJob?.cancel()
        runCatching { socket.close() }
        onClosed?.invoke(reason)
    }

    companion object {
        const val HANDSHAKE_TIMEOUT_MS = Limits.HANDSHAKE_TIMEOUT_MS
    }
}

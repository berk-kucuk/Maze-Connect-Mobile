package com.mazeconnect.core

import android.content.Context
import android.util.Log
import com.mazeconnect.core.crypto.Fingerprint
import com.mazeconnect.core.crypto.Sas
import com.mazeconnect.core.discovery.Beacon
import com.mazeconnect.core.discovery.DiscoveredDevice
import com.mazeconnect.core.filetransfer.FileTransferReceiver
import com.mazeconnect.core.keystore.AndroidKeyStoreManager
import com.mazeconnect.core.pairing.PairedDevice
import com.mazeconnect.core.pairing.PairedDeviceStore
import com.mazeconnect.core.protocol.Capability
import com.mazeconnect.core.protocol.Message
import com.mazeconnect.core.protocol.MessageType
import com.mazeconnect.core.protocol.SystemStatus
import com.mazeconnect.core.transport.Connection
import com.mazeconnect.core.transport.TlsFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** A pairing awaiting the user's decision on both ends. */
data class PendingPairing(
    val deviceId: String,
    val deviceName: String,
    val peerPublicKey: ByteArray,
    val verificationCode: String,
    val weInitiated: Boolean,
    val localAccepted: Boolean = false,
    val remoteAccepted: Boolean = false,
) {
    // Compares every field, not just the id.
    //
    // StateFlow only emits when the new value differs from the old one, so
    // an equals() that ignored verificationCode silently swallowed the
    // update that fills the code in — the code was derived correctly and the
    // dialog never appeared. The ByteArray is the only reason this is
    // hand-written at all; it needs contentEquals rather than identity.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingPairing) return false
        return deviceId == other.deviceId &&
            deviceName == other.deviceName &&
            verificationCode == other.verificationCode &&
            weInitiated == other.weInitiated &&
            localAccepted == other.localAccepted &&
            remoteAccepted == other.remoteAccepted &&
            peerPublicKey.contentEquals(other.peerPublicKey)
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + verificationCode.hashCode()
        result = 31 * result + weInitiated.hashCode()
        result = 31 * result + localAccepted.hashCode()
        result = 31 * result + remoteAccepted.hashCode()
        result = 31 * result + peerPublicKey.contentHashCode()
        return result
    }
}

/**
 * The dashboard's current answer for one computer.
 *
 * [status] and [error] are not exclusive: a refresh that fails leaves the
 * previous snapshot in place with a reason beside it, because blanking a panel
 * the user is reading is worse than admitting the reading is a moment old.
 */
data class SystemStatusState(
    val deviceId: String,
    val status: SystemStatus?,
    val error: String?,
)

/**
 * One entry of a computer's allow-list, as the phone is allowed to see it.
 *
 * There is no argv here, and that is not an oversight: the computer does not
 * send one. The phone holds a label and an id, and sending the id back is the
 * whole of what it can ask for.
 */
data class RemoteCommand(
    val id: String,
    val label: String,
    val confirm: Boolean,
    /** Shown on the phone's home-screen Commands widget. Set only on the
     *  computer — read-only here, same as every other field. */
    val pinned: Boolean = false,
)

/** The allow-list and the last run, for one computer. */
data class CommandsState(
    val deviceId: String,
    val commands: List<RemoteCommand> = emptyList(),
    val running: Set<String> = emptySet(),
    val lastId: String? = null,
    val lastExitCode: Int? = null,
    val lastOutput: String? = null,
    val lastError: String? = null,
)

/** One turn of a conversation with Maze AI. */
data class AiTurn(val fromUser: Boolean, val text: String, val failed: Boolean = false)

/**
 * The Maze AI conversation with one computer.
 *
 * The transcript here is only what this screen has shown. The authoritative
 * history lives on the computer, keyed by device — this phone never sends it
 * back, so it cannot edit what "was said earlier".
 */
data class AiState(
    val deviceId: String,
    val models: List<String> = emptyList(),
    val model: String? = null,
    val turns: List<AiTurn> = emptyList(),
    val streaming: Boolean = false,
    val error: String? = null,
)

/**
 * One killswitch, as the computer reports it.
 *
 * `state` is deliberately a string from a fixed set rather than a boolean:
 * "none" (the machine has no such device) is not "off", and a phone that
 * showed a protection which does not exist would be lying about security.
 */
data class GuardSwitch(val device: String, val state: String) {
    val blocked: Boolean get() = state == "off"
    val present: Boolean get() = state != "none"
    val known: Boolean get() = state == "on" || state == "off"
}

/** The killswitches on one computer, and the last change asked for. */
data class GuardStateSnapshot(
    val deviceId: String,
    val switches: List<GuardSwitch> = emptyList(),
    val pending: String? = null,
    val error: String? = null,
)

/** An incoming file waiting for the user's decision. */
data class PendingFileOffer(
    val deviceId: String,
    val deviceName: String,
    val transferId: Long,
    val filename: String,
    val sizeBytes: Long,
)

/** A transfer the user accepted, as the UI follows it. */
data class IncomingTransfer(
    val transferId: Long,
    val filename: String,
    val received: Long,
    val total: Long,
    val done: Boolean = false,
    val error: String? = null,
    /** Where it landed, once finished. Receiving only. */
    val path: String? = null,
    /** True when this phone is the sender. */
    val outgoing: Boolean = false,
)

/** Something the UI should tell the user about. */
sealed interface DeviceEvent {
    data class PairingFailed(val deviceId: String, val reason: String) : DeviceEvent
    data class PairingCompleted(val deviceId: String, val accepted: Boolean) : DeviceEvent
    data class SecurityAlert(val summary: String, val detail: String) : DeviceEvent
    data class FileReceived(val deviceId: String, val filename: String, val path: String) :
        DeviceEvent
    data class FileFailed(val deviceId: String, val reason: String) : DeviceEvent

    /** The computer pushed clipboard text to open here. Delivery (a
     *  notification, since this can arrive with the app backgrounded) is
     *  the service's job, not this event's — see MazeConnectService. */
    data class OpenOnPhone(val deviceId: String, val text: String) : DeviceEvent
}

/**
 * Owns identity, pinned devices, discovery, the listener, live links and the
 * pairing state machine. The Kotlin counterpart of the desktop client's
 * `DeviceManager`, and it must stay behaviourally in step with it.
 *
 * Message-level authorisation lives here, and it is the second half of the
 * transport's trust model: [Connection] admits an unpaired peer so pairing is
 * possible at all, and this class ensures such a peer can do *nothing* except
 * pair. Every other capability additionally requires the user to have enabled
 * it for that specific device.
 *
 * More than one computer can be connected at once, and every piece of
 * per-device state here — dashboard, commands, guard, AI, and the
 * bookkeeping behind AI streaming and in-flight command runs — is keyed by
 * device id rather than holding a single value. That used not to be true:
 * a second computer's answer used to silently overwrite the first's, which
 * is invisible with one paired computer and wrong the moment there are two.
 */
/** Sets one entry of a per-device state map. The common shape behind every
 *  status/commands/guard/AI update below. */
private fun <T> MutableStateFlow<Map<String, T>>.put(deviceId: String, entry: T) {
    value = value + (deviceId to entry)
}

/** Drops one entry, if present. */
private fun <T> MutableStateFlow<Map<String, T>>.remove(deviceId: String) {
    if (deviceId in value) value = value - deviceId
}

class DeviceManager(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val keyStore = AndroidKeyStoreManager()
    private val store = PairedDeviceStore(appContext)
    private val tls = TlsFactory(keyStore, store)
    val beacon = Beacon(scope)

    private val _pairedDevices = MutableStateFlow<List<PairedDevice>>(emptyList())
    val pairedDevices: StateFlow<List<PairedDevice>> = _pairedDevices.asStateFlow()

    private val _connectedIds = MutableStateFlow<Set<String>>(emptySet())
    val connectedIds: StateFlow<Set<String>> = _connectedIds.asStateFlow()

    private val _pendingPairing = MutableStateFlow<PendingPairing?>(null)
    val pendingPairing: StateFlow<PendingPairing?> = _pendingPairing.asStateFlow()

    private val _events = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<DeviceEvent> = _events.asSharedFlow()

    // Each of these four is keyed by device id rather than holding one
    // value, so two computers connected at once each keep their own
    // reading instead of overwriting each other's — see the multi-device
    // note in the class comment.
    private val _systemStatus = MutableStateFlow<Map<String, SystemStatusState>>(emptyMap())
    val systemStatus: StateFlow<Map<String, SystemStatusState>> = _systemStatus.asStateFlow()

    private val _commands = MutableStateFlow<Map<String, CommandsState>>(emptyMap())
    val commands: StateFlow<Map<String, CommandsState>> = _commands.asStateFlow()

    private val _ai = MutableStateFlow<Map<String, AiState>>(emptyMap())
    val ai: StateFlow<Map<String, AiState>> = _ai.asStateFlow()

    private val _guard = MutableStateFlow<Map<String, GuardStateSnapshot>>(emptyMap())
    val guard: StateFlow<Map<String, GuardStateSnapshot>> = _guard.asStateFlow()

    private val _fileOffer = MutableStateFlow<PendingFileOffer?>(null)
    val fileOffer: StateFlow<PendingFileOffer?> = _fileOffer.asStateFlow()

    private val _transfers = MutableStateFlow<List<IncomingTransfer>>(emptyList())
    val transfers: StateFlow<List<IncomingTransfer>> = _transfers.asStateFlow()

    /** Files we offered and the computer has not answered yet, by transfer
     *  id. The bytes are only read once it accepts. */
    private val outgoing = java.util.concurrent.ConcurrentHashMap<Long, OutgoingFile>()
    private val nextTransferId = java.util.concurrent.atomic.AtomicLong(1)

    private class OutgoingFile(
        val deviceId: String,
        val uri: android.net.Uri,
        val filename: String,
        val sizeBytes: Long,
    )

    /**
     * The inbox. The app's own external files directory: no runtime
     * permission on any supported API, reachable from a file manager so a
     * received file is actually usable, and removed on uninstall.
     */
    private val receiver by lazy {
        FileTransferReceiver(
            File(
                appContext.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                    ?: appContext.filesDir,
                "inbox",
            )
        )
    }

    /** Computers we are dialing right now, so a burst of beacons cannot open
     *  several sockets to the same one. */
    private val outboundPending = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    /** The reply we are currently streaming from each computer, if any —
     *  by device id, not a single value: two computers can each have a
     *  turn in flight at once. */
    private val aiRequestIds = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val nextAiRequestId = java.util.concurrent.atomic.AtomicLong(1)

    /** Our own request ids for in-flight runs, mapped to which device asked
     *  and which command it was. Allocated here so two computers cannot
     *  collide — but a result is only honoured back on the same link it was
     *  sent to, so the device half still matters. */
    private val commandRuns = java.util.concurrent.ConcurrentHashMap<Long, Pair<String, String>>()
    private val nextCommandRequestId = java.util.concurrent.atomic.AtomicLong(1)

    /** Devices we have asked for a snapshot and not yet heard back from. */
    private val statusAwaiting = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    val discovered: StateFlow<List<DiscoveredDevice>> get() = beacon.devices

    var deviceId: String = ""
        private set
    var deviceName: String = ""
        private set

    val fingerprint: String
        get() = keyStore.publicKey()?.let { Fingerprint.hexOf(it) } ?: ""

    val inboxDir: File get() = File(appContext.filesDir, "inbox")

    // Touched from the IO read loop as well as the main thread.
    private val links = java.util.concurrent.ConcurrentHashMap<String, Link>()
    private val pairingAttempts = mutableMapOf<String, MutableList<Long>>()
    private var serverSocket: SSLServerSocket? = null
    private var listenPort = 0

    private class Link(
        val connection: Connection,
        var deviceId: String,
        var deviceName: String,
        var trusted: Boolean,
        var helloReceived: Boolean = false,
        var peerCapabilities: Set<Capability> = emptySet(),
        var ourNonce: ByteArray? = null,
        var theirNonce: ByteArray? = null,
    )

    /**
     * Keep dialing while the app is alive.
     *
     * Three triggers, because each covers a case the others miss:
     *  * a **beacon sighting** reconnects within a second of the computer
     *    reappearing, which is the common case;
     *  * a **periodic sweep** covers a computer that was already announcing
     *    before we started listening, and any dial that simply failed;
     *  * a **network change** (see MazeConnectService) kicks both immediately
     *    rather than waiting out the sweep interval after Wi-Fi returns.
     */
    private fun startReconnectLoop() {
        scope.launch {
            beacon.devices.collect { reconnectPairedDevices() }
        }
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(RECONNECT_INTERVAL_MS)
                reconnectPairedDevices()
            }
        }
        scope.launch {
            // A slow background reading, so the home-screen widgets have
            // something to draw without the user ever opening the matching
            // screen. That was the whole reason the dashboard widget looked
            // empty: the snapshot was only ever fetched by the dashboard's
            // own poll — and the Commands widget repeated exactly that
            // mistake, since nothing but opening the in-app Commands screen
            // ever called requestCommands() either.
            //
            // Nearly free: the computer caches for a couple of seconds and
            // answers an unchanged reading with `statusUnchanged`, a few bytes
            // rather than the two-kilobyte snapshot. requestCommands() has no
            // such shortcut, but a command catalogue is a handful of short
            // entries, not a snapshot — sending it every sweep costs nothing
            // worth avoiding.
            while (true) {
                for (id in _connectedIds.value) {
                    requestStatus(id)
                    requestGuardStatus(id)
                    requestCommands(id)
                }
                kotlinx.coroutines.delay(BACKGROUND_STATUS_INTERVAL_MS)
            }
        }
    }

    fun start(name: String): Boolean {
        if (!keyStore.ensureIdentity()) return false
        deviceName = name.take(Limits.MAX_DEVICE_NAME_CHARS)
        deviceId = loadOrCreateDeviceId()
        store.load()
        publishDevices()

        if (!startListener()) return false
        beacon.start(deviceId, deviceName, "mobile", listenPort)
        startReconnectLoop()
        return true
    }

    /**
     * The network came back (or changed). Re-announce and dial straight away.
     *
     * Without this the app waits out the sweep interval after every Wi-Fi
     * blip, which is exactly the moment a user picks the phone up to see
     * whether it reconnected.
     */
    fun onNetworkAvailable() {
        Log.i(TAG, "network available: re-announcing and reconnecting")
        beacon.refresh()
        reconnectPairedDevices()
    }

    fun stop() {
        beacon.stop()
        links.values.forEach { it.connection.close() }
        links.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        _connectedIds.value = emptySet()
    }

    // ---- Listener --------------------------------------------------------

    private fun startListener(): Boolean = try {
        val (context, _) = tls.createContext(allowUnpaired = true)
        val server = context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        server.needClientAuth = true
        server.enabledProtocols = arrayOf("TLSv1.3")
        serverSocket = server
        listenPort = server.localPort

        scope.launch(Dispatchers.IO) { acceptLoop(server) }
        true
    } catch (e: Exception) {
        Log.e(TAG, "could not start the listener", e)
        false
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val socket = try {
                server.accept() as SSLSocket
            } catch (_: Exception) {
                return
            }
            // Unpaired peers are admitted at the transport layer so pairing
            // works without arranging a synchronised "pair mode" on both
            // devices. handleMessage() is what confines them.
            val (_, trustManager) = tls.createContext(allowUnpaired = true)
            socket.soTimeout = Connection.HANDSHAKE_TIMEOUT_MS
            val ok = runCatching { socket.startHandshake(); true }.getOrDefault(false)
            if (!ok) {
                runCatching { socket.close() }
                continue
            }
            withContext(Dispatchers.Main) { adopt(Connection(socket, trustManager, scope)) }
        }
    }

    // ---- Pairing ---------------------------------------------------------

    /**
     * Begin pairing with a device at an explicit address.
     *
     * The address only decides who we dial; the SAS comparison is what
     * authorises, so a spoofed beacon buys an attacker nothing here.
     */
    fun requestPairingAt(address: InetAddress, port: Int, targetDeviceId: String) {
        if (store.forId(targetDeviceId) != null) return // already paired
        if (_pendingPairing.value != null) return // one pairing at a time

        scope.launch(Dispatchers.IO) {
            val result = runCatching { tls.connect(address, port, allowUnpaired = true) }
            val (socket, trustManager) = result.getOrElse {
                emit(DeviceEvent.PairingFailed(targetDeviceId, it.message ?: "could not connect"))
                return@launch
            }

            val connection = Connection(socket, trustManager, scope)
            val nonce = Sas.generateNonce()

            withContext(Dispatchers.Main) {
                // The pending pairing and the nonce are in place before the
                // link starts reading, so a fast pairResponse cannot arrive
                // before we are ready to derive the code from it.
                _pendingPairing.value = PendingPairing(
                    deviceId = targetDeviceId,
                    deviceName = targetDeviceId,
                    peerPublicKey = connection.peerPublicKey ?: ByteArray(0),
                    verificationCode = "",
                    weInitiated = true,
                )

                val adopted = adopt(connection, targetDeviceId, ourNonce = nonce) {
                    connection.send(Message.pairRequest(connection.nextCounter(), nonce))
                }
                if (!adopted) {
                    _pendingPairing.value = null
                    emit(DeviceEvent.PairingFailed(targetDeviceId, "could not start the link"))
                }
            }
        }
    }

    fun respondToPairing(accept: Boolean) {
        val pending = _pendingPairing.value ?: return
        val link = links[pending.deviceId] ?: run {
            _pendingPairing.value = null
            return
        }

        _pendingPairing.value = pending.copy(localAccepted = accept)

        scope.launch {
            link.connection.send(Message.pairResult(link.connection.nextCounter(), accept))
            if (!accept) {
                _pendingPairing.value = null
                emit(DeviceEvent.PairingCompleted(pending.deviceId, false))
                link.connection.close()
            } else {
                withContext(Dispatchers.Main) { finalizePairing(link) }
            }
        }
    }

    fun unpair(targetDeviceId: String): Boolean {
        val device = store.forId(targetDeviceId) ?: return false
        links[targetDeviceId]?.let { link ->
            // Close *after* the send completes. Launching the send and closing
            // on this thread raced, and the close all but always won — so the
            // notice never left, and the computer went on listing a phone that
            // had removed it.
            scope.launch {
                link.connection.send(Message.unpair(link.connection.nextCounter()))
                link.connection.close()
            }
        }
        val removed = store.remove(device.publicKey)
        publishDevices()
        return removed
    }

    /**
     * User-initiated escape hatch: drop whatever link this device has (if
     * any) and redial it immediately, without waiting for the heartbeat
     * timeout or the next sweep.
     *
     * Exists for the same failure the heartbeat is meant to catch
     * automatically — a half-open link that TCP itself never notices — as a
     * fast, visible way out for a user staring at "not reachable" right now.
     * Closing here is enough on its own: [Connection.onClosed] already
     * removes the link and [reconnectPairedDevices] already redials anything
     * paired and currently seen by the beacon.
     */
    fun forceReconnect(targetDeviceId: String): Boolean {
        if (store.forId(targetDeviceId) == null) return false
        links[targetDeviceId]?.connection?.close("manual reconnect")
        reconnectPairedDevices()
        return true
    }

    private fun finalizePairing(link: Link) {
        val pending = _pendingPairing.value ?: return
        // Both sides must have said yes. A single-sided accept never pins.
        if (!pending.localAccepted || !pending.remoteAccepted) return

        val device = PairedDevice(
            deviceId = pending.deviceId,
            deviceName = pending.deviceName,
            deviceType = "desktop",
            publicKey = pending.peerPublicKey,
            pairedAtEpochSeconds = System.currentTimeMillis() / 1000,
        )
        _pendingPairing.value = null

        if (!store.add(device)) {
            emit(DeviceEvent.PairingFailed(device.deviceId, "could not store the pairing"))
            link.connection.close()
            return
        }
        link.trusted = true
        publishDevices()
        markConnected(device.deviceId, true)
        emit(DeviceEvent.PairingCompleted(device.deviceId, true))
    }

    // ---- Link lifecycle --------------------------------------------------

    /**
     * @param afterHello runs in the *same* coroutine as the hello, right
     *        after it. Hello has to be the first message on the wire — the
     *        peer files the pairing under the id it carries — and launching
     *        the follow-up separately raced with it, which the desktop then
     *        rejected as "first message was pairRequest".
     */
    private fun adopt(
        connection: Connection,
        expectedId: String? = null,
        ourNonce: ByteArray? = null,
        afterHello: (suspend () -> Unit)? = null,
    ): Boolean {
        val key = connection.peerPublicKey ?: return false
        val paired = store.forKey(key)
        val id = expectedId ?: paired?.deviceId ?: "pending-${UUID.randomUUID()}"

        val link = Link(
            connection = connection,
            deviceId = id,
            deviceName = paired?.deviceName ?: id,
            trusted = paired != null,
            // Set before the read loop starts, so a reply that arrives
            // immediately still finds everything it needs.
            ourNonce = ourNonce,
        )
        links[id] = link

        connection.onMessage = { message -> handleMessage(link, message) }
        connection.onData = { transferId, chunk -> handleData(link, transferId, chunk) }
        connection.onClosed = { reason ->
            if (reason != null) Log.w(TAG, "link closed: $reason")
            links.remove(link.deviceId)
            markConnected(link.deviceId, false)
            // Anything we were waiting on died with the link. Left behind,
            // a stale entry would make the *next* answer look unsolicited
            // and be dropped — which is how a reconnected dashboard stays
            // blank even though the link came back.
            forgetPendingWork(link.deviceId)
            if (reason != null && !link.trusted) {
                emit(DeviceEvent.PairingFailed(link.deviceId, reason))
            }
        }

        // Only now start reading. Starting the read loop before the handlers
        // are attached drops whatever arrives in between — and the peer sends
        // its hello the instant the link comes up, so that window is hit
        // almost every time.
        if (!connection.start()) {
            links.remove(id)
            return false
        }

        scope.launch {
            connection.send(
                Message.hello(
                    connection.nextCounter(),
                    deviceId,
                    deviceName,
                    "mobile",
                    // What we implement, not what the peer may use: the
                    // peer's own per-device switch decides the latter.
                    Capability.toNames(Capability.SUPPORTED),
                    PROTOCOL_VERSION.toString(),
                )
            )
            afterHello?.invoke()
        }

        if (link.trusted) markConnected(link.deviceId, true)
        return true
    }

    private fun handleMessage(link: Link, message: Message) {
        // Connection and pairing decisions are logged so "what did it refuse,
        // and why" is answerable after the fact. Message contents never are.
        Log.i(TAG, "recv ${message.type.wire} trusted=${link.trusted}")
        // Gate: an untrusted peer may only pair. This is the other half of
        // the transport's trust model — Connection lets an unknown key
        // complete TLS so pairing is possible, and this stops such a peer
        // from doing anything else with that access.
        if (!link.trusted && message.type !in PAIRING_MESSAGES) {
            // A peer asking for something other than pairing believes it is
            // paired with us, and it is not — so it was removed here while it
            // was offline, and the notice we sent at the time went nowhere.
            // Say so now, otherwise it stays listed on the other side forever
            // and reconnects only to be refused again.
            //
            // Safe to send unconditionally: a peer that is genuinely mid-
            // pairing only sends PAIRING_MESSAGES and never reaches here.
            scope.launch {
                link.connection.send(Message.unpair(link.connection.nextCounter()))
                link.connection.close("unpaired peer attempted ${message.type.wire}")
            }
            return
        }

        when (message.type) {
            MessageType.HELLO -> handleHello(link, message)

            MessageType.PAIR_REQUEST -> handlePairRequest(link, message)

            MessageType.PAIR_RESPONSE -> handlePairResponse(link, message)

            MessageType.PAIR_RESULT -> {
                val pending = _pendingPairing.value ?: return
                val accepted = message.boolean("accepted")
                if (!accepted) {
                    _pendingPairing.value = null
                    emit(DeviceEvent.PairingCompleted(link.deviceId, false))
                    link.connection.close()
                    return
                }
                _pendingPairing.value = pending.copy(remoteAccepted = true)
                finalizePairing(link)
            }

            MessageType.UNPAIR -> {
                link.connection.peerPublicKey?.let { store.remove(it) }
                publishDevices()
                emit(DeviceEvent.PairingCompleted(link.deviceId, false))
                link.connection.close()
            }

            MessageType.STATUS_REPORT -> handleStatusReport(link, message)

            MessageType.STATUS_UNCHANGED -> {
                // The computer says its reading is the one already on screen.
                // Keep it, and clear any error — being told "still current"
                // is a successful answer, not a missing one.
                if (statusAwaiting.remove(link.deviceId)) {
                    _systemStatus.value[link.deviceId]?.let {
                        _systemStatus.put(link.deviceId, it.copy(error = null))
                    }
                }
            }

            MessageType.OPEN_ON_PHONE -> handleOpenOnPhone(link, message)

            MessageType.COMMAND_CATALOG -> handleCommandCatalog(link, message)

            MessageType.COMMAND_RESULT -> handleCommandResult(link, message)

            MessageType.AI_MODEL_LIST -> handleAiModelList(link, message)

            MessageType.AI_CHUNK -> handleAiChunk(link, message)

            MessageType.AI_DONE -> handleAiDone(link, message)

            MessageType.GUARD_REPORT -> handleGuardReport(link, message)

            MessageType.GUARD_RESULT -> handleGuardResult(link, message)

            MessageType.FILE_OFFER -> handleFileOffer(link, message)

            MessageType.FILE_ACCEPT -> handleFileAccept(link, message)

            MessageType.FILE_COMPLETE -> handleFileComplete(link, message)

            MessageType.FILE_CANCEL, MessageType.FILE_REJECT -> {
                val id = message.integer("transferId", 0xFFFFFFFFL) ?: return
                // Could be either direction: a file we were receiving, or one
                // we offered and the computer declined.
                outgoing.remove(id)
                receiver.abort(id)
                updateTransfer(id) {
                    it.copy(
                        done = true,
                        error = message.string("reason", 256) ?: "cancelled by the sender",
                    )
                }
                if (_fileOffer.value?.transferId == id) _fileOffer.value = null
            }

            else -> {
                // File transfer is accepted by the protocol but not yet
                // surfaced on mobile; ignoring it is correct, acting on it
                // would not be.
            }
        }
    }

    /**
     * Clipboard text pushed from the computer.
     *
     * Bounded and control-character-free like every other peer-supplied
     * string before it goes anywhere near a screen. What happens with it —
     * a notification, since this may arrive with the app backgrounded — is
     * decided outside DeviceManager entirely; this only validates and hands
     * it off.
     */
    private fun handleOpenOnPhone(link: Link, message: Message) {
        if (!allowed(link, Capability.OPEN_ON_PHONE)) return
        val text = message.string("text", Limits.MAX_OPEN_TEXT_CHARS) ?: return
        emit(DeviceEvent.OpenOnPhone(link.deviceId, text))
    }

    private fun handleStatusReport(link: Link, message: Message) {
        // Having asked is the authorisation, not merely being paired: a
        // report nobody requested has no business reaching the screen.
        if (!statusAwaiting.remove(link.deviceId)) {
            Log.i(TAG, "ignoring unsolicited status report")
            return
        }
        // Kept when a refresh fails, so a momentary problem on the computer
        // does not blank a panel the user is reading.
        val previous = _systemStatus.value[link.deviceId]?.status

        val error = message.string("error", MAX_STATUS_ERROR_CHARS)
        if (error != null) {
            _systemStatus.put(link.deviceId, SystemStatusState(link.deviceId, previous, error))
            return
        }

        val status = SystemStatus.parse(message.unvalidatedObject("status"))
        _systemStatus.put(
            link.deviceId,
            SystemStatusState(
                deviceId = link.deviceId,
                status = status ?: previous,
                // A report that parsed to nothing is said out loud rather than
                // left as a blank dashboard, which would read as a healthy
                // machine with nothing running on it.
                error = if (status == null) "the computer sent an unreadable snapshot" else null,
            ),
        )
    }

    private fun handleHello(link: Link, message: Message) {
        if (link.helloReceived) {
            link.connection.close("duplicate hello")
            return
        }
        link.helloReceived = true

        val id = message.string("deviceId", Limits.MAX_DEVICE_ID_CHARS)
        val name = message.string("deviceName", Limits.MAX_DEVICE_NAME_CHARS)
        if (id == null || name == null) {
            link.connection.close("malformed hello")
            return
        }

        if (link.trusted) {
            // For a paired device the id is fixed by the pin. A peer claiming
            // a different id over an authenticated link is either a bug or an
            // attempt to be treated as another device.
            val pinned = link.connection.peerPublicKey?.let { store.forKey(it) }
            if (pinned != null && pinned.deviceId != id) {
                link.connection.close("paired device changed its id")
                return
            }
        } else {
            links.remove(link.deviceId)
            link.deviceId = id
            links[id] = link
            _pendingPairing.value?.let { pending ->
                if (pending.weInitiated) {
                    _pendingPairing.value = pending.copy(deviceId = id, deviceName = name)
                }
            }
        }
        link.deviceName = name
        link.peerCapabilities =
            Capability.fromNames(message.stringList("capabilities", 32, 32))
    }

    private fun handlePairRequest(link: Link, message: Message) {
        if (link.trusted) {
            link.connection.close("pair request on an already-paired link")
            return
        }
        if (!allowPairingAttempt(link)) {
            link.connection.close("pairing attempts rate-limited")
            return
        }

        val theirNonce = message.binary("nonce", Sas.NONCE_SIZE)
        val ourKey = keyStore.publicKey()
        val theirKey = link.connection.peerPublicKey
        if (theirNonce == null || ourKey == null || theirKey == null) {
            link.connection.close("malformed pairing nonce")
            return
        }

        val ourNonce = Sas.generateNonce()
        link.theirNonce = theirNonce
        link.ourNonce = ourNonce

        // We are the responder, so the initiator's key comes first.
        val code = Sas.derive(theirKey, ourKey, theirNonce, ourNonce)
        if (code == null) {
            link.connection.close("could not derive a verification code")
            return
        }

        scope.launch {
            link.connection.send(Message.pairResponse(link.connection.nextCounter(), ourNonce))
        }

        _pendingPairing.value = PendingPairing(
            deviceId = link.deviceId,
            deviceName = link.deviceName,
            peerPublicKey = theirKey,
            verificationCode = code,
            weInitiated = false,
        )
    }

    private fun handlePairResponse(link: Link, message: Message) {
        val pending = _pendingPairing.value
        if (pending == null) {
            Log.w(TAG, "pairResponse with no pairing in progress")
            return
        }
        if (!pending.weInitiated) {
            link.connection.close("unexpected pair response")
            return
        }

        val theirNonce = message.binary("nonce", Sas.NONCE_SIZE)
        val ourNonce = link.ourNonce
        val ourKey = keyStore.publicKey()
        val theirKey = link.connection.peerPublicKey
        if (theirNonce == null || ourNonce == null || ourKey == null || theirKey == null) {
            Log.w(
                TAG,
                "pairResponse missing inputs: theirNonce=${theirNonce != null} " +
                    "ourNonce=${ourNonce != null} ourKey=${ourKey != null} " +
                    "theirKey=${theirKey != null}",
            )
            link.connection.close("malformed pairing nonce")
            return
        }
        link.theirNonce = theirNonce

        // We initiated, so our key comes first.
        val code = Sas.derive(ourKey, theirKey, ourNonce, theirNonce)
        if (code == null) {
            link.connection.close("could not derive a verification code")
            return
        }

        Log.i(TAG, "verification code ready")
        _pendingPairing.value = pending.copy(
            verificationCode = code,
            peerPublicKey = theirKey,
            deviceName = link.deviceName,
        )
    }

    private fun handleData(link: Link, transferId: Long, chunk: ByteArray) {
        if (!allowed(link, Capability.FILE_TRANSFER)) {
            link.connection.close("data frame without file-transfer capability")
            return
        }
        // Data for a transfer the user never accepted. Refusing is the safe
        // answer: bytes arriving is not consent to write them.
        if (!receiver.has(transferId)) {
            link.connection.close("data for an unaccepted transfer")
            return
        }

        val error = receiver.appendChunk(transferId, chunk)
        if (error != null) {
            updateTransfer(transferId) { it.copy(done = true, error = error) }
            scope.launch {
                link.connection.send(
                    Message.fileCancel(link.connection.nextCounter(), transferId, error)
                )
            }
            return
        }
        receiver.progressOf(transferId)?.let { (received, total) ->
            updateTransfer(transferId) { it.copy(received = received, total = total) }
        }
    }


    // ---- System status ---------------------------------------------------

    /**
     * Ask a paired computer for a dashboard snapshot.
     *
     * Returns false when the capability is not usable — the computer does not
     * offer it, or the user has not enabled it for that device. Both ends
     * must agree, so a false here is an answer, not a failure.
     */
    fun requestStatus(targetDeviceId: String): Boolean {
        val link = links[targetDeviceId] ?: return false
        if (!allowed(link, Capability.SYSTEM_STATUS)) return false
        statusAwaiting.add(targetDeviceId)
        scope.launch {
            link.connection.send(Message.statusRequest(link.connection.nextCounter()))
        }
        return true
    }

    /** Forget the snapshot on screen, e.g. when leaving the dashboard. */
    /** Forget one device's snapshot, e.g. when the capability is switched
     *  off for it. Other devices' readings are untouched. */
    fun clearStatus(deviceId: String) {
        _systemStatus.remove(deviceId)
    }

    private fun handleCommandCatalog(link: Link, message: Message) {
        if (!allowed(link, Capability.COMMANDS)) return

        val array = message.unvalidatedArray("commands") ?: return
        val entries = ArrayList<RemoteCommand>(minOf(array.length(), MAX_COMMANDS))
        for (i in 0 until minOf(array.length(), MAX_COMMANDS)) {
            val row = array.opt(i) as? org.json.JSONObject ?: continue
            val id = row.optString("id").takeIf { it.isNotEmpty() && it.length <= MAX_COMMAND_ID_CHARS }
                ?: continue
            // Bounded and control-character-free before it can reach a
            // label on screen: the computer is authenticated, not trusted.
            if (id.any { it.code < 0x20 || it.code == 0x7F || it.code in 0x80..0x9F }) continue
            val label = row.optString("label").takeIf {
                it.isNotEmpty() && it.length <= MAX_COMMAND_LABEL_CHARS &&
                    it.none { c -> c.code < 0x20 || c.code == 0x7F || c.code in 0x80..0x9F }
            } ?: id
            entries.add(
                RemoteCommand(id, label, row.optBoolean("confirm", false), row.optBoolean("pinned", false))
            )
        }

        val previous = _commands.value[link.deviceId]
        _commands.put(
            link.deviceId,
            (previous ?: CommandsState(link.deviceId)).copy(
                commands = entries,
                // An empty catalogue is ambiguous on its own — no commands
                // defined, or the computer refusing to say. The reason it sends
                // when it refuses is what tells them apart.
                lastError = message.string("error", MAX_STATUS_ERROR_CHARS),
            ),
        )
    }

    private fun handleCommandResult(link: Link, message: Message) {
        if (!allowed(link, Capability.COMMANDS)) return

        val requestId = message.integer("requestId", Int.MAX_VALUE.toLong()) ?: return
        // Only a result for a run we started. Being paired is not a licence
        // to put output on someone's screen at a moment of your choosing.
        val (ownerDeviceId, expectedId) = commandRuns.remove(requestId) ?: run {
            Log.i(TAG, "ignoring unsolicited command result")
            return
        }
        // And only back on the link it was sent to — the request id space is
        // shared across every connected computer precisely so this check is
        // meaningful, not just so ids do not collide.
        if (ownerDeviceId != link.deviceId) {
            Log.w(TAG, "command result arrived on the wrong device's link")
            return
        }

        val state = _commands.value[link.deviceId] ?: CommandsState(link.deviceId)
        val error = message.string("error", MAX_STATUS_ERROR_CHARS)
        _commands.put(
            link.deviceId,
            state.copy(
                running = state.running - expectedId,
                lastId = expectedId,
                lastExitCode = message.signedInteger("exitCode", -1, 255)?.toInt(),
                lastOutput = message.string("output", MAX_COMMAND_OUTPUT_CHARS),
                lastError = error,
            ),
        )
    }

    private fun handleAiModelList(link: Link, message: Message) {
        if (!allowed(link, Capability.AI)) return

        val state = aiStateFor(link.deviceId)
        val error = message.string("error", MAX_STATUS_ERROR_CHARS)
        val models = message.stringList("models", MAX_AI_MODELS, MAX_AI_MODEL_CHARS)
        _ai.put(
            link.deviceId,
            state.copy(
                models = models,
                // Keep the choice if it survived the refresh; otherwise fall to
                // the first, so the chat is usable without picking anything.
                model = state.model?.takeIf { it in models } ?: models.firstOrNull(),
                error = error,
            ),
        )
    }

    private fun handleAiChunk(link: Link, message: Message) {
        if (!allowed(link, Capability.AI)) return
        val requestId = message.integer("requestId", Long.MAX_VALUE) ?: return
        // Only the reply we asked for, from that same device — a chunk for
        // anything else is either a stale stream or a peer pushing text at
        // the screen. Two computers can each have a request id in flight at
        // once, so this has to check *this* device's, not a single shared one.
        if (requestId != aiRequestIds[link.deviceId]) return

        val text = message.string("text", MAX_AI_CHUNK_CHARS) ?: return
        val state = _ai.value[link.deviceId] ?: return
        val turns = state.turns.toMutableList()
        if (turns.isEmpty() || turns.last().fromUser) {
            turns.add(AiTurn(fromUser = false, text = text))
        } else {
            // Capped as it accumulates: a stream that never ends must not be
            // able to grow this without bound.
            val grown = (turns.last().text + text).take(MAX_AI_ANSWER_CHARS)
            turns[turns.size - 1] = turns.last().copy(text = grown)
        }
        _ai.put(link.deviceId, state.copy(turns = turns, streaming = true))
    }

    private fun handleAiDone(link: Link, message: Message) {
        if (!allowed(link, Capability.AI)) return
        val requestId = message.integer("requestId", Long.MAX_VALUE) ?: return
        if (requestId != aiRequestIds[link.deviceId]) return
        aiRequestIds.remove(link.deviceId)

        val error = message.string("error", MAX_STATUS_ERROR_CHARS)
        val state = _ai.value[link.deviceId] ?: return
        val turns = state.turns.toMutableList()
        if (error != null) {
            // Said in the transcript, not only in a status line: an answer
            // bubble left empty forever reads as the model having nothing to
            // say, which is not what happened.
            if (turns.isNotEmpty() && !turns.last().fromUser) {
                turns[turns.size - 1] = AiTurn(fromUser = false, text = error, failed = true)
            } else {
                turns.add(AiTurn(fromUser = false, text = error, failed = true))
            }
        }
        _ai.put(link.deviceId, state.copy(turns = turns, streaming = false, error = null))
    }

    private fun aiStateFor(deviceId: String): AiState = _ai.value[deviceId] ?: AiState(deviceId)

    private fun handleGuardReport(link: Link, message: Message) {
        if (!allowed(link, Capability.GUARD_CONTROL)) return

        val array = message.unvalidatedArray("devices")
        val error = message.string("error", MAX_STATUS_ERROR_CHARS)
        val switches = ArrayList<GuardSwitch>(GUARD_DEVICES.size)
        if (array != null) {
            for (i in 0 until minOf(array.length(), GUARD_DEVICES.size)) {
                val row = array.opt(i) as? org.json.JSONObject ?: continue
                val device = row.optString("device")
                // Only devices maze-guard actually has. A name outside the
                // fixed set is not rendered as a switch, because pressing it
                // could not mean anything.
                if (device !in GUARD_DEVICES) continue
                val state = row.optString("state")
                if (state !in GUARD_STATES) continue
                switches.add(GuardSwitch(device, state))
            }
        }
        _guard.put(link.deviceId, GuardStateSnapshot(link.deviceId, switches, null, error))
    }

    private fun handleGuardResult(link: Link, message: Message) {
        if (!allowed(link, Capability.GUARD_CONTROL)) return

        val state = _guard.value[link.deviceId] ?: return
        val error = message.string("error", MAX_STATUS_ERROR_CHARS)
        _guard.put(link.deviceId, state.copy(pending = null, error = error))
        // Re-read rather than patch the row from the result: the computer is
        // the authority on what the hardware actually did.
        requestGuardStatus(link.deviceId)
    }

    // ---- Receiving files -------------------------------------------------

    private fun handleFileOffer(link: Link, message: Message) {
        if (!allowed(link, Capability.FILE_TRANSFER)) return

        val transferId = message.integer("transferId", 0xFFFFFFFFL) ?: return
        val filename = message.string("filename", Limits.MAX_FILENAME_CHARS) ?: return
        val size = message.integer("size", Limits.MAX_FILE_BYTES) ?: return

        val refusal = receiver.validateOffer(transferId, filename, size)
        if (refusal != null) {
            scope.launch {
                link.connection.send(
                    Message.fileReject(link.connection.nextCounter(), transferId, refusal)
                )
            }
            return
        }
        // One prompt at a time. A second offer arriving while the first is on
        // screen is refused rather than silently replacing it — otherwise a
        // sender could swap what the user is about to accept.
        if (_fileOffer.value != null) {
            scope.launch {
                link.connection.send(
                    Message.fileReject(
                        link.connection.nextCounter(), transferId,
                        "another file is already waiting for an answer",
                    )
                )
            }
            return
        }

        // Never auto-accepted. An incoming file always waits for the user,
        // exactly as on the desktop.
        _fileOffer.value = PendingFileOffer(
            deviceId = link.deviceId,
            deviceName = link.deviceName,
            transferId = transferId,
            filename = filename,
            sizeBytes = size,
        )
    }

    /** Answer the prompt. Refusing tells the sender, so it does not hang. */
    fun respondToFileOffer(accept: Boolean) {
        val offer = _fileOffer.value ?: return
        _fileOffer.value = null
        val link = links[offer.deviceId] ?: return

        if (!accept) {
            scope.launch {
                link.connection.send(
                    Message.fileReject(link.connection.nextCounter(), offer.transferId, "declined")
                )
            }
            return
        }

        val error = receiver.begin(offer.transferId, offer.filename, offer.sizeBytes)
        if (error != null) {
            scope.launch {
                link.connection.send(
                    Message.fileReject(link.connection.nextCounter(), offer.transferId, error)
                )
            }
            return
        }

        _transfers.value = _transfers.value + IncomingTransfer(
            transferId = offer.transferId,
            filename = offer.filename,
            received = 0,
            total = offer.sizeBytes,
        )
        scope.launch {
            link.connection.send(
                Message.fileAccept(link.connection.nextCounter(), offer.transferId)
            )
        }
    }

    private fun handleFileComplete(link: Link, message: Message) {
        if (!allowed(link, Capability.FILE_TRANSFER)) return
        val transferId = message.integer("transferId", 0xFFFFFFFFL) ?: return
        if (!receiver.has(transferId)) return

        receiver.finish(transferId).fold(
            onSuccess = { file ->
                updateTransfer(transferId) {
                    it.copy(
                        done = true,
                        received = it.total,
                        path = file.absolutePath,
                    )
                }
                emit(DeviceEvent.FileReceived(link.deviceId, file.name, file.absolutePath))
            },
            onFailure = { error ->
                val reason = error.message ?: "transfer failed"
                updateTransfer(transferId) { it.copy(done = true, error = reason) }
                emit(DeviceEvent.FileFailed(link.deviceId, reason))
            },
        )
    }

    private fun updateTransfer(transferId: Long, edit: (IncomingTransfer) -> IncomingTransfer) {
        _transfers.value = _transfers.value.map {
            if (it.transferId == transferId) edit(it) else it
        }
    }

    /** Forget finished rows. The files stay where they were written. */
    fun clearFinishedTransfers() {
        _transfers.value = _transfers.value.filterNot { it.done }
    }

    val inboxPath: String get() = receiver.root.absolutePath

    // ---- Sending files ---------------------------------------------------

    /**
     * Offer a file to a paired computer.
     *
     * Nothing is read from the [uri] until the far side accepts: an offer is
     * a filename and a size, and a computer that declines never causes this
     * phone to open the file at all.
     *
     * The name is sent as the content provider reports it. The computer
     * sanitises it on arrival — a peer-supplied filename is untrusted there
     * exactly as one is here.
     */
    fun sendFile(targetDeviceId: String, uri: android.net.Uri): Boolean {
        val link = links[targetDeviceId] ?: return false
        if (!allowed(link, Capability.FILE_TRANSFER)) return false

        val (name, size) = describe(uri) ?: return false
        if (size <= 0 || size > Limits.MAX_FILE_BYTES) return false

        val transferId = nextTransferId.getAndIncrement()
        outgoing[transferId] = OutgoingFile(targetDeviceId, uri, name, size)

        _transfers.value = _transfers.value + IncomingTransfer(
            transferId = transferId,
            filename = name,
            received = 0,
            total = size,
            outgoing = true,
        )
        scope.launch {
            link.connection.send(
                Message.fileOffer(link.connection.nextCounter(), transferId, name, size)
            )
        }
        return true
    }

    /** Name and size from the content provider, or null if it will not say. */
    private fun describe(uri: android.net.Uri): Pair<String, Long>? {
        val resolver = appContext.contentResolver
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use
            val nameCol = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            val name = if (nameCol >= 0) cursor.getString(nameCol) else null
            val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else -1L
            if (!name.isNullOrEmpty() && size >= 0) return name to size
        }
        return null
    }

    private fun handleFileAccept(link: Link, message: Message) {
        if (!allowed(link, Capability.FILE_TRANSFER)) return
        val transferId = message.integer("transferId", 0xFFFFFFFFL) ?: return

        // Only a file *we* offered, to the device we offered it to. An accept
        // for anything else is a peer trying to make us read a file it named.
        val file = outgoing[transferId] ?: return
        if (file.deviceId != link.deviceId) return
        outgoing.remove(transferId)

        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                appContext.contentResolver.openInputStream(file.uri)?.use { stream ->
                    val buffer = ByteArray(SEND_CHUNK_BYTES)
                    var sent = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        if (!link.connection.sendData(transferId, buffer.copyOf(read))) {
                            return@use false
                        }
                        sent += read
                        updateTransfer(transferId) { it.copy(received = sent) }
                    }
                    true
                } ?: false
            }.getOrDefault(false)

            if (ok) {
                link.connection.send(
                    Message.fileComplete(link.connection.nextCounter(), transferId)
                )
                updateTransfer(transferId) { it.copy(done = true, received = it.total) }
            } else {
                link.connection.send(
                    Message.fileCancel(
                        link.connection.nextCounter(), transferId, "could not read the file"
                    )
                )
                updateTransfer(transferId) {
                    it.copy(done = true, error = "could not read the file")
                }
            }
        }
    }

    // ---- Staying connected -----------------------------------------------

    /**
     * Dial every paired computer we can see and are not already linked to.
     *
     * **The phone dials the computer, never the other way round.** The
     * computer is the stable end — fixed address, fixed port, always
     * listening — while this device changes networks, changes address, and in
     * the background may not be accepting connections at all. The desktop
     * client has the matching half of this rule and will not dial a mobile
     * peer.
     *
     * Cheap and idempotent, so it is safe to call from a timer, from a
     * beacon sighting, and from a network change.
     */
    fun reconnectPairedDevices() {
        for (seen in beacon.devices.value) {
            // Only computers, only ones already trusted, only ones not
            // already linked or being dialed.
            if (seen.deviceType == "mobile") continue
            if (store.forId(seen.deviceId) == null) continue
            if (links.containsKey(seen.deviceId)) continue
            if (!outboundPending.add(seen.deviceId)) continue
            connectToPaired(seen.address, seen.port, seen.deviceId)
        }
    }

    /**
     * Reconnect to a computer we have already paired with.
     *
     * Always a paired-mode connection: an unknown key fails here rather than
     * opening a pairing window. A reconnect must never be a place where trust
     * can be established.
     */
    private fun connectToPaired(address: InetAddress, port: Int, targetDeviceId: String) {
        scope.launch(Dispatchers.IO) {
            val result = runCatching { tls.connect(address, port, allowUnpaired = false) }
            val (socket, trustManager) = result.getOrElse {
                Log.i(TAG, "reconnect to $targetDeviceId failed: ${it.message}")
                outboundPending.remove(targetDeviceId)
                return@launch
            }
            val connection = Connection(socket, trustManager, scope)
            withContext(Dispatchers.Main) {
                val adopted = adopt(connection, targetDeviceId)
                outboundPending.remove(targetDeviceId)
                if (!adopted) connection.close("could not start the link")
            }
        }
    }

    /**
     * Drop everything we were waiting on from one device.
     *
     * Called when a link closes. Each of these sets exists to refuse answers
     * nobody asked for; an entry that outlives its link inverts that, and
     * makes the first genuine answer after a reconnect look unsolicited.
     */
    private fun forgetPendingWork(deviceId: String) {
        statusAwaiting.remove(deviceId)
        // Only this device's entries — commandRuns and aiRequestIds are
        // shared across every connected computer, and clearing the whole
        // map here used to wipe another, still-connected computer's
        // in-flight command or AI stream out from under it.
        commandRuns.entries.removeIf { it.value.first == deviceId }
        aiRequestIds.remove(deviceId)
        _ai.value[deviceId]?.let { _ai.put(deviceId, it.copy(streaming = false)) }
        _commands.value[deviceId]?.let { _commands.put(deviceId, it.copy(running = emptySet())) }
        _guard.value[deviceId]?.let { _guard.put(deviceId, it.copy(pending = null)) }
    }

    // ---- maze-guard ------------------------------------------------------

    fun requestGuardStatus(targetDeviceId: String): Boolean {
        val link = links[targetDeviceId] ?: return false
        if (!allowed(link, Capability.GUARD_CONTROL)) return false
        if (targetDeviceId !in _guard.value) {
            _guard.put(targetDeviceId, GuardStateSnapshot(targetDeviceId))
        }
        scope.launch {
            link.connection.send(Message.guardStatus(link.connection.nextCounter()))
        }
        return true
    }

    /**
     * Ask the computer to allow or block one device.
     *
     * [enabled] is the **device's** state, matching maze-guard and `rfkill`:
     * `true` means the device works, `false` means it is blocked. Read as
     * "protection on/off" it inverts, and it did — Block used to unblock.
     *
     * [device] must be one of the fixed names; anything else is refused here
     * as well as there. There is no verb to send — PANIC and RESTORE have no
     * representation in this protocol at all.
     */
    fun setGuardKill(targetDeviceId: String, device: String, enabled: Boolean): Boolean {
        if (device !in GUARD_DEVICES) return false
        val link = links[targetDeviceId] ?: return false
        if (!allowed(link, Capability.GUARD_CONTROL)) return false

        val state = _guard.value[targetDeviceId] ?: return false
        if (state.pending != null) return false

        _guard.put(targetDeviceId, state.copy(pending = device, error = null))
        scope.launch {
            link.connection.send(
                Message.guardRequest(link.connection.nextCounter(), device, enabled)
            )
        }
        return true
    }

    fun clearGuard(deviceId: String) {
        _guard.remove(deviceId)
    }

    /**
     * Toggle one device on whichever paired computer is reachable.
     *
     * For the home-screen widget, which has no notion of *which* computer —
     * it is a button, not a device list. Returns false when nothing is
     * reachable, so the caller can say so rather than appear to have worked.
     */
    fun toggleGuardOnAnyComputer(device: String, enabled: Boolean): Boolean {
        val target = _connectedIds.value.firstOrNull { id ->
            links[id]?.let { allowed(it, Capability.GUARD_CONTROL) } == true
        } ?: return false
        // The state must be re-read afterwards anyway, so ask for a status
        // as well: the widget draws from the store, and the store is only
        // written when a report arrives.
        val sent = setGuardKill(target, device, enabled)
        if (sent) requestGuardStatus(target)
        return sent
    }

    // ---- Maze AI ---------------------------------------------------------

    fun requestAiModels(targetDeviceId: String): Boolean {
        val link = links[targetDeviceId] ?: return false
        if (!allowed(link, Capability.AI)) return false
        _ai.put(targetDeviceId, aiStateFor(targetDeviceId))
        scope.launch {
            link.connection.send(Message.aiModels(link.connection.nextCounter()))
        }
        return true
    }

    fun sendAiPrompt(targetDeviceId: String, text: String): Boolean {
        val link = links[targetDeviceId] ?: return false
        if (!allowed(link, Capability.AI)) return false

        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_AI_PROMPT_CHARS) return false

        val state = _ai.value[targetDeviceId] ?: return false
        val model = state.model ?: return false
        if (state.streaming) return false

        val requestId = nextAiRequestId.getAndIncrement()
        aiRequestIds[targetDeviceId] = requestId
        _ai.put(
            targetDeviceId,
            state.copy(
                turns = state.turns + AiTurn(fromUser = true, text = trimmed),
                streaming = true,
                error = null,
            ),
        )
        scope.launch {
            link.connection.send(
                Message.aiPrompt(link.connection.nextCounter(), requestId, model, trimmed)
            )
        }
        return true
    }

    fun setAiModel(targetDeviceId: String, model: String) {
        val state = _ai.value[targetDeviceId] ?: return
        if (model !in state.models) return
        _ai.put(targetDeviceId, state.copy(model = model))
    }

    /** Forget the transcript on screen for one device. The computer keeps
     *  its own history; this only clears what is displayed here. */
    fun clearAiTranscript(deviceId: String) {
        aiRequestIds.remove(deviceId)
        _ai.value[deviceId]?.let { _ai.put(deviceId, it.copy(turns = emptyList(), streaming = false)) }
    }

    // ---- Commands --------------------------------------------------------

    /** Ask a computer for its allow-list. */
    fun requestCommands(targetDeviceId: String): Boolean {
        val link = links[targetDeviceId] ?: return false
        if (!allowed(link, Capability.COMMANDS)) return false
        if (targetDeviceId !in _commands.value) {
            _commands.put(targetDeviceId, CommandsState(targetDeviceId))
        }
        scope.launch {
            link.connection.send(Message.commandList(link.connection.nextCounter()))
        }
        return true
    }

    /**
     * Ask a computer to run one entry of its own list.
     *
     * [commandId] is sent as-is and matched there. Nothing is composed here:
     * this phone has no argv to offer and no way to supply one.
     */
    fun runCommand(targetDeviceId: String, commandId: String): Boolean {
        val link = links[targetDeviceId] ?: return false
        if (!allowed(link, Capability.COMMANDS)) return false

        val state = _commands.value[targetDeviceId] ?: return false
        // Only ids the computer actually offered. A phone that has never seen
        // the list has nothing to ask for, and this keeps the id space on
        // screen honest even before the far end checks it again.
        if (state.commands.none { it.id == commandId }) return false

        val requestId = nextCommandRequestId.getAndIncrement()
        commandRuns[requestId] = targetDeviceId to commandId
        _commands.put(targetDeviceId, state.copy(running = state.running + commandId, lastError = null))
        scope.launch {
            link.connection.send(
                Message.commandRun(link.connection.nextCounter(), requestId, commandId)
            )
        }
        return true
    }

    fun clearCommands(deviceId: String) {
        _commands.remove(deviceId)
        commandRuns.entries.removeIf { it.value.first == deviceId }
    }

    // ---- Capabilities ----------------------------------------------------

    fun setCapabilityEnabled(targetDeviceId: String, capability: Capability, enabled: Boolean) {
        val device = store.forId(targetDeviceId) ?: return
        val caps = device.enabledCapabilities.toMutableSet()
        if (enabled) caps.add(capability) else caps.remove(capability)
        store.update(device.publicKey, device.deviceName, caps)
        publishDevices()
    }

    /**
     * Both peers must advertise it, and the local user must have enabled it
     * for this device. An advertisement alone grants nothing.
     */
    /**
     * Whether a connected device may use a capability, by id.
     *
     * Exists for callers outside a request path — the widget's confirmation
     * waits for a computer that will actually *take* a guard request, rather
     * than sending to the first connected link and being refused.
     */
    fun allows(deviceId: String, capability: Capability): Boolean {
        val link = links[deviceId] ?: return false
        return allowed(link, capability)
    }

    private fun allowed(link: Link, capability: Capability): Boolean {
        if (!link.trusted) return false
        if (capability !in link.peerCapabilities) return false
        val device = link.connection.peerPublicKey?.let { store.forKey(it) } ?: return false
        return capability in device.enabledCapabilities
    }

    private fun allowPairingAttempt(link: Link): Boolean {
        val key = link.connection.peerPublicKey?.let { Fingerprint.hexOf(it) } ?: return false
        val now = System.currentTimeMillis()
        val attempts = pairingAttempts.getOrPut(key) { mutableListOf() }
        attempts.removeAll { now - it > 60_000 }
        if (attempts.size >= Limits.PAIRING_ATTEMPTS_PER_MINUTE) return false
        attempts.add(now)
        return true
    }

    // ---- Helpers ---------------------------------------------------------

    private fun loadOrCreateDeviceId(): String {
        val file = File(appContext.filesDir, DEVICE_ID_FILE)
        if (file.exists()) {
            val stored = runCatching { file.readText().trim() }.getOrNull()
            if (!stored.isNullOrEmpty()) return stored
        }
        val fresh = UUID.randomUUID().toString()
        runCatching { file.writeText(fresh) }
        return fresh
    }

    private fun publishDevices() {
        _pairedDevices.value = store.all()
    }

    private fun markConnected(id: String, connected: Boolean) {
        _connectedIds.value = _connectedIds.value.toMutableSet().apply {
            if (connected) add(id) else remove(id)
        }
    }

    private fun emit(event: DeviceEvent) {
        scope.launch { _events.emit(event) }
    }

    companion object {
        private const val TAG = "MazeDeviceManager"
        private const val DEVICE_ID_FILE = "device-id"

        /** Bound on the reason string in a statusReport, before display. */
        private const val MAX_STATUS_ERROR_CHARS = 200

        // Bounds on a catalogue, applied before any of it reaches the screen.
        // Mirrors the desktop's CommandRunner caps; a computer sending more
        // than this is not one whose list we can render sensibly anyway.
        private const val MAX_COMMANDS = 64
        private const val MAX_COMMAND_ID_CHARS = 64
        private const val MAX_COMMAND_LABEL_CHARS = 64
        private const val MAX_COMMAND_OUTPUT_CHARS = 16 * 1024

        // Bounds on everything the AI path can put on screen.
        private const val MAX_AI_MODELS = 64
        private const val MAX_AI_MODEL_CHARS = 128
        private const val MAX_AI_PROMPT_CHARS = 8000
        private const val MAX_AI_CHUNK_CHARS = 4096
        private const val MAX_AI_ANSWER_CHARS = 32000

        /** Sweep interval. Short enough that a missed beacon costs seconds
         *  rather than minutes; long enough not to matter to the battery,
         *  since a sweep with nothing to do makes no syscalls. */
        private const val RECONNECT_INTERVAL_MS = 10_000L

        /** How often to read the computer for the widget's benefit. Slow on
         *  purpose — the widget is glanced at, not watched. */
        private const val BACKGROUND_STATUS_INTERVAL_MS = 60_000L

        /** Comfortably under the data-frame cap once the 4-byte transfer id
         *  is added. Matches the desktop's kSendChunkSize. */
        private const val SEND_CHUNK_BYTES = 128 * 1024

        /** maze-guard's own device set, mirrored so a name outside it is
         *  never rendered as something pressable. */
        private val GUARD_DEVICES =
            setOf("camera", "microphone", "bluetooth", "wifi", "usb")
        private val GUARD_STATES = setOf("on", "off", "none", "unknown")

        private val PAIRING_MESSAGES = setOf(
            MessageType.HELLO,
            MessageType.PAIR_REQUEST,
            MessageType.PAIR_RESPONSE,
            MessageType.PAIR_RESULT,
        )
    }
}

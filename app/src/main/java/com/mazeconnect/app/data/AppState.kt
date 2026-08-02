package com.mazeconnect.app.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mazeconnect.core.DeviceEvent
import com.mazeconnect.core.DeviceManager
import com.mazeconnect.core.AiState
import com.mazeconnect.core.CommandsState
import com.mazeconnect.core.IncomingTransfer
import com.mazeconnect.core.PendingFileOffer
import com.mazeconnect.core.GuardStateSnapshot
import com.mazeconnect.core.SystemStatusState
import com.mazeconnect.core.crypto.Fingerprint
import com.mazeconnect.core.protocol.Capability
import com.mazeconnect.core.discovery.DiscoveredDevice
import com.mazeconnect.app.service.Link
import com.mazeconnect.app.service.MazeConnectService
import com.mazeconnect.app.widget.CommandsWidget
import com.mazeconnect.app.widget.ControlsWidget
import com.mazeconnect.app.widget.DashboardWidget
import com.mazeconnect.app.widget.WidgetSnapshotStore
import com.mazeconnect.core.pairing.PairedDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A device row as the UI sees it — paired or merely discovered. */
data class DeviceRow(
    val deviceId: String,
    val name: String,
    val deviceType: String,
    val paired: Boolean,
    val connected: Boolean,
    val fingerprint: String,
    val address: String,
    /** Wire names of the capabilities enabled for this device, for the
     *  toggles and for deciding what may be asked of it. */
    val capabilities: Set<String> = emptySet(),
)

/**
 * UI-facing state, backed by the real [DeviceManager].
 *
 * Deliberately narrow: the UI can list devices, start and answer a pairing,
 * and read its own fingerprint. It cannot reach key material and cannot mark
 * a device trusted — pinning only ever happens inside DeviceManager, after
 * the user answers the verification prompt.
 */
class AppState(application: Application) : AndroidViewModel(application) {

    // Borrowed, not owned. The link belongs to the process (see Link), so it
    // survives this screen being destroyed and recreated.
    private val manager = Link.manager(application)

    /** Paired devices first — a trusted device should not vanish from the
     *  list just because it is asleep — then unpaired ones seen on the LAN. */
    val devices: StateFlow<List<DeviceRow>> =
        combine(
            manager.pairedDevices,
            manager.discovered,
            manager.connectedIds,
        ) { paired, discovered, connected ->
            val pairedRows = paired.map { it.toRow(connected.contains(it.deviceId)) }
            val discoveredRows = discovered
                .filter { seen -> pairedRows.none { it.deviceId == seen.deviceId } }
                .map { it.toRow() }
            pairedRows + discoveredRows
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pendingPairing: StateFlow<com.mazeconnect.core.PendingPairing?> = manager.pendingPairing

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _fingerprint = MutableStateFlow("")
    val fingerprint: StateFlow<String> = _fingerprint.asStateFlow()

    /**
     * Which paired-and-connected computer the UI is currently looking at.
     *
     * Falls back automatically: nothing chosen yet, or the chosen one went
     * away, picks the first paired+connected device — today's behaviour,
     * for the common case of exactly one. Every screen and the switcher
     * read this same flow, so they always agree on which computer is
     * "current".
     */
    private val _selectedDeviceId = MutableStateFlow<String?>(null)
    val selectedDeviceId: StateFlow<String?> =
        combine(_selectedDeviceId, devices) { selected, list ->
            selected?.takeIf { id -> list.any { it.deviceId == id && it.connected } }
                ?: list.firstOrNull { it.paired && it.connected }?.deviceId
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun selectDevice(deviceId: String) {
        _selectedDeviceId.value = deviceId
    }

    private var started = false

    fun start(deviceName: String) {
        if (started) return
        started = true

        if (!Link.ensureStarted(getApplication(), deviceName)) {
            _status.value = "Could not start. Secure storage is unavailable on this device."
            return
        }
        _fingerprint.value = manager.fingerprint
        // The notification listener runs as its own system-bound service and
        // needs a way to reach the live manager.
        MazeConnectService.attachManager(manager)
        // Foreground service so the link survives the app leaving the
        // foreground — and so the user can see and stop it.
        MazeConnectService.start(getApplication())

        viewModelScope.launch {
            manager.events.collect { event -> _status.value = event.describe() }
        }
        followSnapshotsForWidget()
    }

    /**
     * Keep every placed widget in step with whichever computer it is
     * configured for.
     *
     * A widget cannot reach the link — it is drawn by the launcher — so
     * every snapshot the app receives is written down for it. Every entry in
     * the map is saved on each change, not just the currently-selected
     * device's: a widget configured for the *other* computer needs its data
     * too, regardless of which tab is open right now.
     */
    private fun followSnapshotsForWidget() {
        val store = WidgetSnapshotStore(getApplication())
        viewModelScope.launch {
            manager.systemStatus.collect { map ->
                if (map.isEmpty()) return@collect
                for ((deviceId, state) in map) store.save(deviceId, state.status ?: continue)
                DashboardWidget.refresh(getApplication())
            }
        }
        viewModelScope.launch {
            // The controls widget needs the killswitch states written down for
            // the same reason: it is drawn by the launcher and cannot ask.
            manager.guard.collect { map ->
                if (map.isEmpty()) return@collect
                for ((deviceId, state) in map) {
                    if (state.switches.isEmpty()) continue
                    store.saveGuard(deviceId, state.switches.associate { it.device to it.state })
                }
                ControlsWidget.refresh(getApplication())
            }
        }
        viewModelScope.launch {
            // Same reasoning again: the Commands widget draws whichever
            // entries the computer last marked pinned, and cannot ask for
            // them itself.
            manager.commands.collect { map ->
                if (map.isEmpty()) return@collect
                for ((deviceId, state) in map) store.savePinnedCommands(deviceId, state.commands)
                CommandsWidget.refresh(getApplication())
            }
        }
    }

    fun requestPairing(deviceId: String) {
        val target = manager.discovered.value.firstOrNull { it.deviceId == deviceId }
        if (target == null) {
            _status.value = "That device is no longer visible on this network."
            return
        }
        manager.requestPairingAt(target.address, target.port, deviceId)
    }

    /**
     * Pair with a device typed in by hand.
     *
     * Needed wherever multicast discovery is filtered — plenty of corporate
     * and guest networks drop it. The address only decides who we dial; the
     * verification code is still what authorises, so typing an address grants
     * no more trust than picking a discovered device does.
     */
    fun requestPairingAtAddress(input: String) {
        val trimmed = input.trim()
        val host = trimmed.substringBeforeLast(':', trimmed)
        val port = trimmed.substringAfterLast(':', "").toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535) {
            _status.value = "Enter an address as host:port, for example 192.168.1.20:41234."
            return
        }
        viewModelScope.launch {
            val resolved = runCatching {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    java.net.InetAddress.getByName(host)
                }
            }.getOrNull()
            if (resolved == null) {
                _status.value = "Could not reach $host."
                return@launch
            }
            manager.requestPairingAt(resolved, port, "manual-$host:$port")
        }
    }

    fun respondToPairing(accept: Boolean) = manager.respondToPairing(accept)

    // ---- Dashboard -------------------------------------------------------

    val systemStatus: StateFlow<SystemStatusState?> =
        combine(manager.systemStatus, selectedDeviceId) { map, id -> id?.let(map::get) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Ask a computer for a snapshot.
     *
     * Silent when the capability is not usable: the dashboard already tells
     * the user which of the three conditions is missing, and repeating it in
     * the status line on every poll would be noise.
     */
    fun refreshSystemStatus(deviceId: String) {
        manager.requestStatus(deviceId)
    }

    // ---- Commands --------------------------------------------------------

    val commands: StateFlow<CommandsState?> =
        combine(manager.commands, selectedDeviceId) { map, id -> id?.let(map::get) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun refreshCommands(deviceId: String) {
        manager.requestCommands(deviceId)
    }

    fun runCommand(deviceId: String, commandId: String) {
        if (!manager.runCommand(deviceId, commandId)) {
            _status.value = "That command is not available right now."
        }
    }

    // ---- Maze AI ---------------------------------------------------------

    val ai: StateFlow<AiState?> =
        combine(manager.ai, selectedDeviceId) { map, id -> id?.let(map::get) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun refreshAiModels(deviceId: String) {
        manager.requestAiModels(deviceId)
    }

    fun sendAiPrompt(deviceId: String, text: String) {
        if (!manager.sendAiPrompt(deviceId, text)) {
            _status.value = "Could not send that — pick a model, or wait for the current reply."
        }
    }

    fun pickAiModel(deviceId: String, model: String) = manager.setAiModel(deviceId, model)

    fun clearAiTranscript() {
        selectedDeviceId.value?.let { manager.clearAiTranscript(it) }
    }

    // ---- Receiving files -------------------------------------------------

    val fileOffer: StateFlow<PendingFileOffer?> = manager.fileOffer
    val transfers: StateFlow<List<IncomingTransfer>> = manager.transfers
    val inboxPath: String get() = manager.inboxPath

    fun respondToFileOffer(accept: Boolean) = manager.respondToFileOffer(accept)

    /**
     * Offer a file to the first reachable paired computer.
     *
     * Nothing is read from the picked document until the computer accepts, so
     * declining costs this phone nothing.
     */
    fun sendFile(uri: android.net.Uri) {
        val target = devices.value.firstOrNull { it.paired && it.connected }
        if (target == null) {
            _status.value = "No paired computer is reachable right now."
            return
        }
        if (!manager.sendFile(target.deviceId, uri)) {
            _status.value = "Could not send that file."
        }
    }
    fun clearFinishedTransfers() = manager.clearFinishedTransfers()

    // ---- maze-guard ------------------------------------------------------

    val guard: StateFlow<GuardStateSnapshot?> =
        combine(manager.guard, selectedDeviceId) { map, id -> id?.let(map::get) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun refreshGuard(deviceId: String) {
        manager.requestGuardStatus(deviceId)
    }

    /** [enabled] is the device's state: true works, false is blocked. */
    fun setGuardKill(deviceId: String, device: String, enabled: Boolean) {
        if (!manager.setGuardKill(deviceId, device, enabled)) {
            _status.value = "Could not change that right now."
        }
    }

    fun setCapability(deviceId: String, capability: String, enabled: Boolean) {
        val cap = Capability.from(capability) ?: return
        manager.setCapabilityEnabled(deviceId, cap, enabled)
        // Nothing may be read from — or run on — a computer we just stopped
        // allowing, and a panel still showing its last answer would suggest
        // otherwise.
        if (!enabled) {
            when (cap) {
                Capability.SYSTEM_STATUS -> {
                    manager.clearStatus(deviceId)
                    // A widget still showing a machine the user just cut off
                    // would be reporting something they revoked.
                    WidgetSnapshotStore(getApplication()).clear(deviceId)
                    DashboardWidget.refresh(getApplication())
                }
                Capability.COMMANDS -> {
                    manager.clearCommands(deviceId)
                    // clearCommands() removes the device's entry, which the
                    // widget-following collector above treats as "nothing
                    // changed" and skips — so the pinned set has to be
                    // cleared here explicitly, same as status/guard above.
                    WidgetSnapshotStore(getApplication()).savePinnedCommands(deviceId, emptyList())
                    CommandsWidget.refresh(getApplication())
                }
                Capability.AI -> manager.clearAiTranscript(deviceId)
                Capability.GUARD_CONTROL -> {
                    manager.clearGuard(deviceId)
                    WidgetSnapshotStore(getApplication()).clear(deviceId)
                    ControlsWidget.refresh(getApplication())
                }
                else -> {}
            }
        }
    }

    fun unpair(deviceId: String) {
        manager.unpair(deviceId)
        WidgetSnapshotStore(getApplication()).clear(deviceId)
        DashboardWidget.refresh(getApplication())
        ControlsWidget.refresh(getApplication())
        CommandsWidget.refresh(getApplication())
    }

    /** Manual escape hatch for a paired device stuck showing "not reachable". */
    fun reconnect(deviceId: String) {
        manager.forceReconnect(deviceId)
    }

    fun displayFingerprint(hex: String): String = Fingerprint.display(hex)

    override fun onCleared() {
        // Nothing is torn down here, and that is the point. This runs when the
        // screen goes away — which used to stop the service and the manager,
        // killing the link, the beacon and the listening socket while the
        // notification stayed up claiming otherwise. The link outlives the UI;
        // stopping it is the service's job, and the user's decision.
        super.onCleared()
    }

    /** Phrased for the person reading it, not for the log. */
    private fun DeviceEvent.describe(): String = when (this) {
        is DeviceEvent.PairingFailed -> "Pairing failed: $reason"
        is DeviceEvent.PairingCompleted ->
            if (accepted) "Device paired." else "Pairing cancelled."
        is DeviceEvent.SecurityAlert -> "$summary — $detail"
        is DeviceEvent.FileReceived -> "Received $filename"
        is DeviceEvent.FileFailed -> "Transfer failed: $reason"
        // Kept generic rather than showing the text itself: a transient
        // status line is not the place for clipboard content, and the
        // notification MazeConnectService posts is where it's actually read.
        is DeviceEvent.OpenOnPhone -> "Your computer sent you something to open."
    }

    private fun PairedDevice.toRow(connected: Boolean) = DeviceRow(
        deviceId = deviceId,
        name = deviceName,
        deviceType = deviceType,
        paired = true,
        connected = connected,
        fingerprint = fingerprint,
        address = "",
        capabilities = enabledCapabilities.map { it.wire }.toSet(),
    )

    private fun DiscoveredDevice.toRow() = DeviceRow(
        deviceId = deviceId,
        name = deviceName,
        deviceType = deviceType,
        paired = false,
        connected = false,
        fingerprint = "",
        address = "${address.hostAddress}:$port",
    )
}

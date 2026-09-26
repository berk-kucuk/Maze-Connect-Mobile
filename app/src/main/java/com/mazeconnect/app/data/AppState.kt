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
import com.mazeconnect.core.protocol.MediaAction
import com.mazeconnect.core.protocol.MediaState
import com.mazeconnect.core.discovery.DiscoveredDevice
import com.mazeconnect.app.service.Link
import com.mazeconnect.app.service.LiveStatusNotification
import com.mazeconnect.app.service.MazeConnectService
import com.mazeconnect.app.update.UpdateChecker
import com.mazeconnect.app.update.UpdateStatus
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
        checkForUpdateIfDue()
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

    /**
     * Put [text] on a computer's clipboard: the one on screen if it takes
     * text, otherwise the first that does. Every refusal is said, because a
     * clipboard send has nothing else to show for itself.
     */
    fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            _status.value = "The clipboard is empty."
            return
        }
        if (trimmed.length > com.mazeconnect.core.Limits.MAX_SHARE_TEXT_CHARS) {
            _status.value = "That is too long to send — the limit is " +
                "${com.mazeconnect.core.Limits.MAX_SHARE_TEXT_CHARS} characters."
            return
        }
        val selected = selectedDeviceId.value
        val target = selected?.takeIf { manager.allows(it, Capability.SHARE_TEXT) }
            ?: manager.connectedIds.value.firstOrNull { manager.allows(it, Capability.SHARE_TEXT) }
        if (target == null) {
            _status.value = if (manager.connectedIds.value.isEmpty()) {
                "No paired computer is reachable right now."
            } else {
                "The computer's Maze Connect is too old to take text — update it to 1.3.0."
            }
            return
        }
        _status.value = if (manager.shareText(target, trimmed)) {
            "Sent to the computer's clipboard."
        } else {
            "That text cannot be sent — it contains control characters."
        }
    }

    // ---- This phone ------------------------------------------------------

    var shareStatusEnabled: Boolean
        get() = com.mazeconnect.app.service.PhonePrefs.shareStatus(getApplication())
        set(value) = com.mazeconnect.app.service.PhonePrefs.setShareStatus(getApplication(), value, manager)

    var allowRingEnabled: Boolean
        get() = com.mazeconnect.app.service.PhonePrefs.allowRing(getApplication())
        set(value) = com.mazeconnect.app.service.PhonePrefs.setAllowRing(getApplication(), value, manager)

    /** Ring once from Settings, so the owner knows what a computer's ring
     *  sounds like — and that it is loud enough — before the day they need it. */
    fun testRing() {
        com.mazeconnect.app.service.FindPhoneRinger.start(getApplication(), "Settings")
        _status.value = "Ringing — tap Found it in the notification to stop."
    }

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

    // ---- Media -----------------------------------------------------------

    val media: StateFlow<MediaState?> =
        combine(manager.media, selectedDeviceId) { map, id -> id?.let(map::get) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** The Media screen is showing [deviceId] (or nothing, with null). Held
     *  under the screen's own token, beside the notification's. */
    fun watchMedia(deviceId: String?) {
        manager.releaseMediaInterest(MEDIA_SCREEN_TOKEN)
        if (deviceId != null) manager.setMediaInterest(deviceId, MEDIA_SCREEN_TOKEN, true)
    }

    fun mediaCommand(deviceId: String, playerId: String, action: MediaAction, value: Long = 0) {
        if (!manager.sendMediaCommand(deviceId, playerId, action, value)) {
            _status.value = "That computer is not reachable right now."
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

    /** Re-run discovery. For the empty list, where there is nothing to
     *  press "reconnect" on. */
    fun rescan() {
        manager.rescan()
    }

    /**
     * The dashboard's Refresh button.
     *
     * Falls through to a rescan when the reading cannot be asked for, and
     * that fallback is the whole point. The dashboard already polls every
     * few seconds *while a computer is connected*, so the only time a person
     * reaches for Refresh is when it has gone stale — which is exactly when
     * there is no link to ask over and a plain status request would do
     * nothing at all. requestStatus() returns false in precisely those
     * cases (no link, or the capability switched off), so its answer is the
     * signal for what to do instead.
     */
    fun refreshDashboard() {
        val id = selectedDeviceId.value
        if (id == null || !manager.requestStatus(id)) manager.rescan()
    }

    fun displayFingerprint(hex: String): String = Fingerprint.display(hex)

    // ---- Now bar / live status -------------------------------------------

    val liveStatusSupported: Boolean get() = LiveStatusNotification.isSupported()

    /** What the system did with the last post — see PromotionState. */
    fun liveStatusPromotion(): LiveStatusNotification.PromotionState =
        LiveStatusNotification.promotionState(getApplication())

    var liveStatusEnabled: Boolean
        get() = LiveStatusNotification.isEnabled(getApplication())
        set(value) {
            LiveStatusNotification.setEnabled(getApplication(), value)
            // Applied now rather than at the next reading. The background
            // sweep is on a minute-long timer, and a switch that appears to
            // do nothing for most of a minute reads as a broken switch —
            // which is the exact complaint this whole screen keeps producing.
            if (value) {
                LiveStatusNotification.refresh(
                    getApplication(),
                    manager.systemStatus.value,
                    force = true,
                ) { deviceId ->
                    manager.pairedDevices.value
                        .firstOrNull { it.deviceId == deviceId }?.deviceName
                }
            }
        }

    // ---- Update reminder -------------------------------------------------

    private val _update = MutableStateFlow(UpdateStatus())
    val update: StateFlow<UpdateStatus> = _update.asStateFlow()

    /** False in the F-Droid build, where F-Droid delivers updates. */
    val updateCheckAvailable: Boolean get() = UpdateChecker.available

    val installedVersionCode: Long get() = UpdateChecker.installedVersionCode(getApplication())
    val installedVersionName: String get() = UpdateChecker.installedVersionName(getApplication())

    var updateCheckEnabled: Boolean
        get() = UpdateChecker.isEnabled(getApplication())
        set(value) {
            UpdateChecker.setEnabled(getApplication(), value)
            if (!value) _update.value = UpdateStatus()
        }

    /** The automatic look, at most daily. Silent about every outcome except
     *  a genuinely newer build — an unreachable LAN server is the normal
     *  case away from home, not something to report. */
    private fun checkForUpdateIfDue() {
        viewModelScope.launch {
            UpdateChecker.checkIfDue(getApplication())?.let { _update.value = it }
        }
    }

    /** The Settings button. Reports every outcome, including failure: here
     *  the user is looking at the result and silence would read as a hang. */
    fun checkForUpdateNow() {
        if (_update.value.checking) return
        _update.value = _update.value.copy(checking = true, error = null)
        viewModelScope.launch {
            _update.value = UpdateChecker.checkNow(getApplication())
        }
    }

    override fun onCleared() {
        // Nothing is torn down here, and that is the point. This runs when the
        // screen goes away — which used to stop the service and the manager,
        // killing the link, the beacon and the listening socket while the
        // notification stayed up claiming otherwise. The link outlives the UI;
        // stopping it is the service's job, and the user's decision.
        //
        // The one exception is interest the screen itself registered: the
        // Media screen is gone, so its subscription goes with it (the
        // notification holds its own, under a different token).
        manager.releaseMediaInterest(MEDIA_SCREEN_TOKEN)
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
        // The dialog this event also raises is the real surface; the status
        // line just explains why it appeared.
        is DeviceEvent.PairingRequested -> "$deviceName wants to pair."
        is DeviceEvent.FindPhone ->
            if (ring) "$deviceName is ringing this phone." else "Ringing stopped."
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

/** The Media screen's interest token — see DeviceManager.setMediaInterest. */
private const val MEDIA_SCREEN_TOKEN = "screen"

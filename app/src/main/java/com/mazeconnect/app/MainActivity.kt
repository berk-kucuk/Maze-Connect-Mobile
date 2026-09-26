package com.mazeconnect.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import android.os.Build
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mazeconnect.app.data.AppState
import com.mazeconnect.app.ui.components.MazeLabel
import com.mazeconnect.app.ui.screens.AiChatScreen
import com.mazeconnect.app.ui.screens.CommandsScreen
import com.mazeconnect.app.ui.screens.DashboardScreen
import com.mazeconnect.app.ui.screens.GuardScreen
import com.mazeconnect.app.ui.screens.MediaScreen
import com.mazeconnect.app.ui.screens.DevicesScreen
import com.mazeconnect.app.ui.screens.PairingDialog
import com.mazeconnect.app.ui.screens.SettingsScreen
import com.mazeconnect.app.ui.screens.TransfersScreen
import com.mazeconnect.app.ui.screens.FileOfferDialog
import com.mazeconnect.app.ui.theme.LocalMazeColors
import com.mazeconnect.app.ui.theme.MazeColors
import com.mazeconnect.app.ui.theme.MazeConnectTheme
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    /**
     * Asking is not optional on Android 13 and later.
     *
     * POST_NOTIFICATIONS has been declared in the manifest all along, but a
     * manifest entry alone grants nothing from API 33: until the user is
     * actually asked, *every* notification this app posts is dropped
     * silently. That covered the foreground-service notification — the one
     * disclosure that the app is holding a link — and it would have covered
     * the pairing request too, which is the one notification the user must
     * see for the computer's Pair button to lead anywhere while the app is
     * backgrounded.
     *
     * Nothing is gated on the answer. A refusal costs the user the
     * notifications and nothing else; the link, the widgets and the pairing
     * dialog all work exactly as before, so there is no reason to ask twice
     * or to explain first.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        // Tapping a widget configured for a particular computer should open
        // straight onto that computer's data, not whichever one the
        // in-app switcher last had selected.
        val openDeviceId = intent?.getStringExtra(EXTRA_OPEN_DEVICE_ID)

        setContent {
            // A ViewModel, not a remembered object: the link must survive a
            // configuration change rather than being torn down and rebuilt
            // every time the screen rotates.
            val state: AppState = viewModel()

            androidx.compose.runtime.LaunchedEffect(openDeviceId) {
                openDeviceId?.let(state::selectDevice)
            }

            MazeConnectTheme {
                MazeConnectApp(state)
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_DEVICE_ID = "openDeviceId"
    }
}

@Composable
private fun MazeConnectApp(state: AppState) {
    val devices by state.devices.collectAsState()
    val selectedDeviceId by state.selectedDeviceId.collectAsState()
    val pairing by state.pendingPairing.collectAsState()
    val update by state.update.collectAsState()
    // Mirrored into composition state because the preference itself is a
    // plain SharedPreferences read, which recomposition cannot observe.
    var updateCheckEnabled by remember { mutableStateOf(state.updateCheckEnabled) }
    var liveStatusEnabled by remember { mutableStateOf(state.liveStatusEnabled) }
    val status by state.status.collectAsState()
    val fingerprint by state.fingerprint.collectAsState()
    val systemStatus by state.systemStatus.collectAsState()
    val commands by state.commands.collectAsState()
    val ai by state.ai.collectAsState()
    val guard by state.guard.collectAsState()
    val media by state.media.collectAsState()
    val fileOffer by state.fileOffer.collectAsState()
    val transfers by state.transfers.collectAsState()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    val colors = LocalMazeColors.current

    // Read from the inset rather than a callback: it follows the keyboard's
    // animation, so the chrome leaves as the keyboard arrives instead of
    // snapping out a frame later.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    androidx.compose.runtime.LaunchedEffect(Unit) {
        state.start(Build.MODEL ?: "Android device")
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        // enableEdgeToEdge() draws behind the system bars, so the insets
        // have to be consumed here. Without this the masthead sits under
        // the status bar: it collides with the clock, and the tab targets
        // are partly unreachable because the system bar takes the touches.
        //
        // One padding, from the **union** of the bars, the cutout and the IME.
        // Both previous attempts got this wrong in opposite directions, and
        // each mistake is easy to make again:
        //
        //  * safeDrawing *plus* imePadding subtracted the keyboard twice, and
        //    the content jumped up by two keyboard heights.
        //  * Dropping the IME entirely and leaving it to `adjustResize` did
        //    nothing at all: under enableEdgeToEdge the window no longer fits
        //    the system windows, so adjustResize never shrinks it and the
        //    input box sat behind the keyboard.
        //
        // safeDrawing already folds the IME in with the bars — its bottom is
        // the *larger* of the navigation bar and the keyboard, not their sum —
        // so it is one modifier and it cannot double-count. Do not add
        // imePadding() anywhere beneath this.
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            // While the keyboard is up the column has perhaps a third of its
            // usual height, and the chrome would eat most of it — leaving the
            // transcript a sliver. The masthead is decoration, and the nav is
            // behind the keyboard anyway, so neither is worth the room.
            if (!imeVisible) Masthead(
                devices = devices,
                selectedDeviceId = selectedDeviceId,
                onSelectDevice = state::selectDevice,
            )

            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> DevicesScreen(
                        devices = devices,
                        displayFingerprint = state::displayFingerprint,
                        onPair = state::requestPairing,
                        onUnpair = state::unpair,
                        onReconnect = state::reconnect,
                        onPairByAddress = state::requestPairingAtAddress,
                        onScan = state::rescan,
                    )
                    1 -> DashboardScreen(
                        devices = devices,
                        selectedDeviceId = selectedDeviceId,
                        state = systemStatus,
                        onRefresh = state::refreshSystemStatus,
                        onManualRefresh = state::refreshDashboard,
                    )
                    2 -> MediaScreen(
                        devices = devices,
                        selectedDeviceId = selectedDeviceId,
                        state = media,
                        onWatch = state::watchMedia,
                        onCommand = state::mediaCommand,
                    )
                    3 -> CommandsScreen(
                        devices = devices,
                        selectedDeviceId = selectedDeviceId,
                        state = commands,
                        onRefresh = state::refreshCommands,
                        onRun = state::runCommand,
                    )
                    4 -> AiChatScreen(
                        devices = devices,
                        selectedDeviceId = selectedDeviceId,
                        state = ai,
                        onRefreshModels = state::refreshAiModels,
                        onSend = state::sendAiPrompt,
                        onPickModel = state::pickAiModel,
                        onClear = state::clearAiTranscript,
                    )
                    5 -> TransfersScreen(
                        transfers = transfers,
                        inboxPath = state.inboxPath,
                        onClearFinished = state::clearFinishedTransfers,
                        onSendFile = state::sendFile,
                    )
                    6 -> GuardScreen(
                        devices = devices,
                        selectedDeviceId = selectedDeviceId,
                        state = guard,
                        onRefresh = state::refreshGuard,
                        onSet = state::setGuardKill,
                    )
                    else -> SettingsScreen(
                        fingerprint = fingerprint,
                        displayFingerprint = state::displayFingerprint,
                        installedVersion = state.installedVersionName,
                        liveStatusSupported = state.liveStatusSupported,
                        liveStatusEnabled = liveStatusEnabled,
                        liveStatusPromotion = state::liveStatusPromotion,
                        onSetLiveStatusEnabled = {
                            state.liveStatusEnabled = it
                            liveStatusEnabled = it
                        },
                        update = update,
                        updateCheckEnabled = updateCheckEnabled,
                        onSetUpdateCheckEnabled = {
                            state.updateCheckEnabled = it
                            updateCheckEnabled = it
                        },
                        onCheckForUpdate = state::checkForUpdateNow,
                    )
                }
            }

            if (status.isNotEmpty() && !imeVisible) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.dim,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }

            if (!imeVisible) BottomNav(
                // "AI" rather than "Maze AI": you are already inside Maze Connect
                // and the masthead says MAZE, so the prefix bought nothing but
                // width in the one place width is scarce.
                items = listOf(
                    "Devices", "Dashboard", "Media", "Commands", "AI", "Files", "Guard", "Settings",
                ),
                selected = tab,
                onSelect = { tab = it },
            )
        }
    }

    // Only prompt once the code is actually derived: the pending pairing is
    // created before the peer's nonce arrives, and showing an empty readout
    // would invite the user to "confirm" nothing.
    fileOffer?.let { offer ->
        FileOfferDialog(
            offer = offer,
            onAccept = { state.respondToFileOffer(true) },
            onDecline = { state.respondToFileOffer(false) },
        )
    }

    pairing?.takeIf { it.verificationCode.isNotEmpty() }?.let {
        PairingDialog(
            deviceName = it.deviceName,
            weInitiated = it.weInitiated,
            verificationCode = it.verificationCode,
            answered = it.localAccepted,
            onAccept = { state.respondToPairing(true) },
            onReject = { state.respondToPairing(false) },
        )
    }
}

@Composable
private fun Masthead(
    devices: List<com.mazeconnect.app.data.DeviceRow> = emptyList(),
    selectedDeviceId: String? = null,
    onSelectDevice: (String) -> Unit = {},
) {
    val colors = LocalMazeColors.current
    val reachable = devices.filter { it.paired && it.connected }

    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "MAZE",
                style = MaterialTheme.typography.labelMedium,
                color = MazeColors.Paper,
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(1.dp).height(14.dp).background(colors.hairline))
            Spacer(Modifier.width(10.dp))
            Text(
                text = "CONNECT",
                style = MaterialTheme.typography.labelMedium,
                color = colors.dim,
            )
        }
        // Only shown once there is something to switch between — the common
        // single-computer case looks exactly as it always has.
        if (reachable.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 0.dp)
                    .padding(bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                reachable.forEachIndexed { index, device ->
                    if (index > 0) Spacer(Modifier.width(8.dp))
                    val active = device.deviceId == selectedDeviceId
                    Box(
                        Modifier
                            .clickable { onSelectDevice(device.deviceId) }
                            .background(if (active) colors.dim.copy(alpha = 0.16f) else Color.Transparent)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = device.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) MazeColors.Paper else colors.dim,
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
    }
}

/**
 * Navigation along the bottom.
 *
 * It started as tabs in the masthead, which held for two and broke for three
 * — "SETTINGS" wrapped on a 1080px screen. More to the point, the top of a
 * phone is the part of the screen a thumb cannot reach, and this app is now
 * something you open to *do* things rather than to read one page.
 *
 * The active item is marked by a rule above it and by weight, not by colour:
 * the palette has no accent, and an indicator that depended on hue would say
 * nothing on a monochrome surface.
 */
@Composable
private fun BottomNav(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = LocalMazeColors.current

    // Parallel to `items` by position. A drawn mark is quicker to find than a
    // word in tracked uppercase, and it is what lets the labels stay small.
    val icons = listOf(
        R.drawable.ic_devices,
        R.drawable.ic_dashboard,
        R.drawable.ic_media,
        R.drawable.ic_commands,
        R.drawable.ic_ai,
        R.drawable.ic_files,
        R.drawable.ic_guard,
        R.drawable.ic_settings,
    )

    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
        // Scrolls, rather than dividing the width into equal columns.
        //
        // Seven equal columns on a 1080px screen gave each label about 150px,
        // which is narrower than the tracked uppercase type needs: the labels
        // clipped to "DASHBOA" and "SETTING" and ran into each other with no
        // gap. Sizing each item to its own text and letting the row scroll
        // fixes both, and keeps working as more tabs arrive.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            items.forEachIndexed { index, title ->
                val active = selected == index
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable(indication = null, interactionSource = null) {
                            onSelect(index)
                        }
                        .padding(bottom = 12.dp),
                ) {
                    // The marker is always laid out, so switching tabs moves a
                    // rule rather than reflowing the row.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (active) MazeColors.Paper else Color.Transparent),
                    )
                    Spacer(Modifier.height(10.dp))
                    icons.getOrNull(index)?.let { icon ->
                        Icon(
                            painter = painterResource(icon),
                            contentDescription = null,
                            tint = if (active) MazeColors.Paper else colors.dim,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.height(5.dp))
                    }
                    MazeLabel(
                        title,
                        color = if (active) MazeColors.Paper else colors.dim,
                        maxLines = 1,
                        // The gap between labels lives here rather than in the
                        // Row's spacing, so the marker above sits under the
                        // text and not under the gap as well.
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }
}

package com.mazeconnect.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Log
import android.util.Patterns
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mazeconnect.app.MainActivity
import com.mazeconnect.app.R
import com.mazeconnect.core.DeviceEvent
import com.mazeconnect.core.DeviceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the LAN link alive while the app is in the background.
 *
 * A foreground service with a visible notification, not a background one:
 * an app holding a network link to another device should be something the
 * user can see and stop, not something running invisibly. The notification
 * is the honest disclosure of that, so it is deliberately not made
 * minimum-priority to hide it.
 */
class MazeConnectService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Only for watchOpenOnPhone()'s collection — the service itself has no
     *  built-in coroutine scope the way a ViewModel does. */
    private var serviceScope: CoroutineScope? = null
    private var eventsJob: Job? = null
    private var liveStatusJob: Job? = null
    private var mediaNotification: MediaNotification? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        createOpenOnPhoneChannel()
        createPairingChannel()
        acquireWifiLocks()
        watchNetwork()
    }

    /**
     * The system's "your time is up" for a timed foreground-service type.
     *
     * `connectedDevice` carries no timeout today, so this should never fire —
     * it is here because the previous `dataSync` type did carry one (six
     * hours in any 24 from Android 15), no override existed, and the platform
     * answers that silence by killing the process. That is precisely what a
     * user sees as the app crashing by itself in the background.
     *
     * If a future platform does put a clock on this type, the contract is to
     * stop promptly: the few seconds of grace are not enough to negotiate
     * anything, and an orderly stop the user can restart beats a kill they
     * cannot explain. Both arities are overridden because API 35 calls the
     * one-argument form and API 36+ the two-argument one.
     */
    override fun onTimeout(startId: Int) {
        stopBecauseOfTimeout()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopBecauseOfTimeout()
    }

    private fun stopBecauseOfTimeout() {
        Log.w(TAG, "foreground service timed out; stopping the link")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // The service being destroyed is the user actually stopping the link,
        // so this is where the manager goes down — not when a screen closes.
        Link.shutdown()
        attachManager(null)
        runCatching { multicastLock?.release() }
        runCatching { wifiLock?.release() }
        multicastLock = null
        wifiLock = null
        networkCallback?.let {
            runCatching {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            }
        }
        networkCallback = null
        LiveStatusNotification.clear(this)
        mediaNotification?.stop()
        mediaNotification = null
        serviceScope?.cancel()
        serviceScope = null
        eventsJob = null
        liveStatusJob = null
        super.onDestroy()
    }

    /**
     * Reconnect the moment the network comes back.
     *
     * The link does not survive Wi-Fi dropping, and the manager's own sweep
     * would eventually notice — but "eventually" is up to ten seconds, and
     * that is exactly the window in which someone picks the phone up to check
     * whether it reconnected. Finding a blank dashboard there is what makes
     * the app feel unreliable even when it is about to recover on its own.
     *
     * onAvailable also fires for a *different* network (Wi-Fi to mobile data,
     * or a new access point), which is when the multicast socket's binding
     * has gone stale and needs rebinding — see Beacon.refresh().
     */
    private fun watchNetwork() {
        val manager = runCatching {
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        }.getOrNull() ?: return

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                sharedManager?.onNetworkAvailable()
            }
        }
        // Only networks that actually carry traffic; a captive-portal or
        // no-internet transport would just make us dial into nothing.
        // Deliberately **not** filtered on NET_CAPABILITY_INTERNET. This is a
        // LAN application: the network that matters is the one the computer is
        // on, and a Wi-Fi network with no route to the internet — an isolated
        // router, a hotspot, a captive portal not yet signed into — is one this
        // app should work on. Requiring INTERNET meant not being told about
        // exactly those.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        runCatching { manager.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
    }

    /**
     * Discovery is multicast, and Android drops multicast frames before they
     * reach an app unless a lock is held — so without this the phone can miss
     * the computer's announcements entirely. The Wi-Fi lock is the other half:
     * it stops the radio being parked while the screen is off, which is exactly
     * when a widget tap needs the link to still be there.
     */
    private fun acquireWifiLocks() {
        val wifi = runCatching {
            applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        }.getOrNull() ?: return

        runCatching {
            wifi.createMulticastLock("maze-connect").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onSuccess { multicastLock = it }

        runCatching {
            wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "maze-connect").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onSuccess { wifiLock = it }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The type is stated here as well as in the manifest. On API 29+ the
        // two-argument overload takes whatever the manifest declared, which
        // works but leaves the caller silent about it; saying it out loud is
        // what makes a later manifest edit a compile-time concern rather than
        // a runtime surprise.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        // The Live Update's Refresh button lands here. Asking costs one small
        // message on an already-open link, and it is the difference between a
        // reading the user waits a minute for and one they can pull now.
        if (intent?.action == ACTION_REFRESH_STATUS) {
            // The answer arrives back through the throttled collector below,
            // so it has to be let through or the button swallows its own
            // result.
            LiveStatusNotification.forceNextUpdate()
            sharedManager?.let { manager ->
                manager.connectedIds.value.forEach { manager.requestStatus(it) }
            }
        }
        // Bring the link up from here too. A widget tap starts this service
        // with no Activity involved, and the link has to exist before the tap
        // can do anything with it.
        if (Link.ensureStarted(applicationContext)) {
            val manager = Link.manager(applicationContext)
            attachManager(manager)
            watchOpenOnPhone(manager)
            watchLiveStatus(manager)
            watchMedia(manager)
        }
        // START_STICKY: if the system reclaims us under pressure, the link
        // should come back rather than silently staying down.
        return START_STICKY
    }

    /**
     * Post a notification whenever the computer pushes something to open.
     *
     * Not opened directly: this runs while backgrounded as often as not,
     * and Android refuses to start an Activity from a background/service
     * context in that case — a direct startActivity() here would frequently
     * just silently fail. A notification tap is exempt from that
     * restriction, so the arrival posts one and the *tap* is what acts.
     *
     * Guarded against being wired up twice: onStartCommand() can run more
     * than once per process (a widget tap, a second launch) and this must
     * not end up collecting the same flow twice, doubling every notification.
     */
    private fun watchOpenOnPhone(manager: DeviceManager) {
        if (eventsJob != null) return
        val scope = serviceScope
            ?: CoroutineScope(SupervisorJob() + Dispatchers.Main).also { serviceScope = it }
        eventsJob = scope.launch {
            manager.events.collect { event ->
                when (event) {
                    is DeviceEvent.OpenOnPhone -> postOpenOnPhoneNotification(event.text)
                    is DeviceEvent.PairingRequested ->
                        postPairingNotification(event.deviceName)
                    // A pairing that ended, either way, retires its
                    // notification: leaving it up invites a tap that opens
                    // an app with no dialog in it.
                    is DeviceEvent.PairingCompleted, is DeviceEvent.PairingFailed ->
                        getSystemService(NotificationManager::class.java)
                            .cancel(PAIRING_NOTIFICATION_ID)
                    else -> {}
                }
            }
        }
    }

    /**
     * Keep the Live Update in step with the computer's readings.
     *
     * Driven from the service rather than a screen, and that is the point:
     * the reading is for the status bar and the lock screen, which are
     * exactly the places a person looks *instead of* opening the app. The
     * manager's background sweep already asks every connected computer for a
     * snapshot on a slow timer for the widgets' sake, so this costs no extra
     * traffic — it consumes what is already arriving.
     *
     * With more than one computer connected it follows the freshest reading
     * rather than publishing one notification per machine. Two permanent
     * entries in the shade for what a person thinks of as "my computer" is
     * worse than one that occasionally switches, and the title names which
     * machine it is showing.
     */
    private fun watchLiveStatus(manager: DeviceManager) {
        if (!LiveStatusNotification.isSupported()) return
        if (liveStatusJob != null) return
        val scope = serviceScope ?: return
        liveStatusJob = scope.launch {
            manager.systemStatus.collect { byDevice ->
                LiveStatusNotification.refresh(this@MazeConnectService, byDevice) { deviceId ->
                    manager.pairedDevices.value
                        .firstOrNull { it.deviceId == deviceId }?.deviceName
                }
            }
        }
    }

    /**
     * The computer's now-playing as a phone media control — lock screen,
     * quick settings, a paired watch. Owned by the service because those are
     * exactly the places someone looks without opening the app.
     */
    private fun watchMedia(manager: DeviceManager) {
        if (mediaNotification != null) return
        val scope = serviceScope ?: return
        mediaNotification = MediaNotification(this, manager).also { it.start(scope) }
    }

    private fun postOpenOnPhoneNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val isUrl = Patterns.WEB_URL.matcher(text).matches()

        val tap = if (isUrl) {
            // An Activity start from a notification tap — the one context
            // this restriction does not apply to.
            PendingIntent.getActivity(
                this, 0,
                Intent(Intent.ACTION_VIEW, Uri.parse(text)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            // A broadcast rather than an activity: nothing to view, just
            // text to put on the clipboard, and a broadcast carries no
            // background-start restriction to begin with.
            PendingIntent.getBroadcast(
                this, 0,
                Intent(this, OpenOnPhoneReceiver::class.java)
                    .putExtra(OpenOnPhoneReceiver.EXTRA_TEXT, text),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = NotificationCompat.Builder(this, OPEN_CHANNEL_ID)
            .setContentTitle(getString(R.string.open_on_phone_notification_title))
            .setContentText(
                if (isUrl) text else getString(R.string.open_on_phone_notification_text),
            )
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(OPEN_NOTIFICATION_ID, notification)
    }

    /**
     * Tell the user a computer is asking to pair.
     *
     * The verification dialog is an Activity, so a request arriving with the
     * app backgrounded or the phone locked used to produce nothing at all on
     * this end while the computer sat waiting — which reads, correctly, as
     * "the phone is not responding".
     *
     * HIGH importance, unlike the other two channels here: this is a
     * time-limited security decision the user has to make, and one they did
     * not ask for at this moment. It is also the only notification in the
     * app that is worth interrupting for.
     *
     * The code itself is deliberately not in the notification — comparing a
     * SAS from the shade is comparing it without the context that gives it
     * meaning. The tap opens the app and the comparison happens there.
     */
    private fun postPairingNotification(deviceName: String) {
        val tap = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, PAIRING_CHANNEL_ID)
            .setContentTitle(getString(R.string.pairing_notification_title))
            .setContentText(getString(R.string.pairing_notification_text, deviceName))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(PAIRING_NOTIFICATION_ID, notification)
    }

    private fun createPairingChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            PAIRING_CHANNEL_ID,
            getString(R.string.pairing_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.pairing_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.link_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.link_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun createOpenOnPhoneChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            OPEN_CHANNEL_ID,
            getString(R.string.open_on_phone_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.open_on_phone_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.link_notification_title))
            .setContentText(getString(R.string.link_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        private const val TAG = "MazeConnectService"

        /** Fired by the live notification's Refresh action. */
        const val ACTION_REFRESH_STATUS = "com.mazeconnect.app.REFRESH_STATUS"
        private const val CHANNEL_ID = "maze-connect-link"
        private const val NOTIFICATION_ID = 1
        private const val OPEN_CHANNEL_ID = "maze-connect-open"
        private const val OPEN_NOTIFICATION_ID = 2
        private const val PAIRING_CHANNEL_ID = "maze-connect-pairing"
        private const val PAIRING_NOTIFICATION_ID = 3

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MazeConnectService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MazeConnectService::class.java))
        }

        /**
         * The live manager, for anything outside the ViewModel that needs to
         * reach it. A reference, not ownership — nothing here may start or
         * stop it.
         */
        @Volatile
        private var sharedManager: DeviceManager? = null

        fun attachManager(manager: DeviceManager?) {
            sharedManager = manager
        }

        /** The live manager, or null when the app is not running. Used by the
         *  controls widget, which has no other way to reach it. */
        fun manager(): DeviceManager? = sharedManager

    }
}

package com.mazeconnect.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Patterns
import androidx.core.app.NotificationCompat
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

    override fun onCreate() {
        super.onCreate()
        createChannel()
        createOpenOnPhoneChannel()
        acquireWifiLocks()
        watchNetwork()
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
        serviceScope?.cancel()
        serviceScope = null
        eventsJob = null
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
        startForeground(NOTIFICATION_ID, buildNotification())
        // Bring the link up from here too. A widget tap starts this service
        // with no Activity involved, and the link has to exist before the tap
        // can do anything with it.
        if (Link.ensureStarted(applicationContext)) {
            val manager = Link.manager(applicationContext)
            attachManager(manager)
            watchOpenOnPhone(manager)
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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { serviceScope = it }
        eventsJob = scope.launch {
            manager.events.collect { event ->
                if (event is DeviceEvent.OpenOnPhone) postOpenOnPhoneNotification(event.text)
            }
        }
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
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        private const val CHANNEL_ID = "maze-connect-link"
        private const val NOTIFICATION_ID = 1
        private const val OPEN_CHANNEL_ID = "maze-connect-open"
        private const val OPEN_NOTIFICATION_ID = 2

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

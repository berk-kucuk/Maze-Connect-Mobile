package com.mazeconnect.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import com.mazeconnect.app.MainActivity
import com.mazeconnect.app.R
import com.mazeconnect.core.DeviceManager
import com.mazeconnect.core.protocol.MediaAction
import com.mazeconnect.core.protocol.MediaState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The computer's now-playing, as a phone media control.
 *
 * A real [MediaSession], not a notification that looks like one: that is
 * what puts the controls on the lock screen, in the quick-settings media
 * player, and on a paired watch or car — every place a phone already shows
 * "what is playing". The session's callbacks and the notification's buttons
 * both end in [DeviceManager.sendMediaCommand].
 *
 * Framework classes only (android.media.session, Notification.MediaStyle):
 * everything needed has been in the platform since long before this app's
 * minSdk, and it keeps the app free of a media library it would use a sliver of.
 *
 * Shown while something on a connected computer is playing, and kept — but
 * dismissable — while it is paused. Swiping it away keeps it away until that
 * computer starts playing again. Nothing is shown for a computer whose
 * players are all stopped.
 */
class MediaNotification(
    private val context: Context,
    private val manager: DeviceManager,
) {
    private val session = MediaSession(context, "MazeConnect").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = send(MediaAction.PLAY)
            override fun onPause() = send(MediaAction.PAUSE)
            override fun onSkipToNext() = send(MediaAction.NEXT)
            override fun onSkipToPrevious() = send(MediaAction.PREVIOUS)
            override fun onStop() = send(MediaAction.STOP)
            override fun onSeekTo(pos: Long) = send(MediaAction.SEEK, pos.coerceAtLeast(0))
        })
    }

    private var job: Job? = null
    private var subscribed: Set<String> = emptySet()

    /** What is on screen right now, so a button press goes to the same place. */
    @Volatile private var shownDevice: String? = null
    @Volatile private var shownPlayer: MediaState.Player? = null

    /** The track the user swiped away, until something new plays. */
    @Volatile private var dismissedKey: String? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        createChannel()
        instance = java.lang.ref.WeakReference(this)
        job = scope.launch {
            // Interest follows the connected set: every reachable computer is
            // asked to push its players while this runs, under this
            // notification's own token (the Media screen holds another).
            launch {
                manager.connectedIds.collect { connected ->
                    for (id in connected - subscribed) manager.setMediaInterest(id, TOKEN, true)
                    for (id in subscribed - connected) manager.setMediaInterest(id, TOKEN, false)
                    subscribed = connected
                }
            }
            launch {
                combine(manager.media, manager.pairedDevices) { media, paired -> media to paired }
                    .collect { (media, paired) ->
                        render(media) { id -> paired.firstOrNull { it.deviceId == id }?.deviceName }
                    }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        manager.releaseMediaInterest(TOKEN)
        subscribed = emptySet()
        clear()
        session.release()
        if (instance?.get() === this) instance = null
    }

    // ---- choosing what to show ---------------------------------------------

    private fun render(media: Map<String, MediaState>, nameOf: (String) -> String?) {
        // A playing player anywhere wins; otherwise the most recent paused one.
        val candidates = media.flatMap { (deviceId, state) ->
            state.players.filter { it.title.isNotEmpty() || it.status == MediaState.Status.PLAYING }
                .map { Triple(deviceId, state, it) }
        }
        val pick = candidates.firstOrNull { it.third.status == MediaState.Status.PLAYING }
            ?: candidates.filter { it.third.status == MediaState.Status.PAUSED }
                .maxByOrNull { it.second.receivedAtMs }

        if (pick == null) {
            clear()
            return
        }
        val (deviceId, state, player) = pick
        val key = "$deviceId/${player.id}/${player.title}"
        val playing = player.status == MediaState.Status.PLAYING
        if (playing) {
            dismissedKey = null
        } else if (dismissedKey == key) {
            // Swiped away while paused. Keep the session current for the lock
            // screen's sake, but do not bring the notification back.
            shownDevice = deviceId
            shownPlayer = player
            updateSession(state, player)
            return
        }

        shownDevice = deviceId
        shownPlayer = player
        updateSession(state, player)
        post(key, player, playing, nameOf(deviceId) ?: "your computer")
    }

    private fun updateSession(state: MediaState, player: MediaState.Player) {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, player.title.ifEmpty { player.name })
                .putString(MediaMetadata.METADATA_KEY_ARTIST, player.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, player.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, player.lengthMs)
                .build()
        )

        var actions = PlaybackState.ACTION_PLAY_PAUSE
        if (player.canPlay) actions = actions or PlaybackState.ACTION_PLAY
        if (player.canPause) actions = actions or PlaybackState.ACTION_PAUSE
        if (player.canNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (player.canPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (player.canSeek) actions = actions or PlaybackState.ACTION_SEEK_TO

        val playing = player.status == MediaState.Status.PLAYING
        // The position as of now, stamped with the clock the platform uses to
        // move it forward itself between updates.
        val position = player.positionAt(System.currentTimeMillis(), state.receivedAtMs)
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    when (player.status) {
                        MediaState.Status.PLAYING -> PlaybackState.STATE_PLAYING
                        MediaState.Status.PAUSED -> PlaybackState.STATE_PAUSED
                        MediaState.Status.STOPPED -> PlaybackState.STATE_STOPPED
                    },
                    position,
                    if (playing) 1f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build()
        )
        session.isActive = true
    }

    private fun post(key: String, player: MediaState.Player, playing: Boolean, computer: String) {
        val nm = context.getSystemService(NotificationManager::class.java)

        val open = PendingIntent.getActivity(
            context, 20,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismissed = actionIntent(ACTION_DISMISSED, 29, key)

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(player.title.ifEmpty { player.name })
            .setContentText(player.artist.ifEmpty { player.name })
            .setSubText(computer)
            .setContentIntent(open)
            .setDeleteIntent(dismissed)
            .setOngoing(playing)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)

        val compact = ArrayList<Int>()
        fun add(icon: Int, title: String, action: String, code: Int, enabled: Boolean) {
            if (!enabled) return
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, icon), title, actionIntent(action, code, null),
                ).build()
            )
            compact += compact.size
        }
        add(R.drawable.ic_media_previous, "Previous", ACTION_PREVIOUS, 21, player.canPrevious)
        add(
            if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play,
            if (playing) "Pause" else "Play",
            ACTION_PLAY_PAUSE, 22, player.canPlay || player.canPause,
        )
        add(R.drawable.ic_media_next, "Next", ACTION_NEXT, 23, player.canNext)

        builder.setStyle(
            Notification.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(*compact.take(3).toIntArray())
        )
        runCatching { nm.notify(NOTIFICATION_ID, builder.build()) }
    }

    private fun actionIntent(action: String, code: Int, key: String?): PendingIntent =
        PendingIntent.getBroadcast(
            context, code,
            Intent(context, MediaActionReceiver::class.java)
                .setAction(action)
                .apply { if (key != null) putExtra(EXTRA_KEY, key) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun clear() {
        shownDevice = null
        shownPlayer = null
        runCatching {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
        session.isActive = false
    }

    // ---- acting --------------------------------------------------------------

    private fun send(action: MediaAction, value: Long = 0) {
        val device = shownDevice ?: return
        val player = shownPlayer ?: return
        manager.sendMediaCommand(device, player.id, action, value)
    }

    internal fun onAction(action: String, key: String?) {
        when (action) {
            ACTION_PLAY_PAUSE -> send(MediaAction.PLAY_PAUSE)
            ACTION_NEXT -> send(MediaAction.NEXT)
            ACTION_PREVIOUS -> send(MediaAction.PREVIOUS)
            ACTION_DISMISSED -> dismissedKey = key
        }
    }

    private fun createChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.media_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.media_channel_description)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "maze-connect-media"
        private const val NOTIFICATION_ID = 5
        private const val TOKEN = "notification"

        internal const val ACTION_PLAY_PAUSE = "com.mazeconnect.app.media.PLAY_PAUSE"
        internal const val ACTION_NEXT = "com.mazeconnect.app.media.NEXT"
        internal const val ACTION_PREVIOUS = "com.mazeconnect.app.media.PREVIOUS"
        internal const val ACTION_DISMISSED = "com.mazeconnect.app.media.DISMISSED"
        internal const val EXTRA_KEY = "key"

        /** Weak: the notification holds the service's context, and the
         *  service — not this reference — decides how long that lives. */
        @Volatile
        internal var instance: java.lang.ref.WeakReference<MediaNotification>? = null
    }
}

/**
 * The notification's buttons. Not exported: only this app's own
 * PendingIntents reach it, and all it does is hand the press to the
 * notification that is showing — which knows which computer and player it is.
 */
class MediaActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        MediaNotification.instance?.get()?.onAction(action, intent.getStringExtra(MediaNotification.EXTRA_KEY))
    }
}

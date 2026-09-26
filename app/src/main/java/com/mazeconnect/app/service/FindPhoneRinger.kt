package com.mazeconnect.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mazeconnect.app.R

/**
 * "Find my phone": ring loudly until someone picks the phone up.
 *
 * Played on the **alarm** stream, which is the one Android keeps audible in
 * silent and vibrate mode — a lost phone is almost always a silenced one, so
 * a ring that respected the ringer switch would be no use at all. The alarm
 * volume is raised to its maximum for the duration and put back afterwards,
 * so finding the phone does not leave tomorrow's alarm deafening.
 *
 * Stopped by the notification ("Found it"), by the computer, or by a timeout:
 * a phone that is genuinely lost somewhere nobody will reach should not play
 * an alarm until its battery dies. Every stop is reported back to the
 * computers that asked, so none of them keeps offering "Stop ringing".
 */
object FindPhoneRinger {

    private const val TAG = "FindPhoneRinger"
    private const val CHANNEL_ID = "maze-connect-find"
    private const val NOTIFICATION_ID = 5
    private const val MAX_RING_MS = 2 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var restoreAlarmVolume: Int? = null
    private val timeout = Runnable { appContext?.let { stop(it) } }
    private var appContext: Context? = null

    val isRinging: Boolean get() = player != null

    /** Start, or keep going if already ringing (and restart the timeout). */
    fun start(context: Context, requestedBy: String) {
        val app = context.applicationContext
        appContext = app
        main.post {
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, MAX_RING_MS)
            postNotification(app, requestedBy)
            if (player != null) return@post
            if (!startSound(app)) {
                // Say so, rather than let the computer show "Ringing" for a
                // phone making no noise at all.
                stop(app, error = "this phone could not play a sound")
                return@post
            }
            startVibration(app)
        }
    }

    /** Stop and tell the computers. Safe to call when not ringing. */
    fun stop(context: Context, error: String? = null) {
        val app = context.applicationContext
        main.post {
            main.removeCallbacks(timeout)
            val wasRinging = player != null
            runCatching { player?.stop() }
            runCatching { player?.release() }
            player = null
            runCatching { vibrator?.cancel() }
            vibrator = null
            restoreAlarmVolume?.let { previous ->
                runCatching {
                    app.getSystemService(AudioManager::class.java)
                        .setStreamVolume(AudioManager.STREAM_ALARM, previous, 0)
                }
            }
            restoreAlarmVolume = null
            app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            if (wasRinging || error != null) {
                MazeConnectService.manager()?.reportRinging(false, error)
            }
        }
    }

    private fun startSound(app: Context): Boolean {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return false

        val audio = app.getSystemService(AudioManager::class.java)
        runCatching {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val current = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            if (current < max) {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
                restoreAlarmVolume = current
            }
        }.onFailure { Log.w(TAG, "could not raise the alarm volume", it) }

        return runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(app, uri)
                isLooping = true
                prepare()
                start()
            }
            true
        }.getOrElse {
            Log.w(TAG, "could not play the ring", it)
            runCatching { player?.release() }
            player = null
            false
        }
    }

    private fun startVibration(app: Context) {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (!v.hasVibrator()) return
        runCatching {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
            vibrator = v
        }
    }

    // The tap *is* the stop, deliberately: someone who has just found a
    // ringing phone wants it quiet, not an app opened on top of the noise.
    // A broadcast does that without the trampoline lint is warning about —
    // nothing is launched from it.
    @android.annotation.SuppressLint("LaunchActivityFromNotification")
    private fun postNotification(app: Context, requestedBy: String) {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                app.getString(R.string.find_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = app.getString(R.string.find_channel_description)
                // The ring is played by the player above, on the alarm stream;
                // a channel sound would be a second, quieter one on top.
                setSound(null, null)
                enableVibration(false)
            }
        )
        val stop = PendingIntent.getBroadcast(
            app, 0,
            Intent(app, Receiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(app.getString(R.string.find_notification_title))
            .setContentText(app.getString(R.string.find_notification_text, requestedBy))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(true)
            .setContentIntent(stop)
            .setDeleteIntent(stop)
            .addAction(0, app.getString(R.string.find_notification_stop), stop)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    /** The notification's "Found it" (and a tap, and a swipe). Not exported. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            stop(context)
        }
    }
}

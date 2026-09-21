package com.mazeconnect.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import com.mazeconnect.app.MainActivity
import com.mazeconnect.app.R
import com.mazeconnect.core.SystemStatusState
import com.mazeconnect.core.protocol.SystemStatus
import kotlin.math.roundToInt

/**
 * The computer's live readings, raised into the system's own status surfaces.
 *
 * WHAT THIS IS, AND WHAT IT IS NOT
 * --------------------------------
 * It is **not** drawing in the navigation bar. No public API lets an
 * application put anything there; that strip belongs to the system and there
 * is no version of Android in which an app can paint on it.
 *
 * What Samsung's Now Bar (One UI 7+) actually consumes is Android's Live
 * Updates: an ongoing notification that asks to be *promoted*, which the
 * platform then mirrors into the status-bar chip, the lock screen, and — on
 * One UI — the Now Bar. So the way to appear there is to publish a good
 * ongoing notification and ask; it is a request, never a guarantee, and the
 * system is free to decline.
 *
 * TWO TIERS, BECAUSE THE APIS LANDED IN TWO RELEASES
 * ---------------------------------------------------
 * Checked against `platforms/android-37.0/data/api-versions.xml` rather than
 * assumed, because getting this wrong is a NoSuchMethodError on a real phone:
 *
 *   ProgressStyle                     API 36
 *   Builder.setShortCriticalText      API 36
 *   Builder.setRequestPromotedOngoing API 36.1
 *   MetricStyle / Metric              API 37.0
 *
 * Android 16 / One UI 8 is API 36 (36.1 after the first quarterly release),
 * so a phone running One UI 8 today gets the ProgressStyle tier: the
 * computer's name, one headline number in the chip, and a bar. The
 * multi-metric list — CPU, memory, GPU side by side, each with its own
 * label, unit and safe/caution/danger colour — is `MetricStyle`, which is
 * API 37 and simply does not exist on that phone yet. It is written here
 * anyway, behind its own guard, so it lights up on its own when the device
 * gets there rather than needing this file revisited.
 *
 * The small icon is what the system draws in the status-bar chip and on the
 * lock screen, so it is the app's own mark rather than a generic glyph —
 * `ic_notification`, the launcher logo cropped out of its adaptive-icon safe
 * zone and flattened to a silhouette. Using the launcher asset directly would
 * have shown the logo at 55% of an already tiny canvas, because an adaptive
 * foreground is mostly padding by design.
 *
 * Nothing here degrades to a worse experience below API 36: it does not
 * publish at all. The home-screen widgets already cover that ground, and a
 * second permanent notification that cannot be promoted would be clutter
 * rather than a feature.
 */
object LiveStatusNotification {

    /**
     * Bumped from "maze-connect-live".
     *
     * A channel's lock-screen visibility is fixed at creation —
     * `setLockscreenVisibility` is ignored once the channel has been
     * submitted — so an install that already had the old channel would have
     * kept redacting on the lock screen no matter what this code said. A new
     * id is the only way to change it; the old one is deleted so it does not
     * sit in the system list as a dead entry.
     */
    private const val CHANNEL_ID = "maze-connect-live-v2"
    private const val LEGACY_CHANNEL_ID = "maze-connect-live"
    private const val NOTIFICATION_ID = 5

    /** Below this the promotion machinery does not exist, and an unpromoted
     *  ongoing notification is not worth posting. */
    private const val MIN_SDK = 36

    /** Build.VERSION_CODES_FULL.BAKLAVA_1 — when setRequestPromotedOngoing
     *  appeared. Written as the literal because the constant itself is API
     *  36 and this object is read on older devices too. */
    private const val PROMOTABLE_SDK_FULL = 3_600_001

    /** Notification.EXTRA_REQUEST_PROMOTED_ONGOING. See [requestPromotion]
     *  for why this is spelled out instead of referenced. */
    private const val EXTRA_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    private const val METRIC_STYLE_SDK = 37

    /** Above this a reading is worth colouring. Not tuned science — a busy
     *  machine should look busy at a glance, and nothing more is claimed. */
    private const val CAUTION_PERCENT = 75f
    private const val DANGER_PERCENT = 90f

    /**
     * The pill's colour, by load.
     *
     * Unset until now, which is why it was the platform's default blue — a
     * colour that means nothing here and matches nothing in this app.
     *
     * Stepped at the same thresholds as [semanticStyleFor] rather than
     * interpolated smoothly, and that is the point of it. A pill that drifts
     * through a hue on every reading is decoration: it changes constantly,
     * so no particular change means anything. Three steps make the colour
     * an answer — grey is "nothing to see", and anything else is worth a
     * second look. It also keeps this in step with API 37's MetricStyle,
     * which colours its readings by those very constants; two surfaces
     * telling different stories about the same number would be worse than
     * either.
     *
     * Grey rather than black at rest: this is the app's monochrome register,
     * but it has to stay legible as a tint on a dark shade.
     *
     * Muted rather than saturated for the same reason — the alarm should
     * read as this app raising it, not as a system error.
     */
    private const val COLOR_IDLE = 0xFF3F3F45.toInt()
    private const val COLOR_CAUTION = 0xFF8A6A1F.toInt()
    private const val COLOR_DANGER = 0xFF8E2F2F.toInt()

    /** How many readings fit in a line of notification text before it stops
     *  being glanceable. */
    private const val TEXT_METRICS = 3

    /**
     * How many readings go into the collapsed chip.
     *
     * One was too few to be worth a glance: the Now Bar pill said "5%" with
     * no clue what was at 5%, and everything else only appeared once the
     * notification was expanded. Two labelled readings is roughly half again
     * the width and is the point where the pill answers a question on its
     * own. The platform caps the status-bar chip at 96dp and truncates past
     * it — this is deliberately near that edge rather than over it.
     */
    private const val CHIP_METRICS = 3

    /**
     * The slowest this surface is allowed to change.
     *
     * Deliberately decoupled from how fast readings arrive, which is the bug
     * this fixes: the notification was re-posted on every emission, and
     * emissions are driven by whoever is asking. With the Dashboard open
     * that is every three seconds — so a screen nobody was looking at was
     * being torn down and rebuilt twenty times a minute, waking the system
     * UI and the lock screen each time.
     *
     * Two minutes is right for a *glance* surface. It is slower than the
     * one-minute background sweep on purpose, so the common background case
     * settles at one repaint every other reading, and the Refresh action
     * exists for anyone who wants a number now rather than eventually.
     */
    private const val MIN_REPOST_INTERVAL_MS = 120_000L

    private const val PREFS = "maze-live-status"
    private const val KEY_ENABLED = "enabled"

    /** Wall clock of the last post, and what it rendered. Touched from the
     *  service's collector and from the settings switch, hence [Volatile]. */
    @Volatile
    private var lastPostMs = 0L

    @Volatile
    private var lastRendered: String? = null

    @Volatile
    private var forceNext = false

    /**
     * Let the next update through regardless of the interval.
     *
     * The Refresh action asks the computer for a reading, and the answer
     * comes back through the same throttled path as everything else — so
     * without this the button would swallow its own result and read as
     * broken. A user who explicitly asked is not who the rate limit is for.
     */
    fun forceNextUpdate() {
        forceNext = true
    }

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= MIN_SDK

    /**
     * Why the Now Bar is or is not showing this.
     *
     * Worth distinguishing, because two of these look identical from the
     * outside and have opposite causes. [NOT_PROMOTABLE] means the
     * notification is malformed and that is this app's bug; [DECLINED] means
     * it is built correctly and the system refused, which on One UI is
     * normally the "Live notifications for all apps" switch being off. Being
     * told "nothing is showing" without that distinction is how three
     * releases went out chasing the wrong half.
     */
    enum class PromotionState { NOT_SUPPORTED, DISABLED, NOT_PUBLISHED, NOT_PROMOTABLE, DECLINED, PROMOTED }

    /**
     * Read back what the system actually did with the last post.
     *
     * `getActiveNotifications()` returns this app's own notifications as the
     * platform currently holds them — flags included — so this is the
     * system's answer rather than a guess about it.
     */
    fun promotionState(context: Context): PromotionState {
        if (!isSupported()) return PromotionState.NOT_SUPPORTED
        if (!isEnabled(context)) return PromotionState.DISABLED
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return PromotionState.NOT_PUBLISHED
        val active = runCatching { manager.activeNotifications }.getOrNull()
            ?.firstOrNull { it.id == NOTIFICATION_ID }
            ?: return PromotionState.NOT_PUBLISHED
        val notification = active.notification
        return when {
            notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0 ->
                PromotionState.PROMOTED
            !notification.hasPromotableCharacteristics() -> PromotionState.NOT_PROMOTABLE
            else -> PromotionState.DECLINED
        }
    }

    /**
     * Where the switch that gates this lives on One UI.
     *
     * There is no way for an app to turn it on — it is a system setting, and
     * one an app could flip would not be worth having. Taking the user
     * straight to the screen is the whole of what is available, and it is
     * the difference between a one-tap fix and an instruction nobody follows.
     */
    fun developerOptionsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Whether to publish at all.
     *
     * A promoted ongoing notification is, by design, hard to miss: it holds
     * a slot on the lock screen and in the status bar for as long as the
     * link is up. Something that deliberately refuses to go away has to have
     * a switch, and the system's own channel toggle is not a good enough
     * answer here — that turns the notification off while this keeps
     * *publishing* it, which is a different and worse state to be in.
     */
    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_ENABLED, enabled) }
        if (!enabled) clear(context)
    }

    /**
     * Publish whichever connected computer has the freshest reading.
     *
     * The selection lives here rather than at the two call sites — the
     * service's collector and the settings switch — because they must agree.
     * Flipping the switch back on and getting a *different* computer than
     * the one the shade was showing a moment ago would be a bug that only
     * appears with two machines connected, which is exactly the kind that
     * survives testing.
     */
    fun refresh(
        context: Context,
        byDevice: Map<String, SystemStatusState>,
        force: Boolean = false,
        nameFor: (String) -> String?,
    ) {
        if (!isSupported()) return
        if (!isEnabled(context)) {
            clear(context)
            return
        }
        val freshest = byDevice.values
            .mapNotNull { entry -> entry.status?.let { entry.deviceId to it } }
            .maxByOrNull { (_, status) -> status.generated }
        if (freshest == null) {
            clear(context)
            return
        }
        val (deviceId, status) = freshest
        post(context, nameFor(deviceId) ?: status.hostname, status, force)
    }

    /**
     * Publish or update the live reading.
     *
     * Cheap to call repeatedly: posting the same id replaces in place, which
     * is how a Live Update is meant to be kept current.
     */
    fun post(context: Context, deviceName: String, status: SystemStatus, force: Boolean = false) {
        if (!isSupported() || !isEnabled(context)) return
        val metrics = status.metrics
        if (metrics.isEmpty()) {
            clear(context)
            return
        }

        val rendered = signatureOf(deviceName, metrics)
        val now = System.currentTimeMillis()
        val forced = force || forceNext
        forceNext = false
        if (!forced) {
            // Nothing a reader could tell apart — the cheapest possible
            // outcome, and on an idle machine it is most of them.
            if (rendered == lastRendered) return
            if (now - lastPostMs < MIN_REPOST_INTERVAL_MS) return
        }

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        createChannel(context, manager)
        runCatching {
            manager.notify(NOTIFICATION_ID, build(context, deviceName, status, metrics))
        }.onSuccess {
            lastPostMs = now
            lastRendered = rendered
        }
    }

    /** Everything the notification actually draws. Two readings that render
     *  identically are not worth a repaint, however different the floats
     *  behind them were. */
    private fun signatureOf(deviceName: String, metrics: List<SystemStatus.Metric>): String =
        deviceName + metrics.joinToString("|") {
            "${it.label}${it.percent.roundToInt()}${it.detail}"
        } + "@" + colorFor(metrics[headlineIndexOf(metrics)].percent)

    fun clear(context: Context) {
        lastRendered = null
        lastPostMs = 0L
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(NOTIFICATION_ID)
        }
    }

    @RequiresApi(MIN_SDK)
    private fun build(
        context: Context,
        deviceName: String,
        status: SystemStatus,
        metrics: List<SystemStatus.Metric>,
    ): Notification {
        val headlineIndex = headlineIndexOf(metrics)
        val headline = metrics[headlineIndex]

        val tap = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(deviceName.ifEmpty { status.hostname })
            .setContentText(metrics.take(TEXT_METRICS).joinToString(" · ") { it.summary() })
            // The computer sends a detail string beside every reading — a
            // temperature, a used/total pair — and it was being dropped on
            // the floor. It is the part that turns "43%" into something worth
            // knowing, and it costs a line that this notification had spare.
            .setSubText(headline.detail.ifEmpty { status.hostname })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Shown in full on the lock screen rather than redacted to the
            // app's name. VISIBILITY_PRIVATE is the default, and it is the
            // right default for a messaging app; here it produced a Now Bar
            // that said "Maze Connect" and nothing else — the one place this
            // feature exists to be read, showing none of what it came to
            // say. A percentage from the user's own computer is not private
            // information, and the whole point is to be legible without
            // unlocking.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            // An accent, not a fill: setColorized(true) disqualifies a
            // notification from being promoted at all, so how much of the
            // pill this paints is the system's call, not ours.
            .setColor(colorFor(headline.percent))
            .setContentIntent(tap)
            .setShortCriticalText(chipText(metrics, headlineIndex))

        // Earns its place twice over: it pulls a fresh reading instead of
        // waiting out the background sweep, and an action row is real height
        // in a template that otherwise renders title, one line and a bar.
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_notification),
                context.getString(R.string.live_action_refresh),
                PendingIntent.getService(
                    context,
                    3,
                    Intent(context, MazeConnectService::class.java)
                        .setAction(MazeConnectService.ACTION_REFRESH_STATUS),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build()
        )

        if (Build.VERSION.SDK_INT >= METRIC_STYLE_SDK) {
            applyMetricStyle(builder, metrics, headlineIndex)
        } else {
            applyProgressStyle(builder, headline)
        }

        requestPromotion(builder)
        return builder.build()
    }

    /**
     * Ask to be promoted — by both routes, on purpose.
     *
     * `setRequestPromotedOngoing` is API **36.1**, not 36. Guarding the whole
     * request on that version was a real mistake: Android 16.0 devices never
     * had the call made at all, so nothing was ever asked for and nothing
     * could ever appear in the status-bar chip or One UI's Now Bar. One UI 8
     * is built on Android 16 and advertises Live Update support for
     * third-party apps, so a phone that wants this can well be reporting
     * 36.0.
     *
     * All the setter does is write one boolean into the notification's extras
     * under a documented key, and writing a bundle key carries no API level
     * of its own. So the key goes in directly from API 36, and the typed
     * setter is called as well where it exists — on 36.1+ the two agree and
     * the second is a no-op, while on 36.0 the extra is the only thing that
     * can be read at all.
     *
     * The literal is spelled out rather than referencing the constant,
     * because the constant is itself API 36.1 and quoting it here would
     * reintroduce exactly the gap this closes.
     *
     * Either way it stays a *request*. The system decides, and declining is a
     * normal outcome rather than an error worth reporting.
     */
    @RequiresApi(MIN_SDK)
    private fun requestPromotion(builder: Notification.Builder) {
        builder.addExtras(Bundle().apply { putBoolean(EXTRA_PROMOTED_ONGOING, true) })
        if (Build.VERSION.SDK_INT_FULL >= PROMOTABLE_SDK_FULL) {
            builder.setRequestPromotedOngoing(true)
        }
    }

    /** API 36: one bar, one number. */
    @RequiresApi(MIN_SDK)
    private fun applyProgressStyle(
        builder: Notification.Builder,
        headline: SystemStatus.Metric,
    ) {
        val value = headline.percent.roundToInt().coerceIn(0, 100)
        val style = Notification.ProgressStyle()
            // A single full-width segment: the bar is a gauge, not a journey
            // with stages, and segments are how ProgressStyle is told its
            // total length.
            .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100)))
            .setProgress(value)
        builder.setStyle(style)
    }

    /**
     * API 37: every reading side by side, each with its own label, unit and
     * colour — which is what this feature wanted all along.
     */
    @RequiresApi(METRIC_STYLE_SDK)
    private fun applyMetricStyle(
        builder: Notification.Builder,
        metrics: List<SystemStatus.Metric>,
        headlineIndex: Int,
    ) {
        val style = Notification.MetricStyle()
        metrics.forEach { metric ->
            style.addMetric(
                Notification.Metric(
                    // One decimal: the computer sends one, and rounding a
                    // 6.2% reading to 6% throws away the only detail a small
                    // number carries.
                    Notification.Metric.FixedFloat(metric.percent, "%", 0, 1),
                    metric.label,
                    semanticStyleFor(metric.percent),
                )
            )
        }
        style.setCriticalMetric(headlineIndex)
        builder.setStyle(style)
    }

    /**
     * Which reading leads.
     *
     * CPU is the number people mean by "how busy is it", so it leads when
     * the computer reports one. Falling back to the first metric rather than
     * giving up keeps this working on a machine that reports some other set
     * entirely — the keys come from the computer's own helper and this app
     * does not get to assume them.
     *
     * One definition, because two callers depend on it: the notification
     * colours itself from this reading, and the change-detection signature
     * has to hash the same one. Picking differently in the two places would
     * throttle away exactly the colour changes worth showing, and only on
     * machines that report CPU somewhere other than first.
     */
    private fun headlineIndexOf(metrics: List<SystemStatus.Metric>): Int =
        metrics.indexOfFirst { it.key == "cpu" }.takeIf { it >= 0 } ?: 0

    private fun colorFor(percent: Float): Int = when {
        percent >= DANGER_PERCENT -> COLOR_DANGER
        percent >= CAUTION_PERCENT -> COLOR_CAUTION
        else -> COLOR_IDLE
    }

    @RequiresApi(METRIC_STYLE_SDK)
    private fun semanticStyleFor(percent: Float): Int = when {
        percent >= DANGER_PERCENT -> Notification.SEMANTIC_STYLE_DANGER
        percent >= CAUTION_PERCENT -> Notification.SEMANTIC_STYLE_CAUTION
        else -> Notification.SEMANTIC_STYLE_SAFE
    }

    /**
     * The collapsed line: the headline reading first, then whatever else
     * fits, each with its label.
     *
     * Labelled on purpose. A bare "5%" is not a smaller version of the
     * information, it is a different and useless one — the reader cannot
     * tell which of several readings they are looking at.
     */
    private fun chipText(metrics: List<SystemStatus.Metric>, headlineIndex: Int): String {
        val ordered = listOf(metrics[headlineIndex]) +
            metrics.filterIndexed { index, _ -> index != headlineIndex }
        return ordered.take(CHIP_METRICS).joinToString(" · ") { it.summary() }
    }

    private fun SystemStatus.Metric.summary(): String = "$label ${percent.roundToInt()}%"

    private fun createChannel(context: Context, manager: NotificationManager) {
        // IMPORTANCE_LOW: this updates constantly and must never make a
        // sound. Its whole value is being glanceable, not announced. Turning
        // the channel off in system settings is the supported way to opt out,
        // which is why it is its own channel rather than sharing the link's.
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.live_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.live_channel_description)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
    }
}

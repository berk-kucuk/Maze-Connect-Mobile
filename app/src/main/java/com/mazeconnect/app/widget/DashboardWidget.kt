package com.mazeconnect.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.mazeconnect.app.MainActivity
import com.mazeconnect.app.R
import com.mazeconnect.core.protocol.SystemStatus

/**
 * A home-screen dashboard, in three sizes: 2x1, 4x1 and 4x2.
 *
 * The widget is rendered by the launcher, in the launcher's process, whether
 * or not this app is running — so it cannot ask the link for anything. It
 * draws the **last snapshot the app saw**, which [WidgetSnapshotStore] wrote
 * to disk, and says how old that is.
 *
 * Saying the age is the whole point. A widget showing month-old CPU figures
 * with no timestamp is worse than one showing nothing: it looks live, and
 * someone glancing at it would believe it. So a stale reading is labelled,
 * and one with no reading at all says what to do instead.
 *
 * There used to be a fourth, 4x4 size with six meters. It took up half a
 * home screen for detail nobody could read any faster than the 4x2's four
 * meters plus its three stat cells — a taller widget is not automatically a
 * more useful one. Removed rather than kept as an option nobody would pick.
 *
 * Each placed instance draws whichever computer [WidgetDeviceConfig] has it
 * configured for — set once, via [DashboardWidgetConfigureActivity], when
 * the widget is added. An instance placed before that existed falls back to
 * whichever computer's reading is freshest, same as the single shared file
 * this used to read before there was more than one computer to pick from.
 */
open class DashboardWidget : AppWidgetProvider() {

    /** How many of the stored meters this size has room to draw. */
    protected open val meterSlots: Int get() = 4

    protected open val layout: Int get() = R.layout.widget_dashboard

    /** The three-cell hardening/services/network row. Only the sizes with
     *  room for it declare true. */
    protected open val showStatCells: Boolean get() = true

    /**
     * Whether this layout has a place for the computer's detail string.
     *
     * The wide row (label | bar | value) has no room for "17.5 / 31.3 GiB"
     * beside a 40dp value, so the sizes built on it leave the field out
     * entirely rather than abbreviate it into nothing. The stacked sizes
     * give it its own line and set this true; render() then fills it in
     * without either of them knowing which size it is drawing.
     */
    protected open val hasDetail: Boolean get() = false

    /**
     * Readings this size leaves out.
     *
     * Only the 4x1 does, and only because it has to: one cell on a typical
     * home screen leaves about 80dp once the launcher takes its own margin,
     * and the four meters, the header and the three stat cells come to 91dp.
     * Disk is the one dropped because it is the slowest to change of the
     * four — a disk that was 5% full a minute ago still is — so it is the
     * one a glance loses least by not having. Every larger size shows it.
     *
     * Matched on the computer's key first and its label second: the key is
     * the stable identifier, but it comes from the computer's own helper and
     * this app does not get to assume it is there.
     */
    protected open val hiddenMetricKeys: Set<String> get() = setOf("disk")

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        widgetIds: IntArray,
    ) {
        for (id in widgetIds) manager.updateAppWidget(id, render(context, this, id))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) WidgetDeviceConfig.clear(context, id)
    }

    /**
     * The 2x1 variant: the smallest size, but still two real meters —
     * hostname alone answered a question nobody was asking. No stat cells
     * and no separate age line at this height; see widget_mini.xml.
     */
    class Mini : DashboardWidget() {
        override val hiddenMetricKeys: Set<String> get() = emptySet()
        override val meterSlots: Int get() = 2
        override val layout: Int get() = R.layout.widget_mini
        override val showStatCells: Boolean get() = false
    }

    /**
     * The 4x1 variant. Three meters laid out across instead of down — see
     * widget_compact.xml, which reuses this class's ids exactly so no code
     * here has to know which size it is filling in.
     */
    class Compact : DashboardWidget() {
        override val hiddenMetricKeys: Set<String> get() = emptySet()
        override val meterSlots: Int get() = 3
        override val layout: Int get() = R.layout.widget_compact
        override val showStatCells: Boolean get() = false
    }

    /**
     * The 4x3 variant: the same four readings as the 4x2, with room to say
     * what they mean.
     *
     * Two lines per metric — label, value and the computer's full detail on
     * one, the bar at full width beneath. The 4x2 has to choose between the
     * bar and the detail; this size does not have to choose.
     */
    class Detailed : DashboardWidget() {
        override val hiddenMetricKeys: Set<String> get() = emptySet()
        override val meterSlots: Int get() = 4
        override val layout: Int get() = R.layout.widget_detailed
        override val showStatCells: Boolean get() = true
        override val hasDetail: Boolean get() = true
    }

    /**
     * The 2x2 square: two readings with their detail, in the footprint of
     * four app icons.
     *
     * Uses the stacked row rather than the wide one because at this width
     * three columns leave the bar narrower than the padding around it.
     */
    class Tile : DashboardWidget() {
        override val hiddenMetricKeys: Set<String> get() = emptySet()
        override val meterSlots: Int get() = 2
        override val layout: Int get() = R.layout.widget_tile
        override val showStatCells: Boolean get() = false
        override val hasDetail: Boolean get() = true
    }

    companion object {

        // Addressed individually rather than through `<include>`s of one row:
        // RemoteViews resolves ids across the whole tree with no per-include
        // scoping, so shared ids meant only the first row was ever filled in
        // and the widget showed a single bar.
        private val LABELS = intArrayOf(
            R.id.meter_label_1, R.id.meter_label_2, R.id.meter_label_3, R.id.meter_label_4,
        )
        private val BARS = intArrayOf(
            R.id.meter_bar_1, R.id.meter_bar_2, R.id.meter_bar_3, R.id.meter_bar_4,
        )
        private val VALUES = intArrayOf(
            R.id.meter_value_1, R.id.meter_value_2, R.id.meter_value_3, R.id.meter_value_4,
        )
        private val ROWS = intArrayOf(
            R.id.meter_row_1, R.id.meter_row_2, R.id.meter_row_3, R.id.meter_row_4,
        )
        private val DETAILS = intArrayOf(
            R.id.meter_detail_1, R.id.meter_detail_2,
            R.id.meter_detail_3, R.id.meter_detail_4,
        )

        // One instance per registered provider, used only to read its size
        // (layout/slots/flags) by class — never through the Android
        // lifecycle. The single source of truth for "what does each size
        // look like" is the class hierarchy above; refresh() used to repeat
        // those numbers by hand next to each redraw() call, and the two
        // copies had already drifted apart — the compact widget's live
        // updates were drawing one meter instead of the three its own layout
        // has room for.
        private val SIZES: List<DashboardWidget> =
            listOf(Mini(), Tile(), Compact(), DashboardWidget(), Detailed())

        /**
         * Redraw every placed widget, of every size.
         *
         * Called when a new snapshot lands, so the widget follows the app
         * rather than waiting out the system's update period — which is
         * measured in tens of minutes and would make it look broken.
         */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            for (size in SIZES) redraw(context, manager, size)
        }

        /** Redraw one instance right after it is configured — the system
         *  also does this on initial placement, but not on every launcher's
         *  re-edit flow, so this makes the pick feel immediate either way. */
        fun refreshOne(context: Context, appWidgetId: Int) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val providerClass = manager.getAppWidgetInfo(appWidgetId)?.provider?.className
            val size = SIZES.firstOrNull { it.javaClass.name == providerClass } ?: return
            manager.updateAppWidget(appWidgetId, render(context, size, appWidgetId))
        }

        private fun redraw(context: Context, manager: AppWidgetManager, size: DashboardWidget) {
            val ids = manager.getAppWidgetIds(ComponentName(context, size.javaClass))
            for (id in ids) manager.updateAppWidget(id, render(context, size, id))
        }

        private fun render(context: Context, size: DashboardWidget, appWidgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, size.layout)
            val store = WidgetSnapshotStore(context)
            val deviceId = WidgetDeviceConfig.deviceIdFor(context, appWidgetId)
                ?: store.mostRecentDeviceId()

            // Tapping anywhere opens the app, on this widget's computer if
            // it has one configured. A widget that does nothing when
            // pressed reads as broken.
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { deviceId?.let { putExtra(MainActivity.EXTRA_OPEN_DEVICE_ID, it) } }
            views.setOnClickPendingIntent(
                R.id.widget_host,
                PendingIntent.getActivity(
                    context, appWidgetId, open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

            val stored = deviceId?.let { store.load(it) }
            if (stored == null) {
                views.setTextViewText(
                    R.id.widget_host,
                    context.getString(R.string.widget_no_computer),
                )
                views.setTextViewText(R.id.widget_age, "")
                for (i in 0 until size.meterSlots) views.setViewVisibility(ROWS[i], View.GONE)
                if (size.showStatCells) views.setViewVisibility(R.id.widget_stats, View.GONE)
                views.setViewVisibility(R.id.widget_hint, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_hint,
                    context.getString(R.string.widget_open_to_connect),
                )
                return views
            }

            val (status, savedAtMillis) = stored
            views.setViewVisibility(R.id.widget_hint, View.GONE)
            views.setTextViewText(
                R.id.widget_host,
                status.hostname.ifEmpty { context.getString(R.string.widget_computer) },
            )
            views.setTextViewText(R.id.widget_age, ageLabel(context, savedAtMillis))

            val shown = status.metrics.filterNot { metric ->
                size.hiddenMetricKeys.any {
                    it.equals(metric.key, ignoreCase = true) ||
                        it.equals(metric.label, ignoreCase = true)
                }
            }
            for (i in 0 until size.meterSlots) {
                val metric = shown.getOrNull(i)
                if (metric == null) {
                    views.setViewVisibility(ROWS[i], View.GONE)
                    continue
                }
                views.setViewVisibility(ROWS[i], View.VISIBLE)
                views.setTextViewText(LABELS[i], metric.label)
                views.setProgressBar(BARS[i], 100, metric.percent.toInt(), false)
                views.setTextViewText(VALUES[i], "${metric.percent.toInt()}%")
                if (size.hasDetail) {
                    // Hidden rather than left blank when the computer sends
                    // none: an empty view still takes its line, and a row
                    // with a gap where a number should be reads as a reading
                    // that failed rather than one that was never offered.
                    if (metric.detail.isEmpty()) {
                        views.setViewVisibility(DETAILS[i], View.GONE)
                    } else {
                        views.setViewVisibility(DETAILS[i], View.VISIBLE)
                        views.setTextViewText(DETAILS[i], metric.detail)
                    }
                }
            }

            // Only the 4x2 has room for these: hardening score, security
            // services and network, as three short numbers rather than one
            // folded sentence.
            if (size.showStatCells) {
                views.setViewVisibility(R.id.widget_stats, View.VISIBLE)
                views.setTextViewText(
                    R.id.stat_hardening_value,
                    status.hardeningScore?.let { "$it%" } ?: "—",
                )
                views.setTextViewText(
                    R.id.stat_services_value,
                    if (status.security.isEmpty()) "—" else {
                        val running = status.security.count { it.state == SystemStatus.State.ACTIVE }
                        "$running/${status.security.size}"
                    },
                )
                views.setTextViewText(
                    R.id.stat_network_value,
                    if (status.network.isEmpty()) "—" else {
                        val good = status.network.count { it.state == SystemStatus.State.ACTIVE }
                        "$good/${status.network.size}"
                    },
                )
            }
            return views
        }

        /**
         * How old the reading is, in words.
         *
         * Deliberately coarse. Second-level precision would imply the widget
         * is live, and it is not — it is the last thing the app happened to
         * see.
         */
        private fun ageLabel(context: Context, savedAtMillis: Long): String {
            val minutes = (System.currentTimeMillis() - savedAtMillis) / 60_000
            return when {
                minutes < 1 -> context.getString(R.string.widget_age_now)
                minutes < 60 -> context.getString(R.string.widget_age_minutes, minutes)
                minutes < 60 * 24 -> context.getString(R.string.widget_age_hours, minutes / 60)
                else -> context.getString(R.string.widget_age_old)
            }
        }
    }
}

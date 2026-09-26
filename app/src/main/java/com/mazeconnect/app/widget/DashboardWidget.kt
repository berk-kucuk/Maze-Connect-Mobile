package com.mazeconnect.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.mazeconnect.app.MainActivity
import com.mazeconnect.app.R
import com.mazeconnect.core.protocol.SystemStatus

/**
 * The computer's dashboard on the home screen — one widget that fits whatever
 * space it is given.
 *
 * Every earlier version drew one fixed layout per provider and declared a
 * size for it, and every round of fixes moved the same problem around: the
 * layout was taller than its cells on one phone and clipped, or shorter on
 * another and left a slab of empty card. A launcher cell simply has no fixed
 * size. So each instance now carries several layouts, each measured for a
 * range (see the [Style] sizes and the comment at the top of each layout
 * file), and shows whichever fits — re-chosen on rotation and on resize. See
 * [WidgetSizing] for how the choice is made.
 *
 * The five providers are the same widget with a different starting size, so
 * the picker offers a sensible first placement for each footprint:
 * Mini 2×1, Square 2×2, Strip 4×1, Grid 4×2, Detailed 4×2. The only difference
 * that survives a resize is the largest layout: Detailed keeps its list with
 * the computer's detail strings and stat cells, the rest use the tile grid.
 *
 * The widget is rendered in the launcher's process, whether or not this app
 * is running, so it cannot ask the link for anything. It draws the **last
 * snapshot the app saw** ([WidgetSnapshotStore]) and always says how old it
 * is — a month-old reading with no timestamp looks live, which is worse than
 * showing nothing.
 */
open class DashboardWidget : AppWidgetProvider() {

    /**
     * One layout and what it has room for.
     *
     * The sizes are measured against the layout XML, not chosen: change a
     * layout's padding or type size and its size here has to be re-measured,
     * or the widget will clip on the grid it was just fixed for.
     */
    enum class Style(
        val layout: Int,
        val widthDp: Float,
        val heightDp: Float,
        /** How many readings it draws. */
        val slots: Int,
        /** Whether it has a line for the computer's detail ("45 °C"). */
        val detail: Boolean,
        /** Whether it has the hardening/services/network cells. */
        val stats: Boolean,
        /** Narrow label column: "RAM" rather than "Memory". */
        val shortLabels: Boolean,
    ) {
        MINI(R.layout.widget_mini, 110f, 48f, 2, false, false, true),
        COMPACT(R.layout.widget_compact, 180f, 50f, 3, false, false, true),
        TILE(R.layout.widget_tile, 110f, 122f, 2, true, false, false),
        DASHBOARD(R.layout.widget_dashboard, 180f, 116f, 4, false, false, true),
        GRID(R.layout.widget_grid, 180f, 192f, 4, true, false, false),
        DETAILED(R.layout.widget_detailed, 180f, 182f, 4, true, true, false),
    }

    /** What this provider shows when it has room for the most. */
    protected open val largeStyle: Style get() = Style.GRID

    /** Drawn before the launcher has reported a size (API 28-30 only). */
    protected open val defaultStyle: Style get() = Style.GRID

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        for (id in widgetIds) manager.updateAppWidget(id, render(context, this, id))
    }

    /** A resize, or the launcher reporting sizes for the first time. On API
     *  31+ the launcher re-picks by itself; below that this is how it hears. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        manager.updateAppWidget(appWidgetId, render(context, this, appWidgetId))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) WidgetDeviceConfig.clear(context, id)
    }

    class Mini : DashboardWidget() {
        override val defaultStyle: Style get() = Style.MINI
    }

    class Compact : DashboardWidget() {
        override val defaultStyle: Style get() = Style.COMPACT
    }

    class Tile : DashboardWidget() {
        override val defaultStyle: Style get() = Style.TILE
    }

    class Detailed : DashboardWidget() {
        override val largeStyle: Style get() = Style.DETAILED
        override val defaultStyle: Style get() = Style.DETAILED
    }

    companion object {

        // Addressed individually rather than through `<include>`s of one row:
        // RemoteViews resolves ids across the whole tree with no per-include
        // scoping, so shared ids meant only the first row was ever filled in.
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
            R.id.meter_detail_1, R.id.meter_detail_2, R.id.meter_detail_3, R.id.meter_detail_4,
        )

        /** Every layout a provider can show, largest class last. */
        internal fun layoutsFor(large: Style): List<SizedLayout<Style>> =
            listOf(Style.MINI, Style.COMPACT, Style.TILE, Style.DASHBOARD, large)
                .map { SizedLayout(it, it.widthDp, it.heightDp) }

        // One instance per registered provider, used only to read its
        // settings by class — never through the Android lifecycle.
        private val PROVIDERS: List<DashboardWidget> =
            listOf(Mini(), Tile(), Compact(), DashboardWidget(), Detailed())

        /** Redraw every placed widget. Called when a new snapshot lands. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            for (provider in PROVIDERS) {
                val ids = manager.getAppWidgetIds(ComponentName(context, provider.javaClass))
                for (id in ids) manager.updateAppWidget(id, render(context, provider, id))
            }
        }

        /** Redraw one instance right after it is configured. */
        fun refreshOne(context: Context, appWidgetId: Int) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val providerClass = manager.getAppWidgetInfo(appWidgetId)?.provider?.className
            val provider = PROVIDERS.firstOrNull { it.javaClass.name == providerClass } ?: return
            manager.updateAppWidget(appWidgetId, render(context, provider, appWidgetId))
        }

        private fun render(context: Context, provider: DashboardWidget, appWidgetId: Int): RemoteViews {
            // Read once, drawn into every layout: the choice between them is
            // the launcher's, and they must all show the same reading.
            val store = WidgetSnapshotStore(context)
            val deviceId = WidgetDeviceConfig.deviceIdFor(context, appWidgetId)
                ?: store.mostRecentDeviceId()
            val stored = deviceId?.let { store.load(it) }
            val open = openApp(context, appWidgetId, deviceId)

            return WidgetSizing.build(
                context, appWidgetId, layoutsFor(provider.largeStyle), provider.defaultStyle,
            ) { style -> draw(context, style, stored, open) }
        }

        private fun openApp(context: Context, appWidgetId: Int, deviceId: String?): PendingIntent {
            // Tapping anywhere opens the app, on this widget's computer. A
            // widget that does nothing when pressed reads as broken.
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { deviceId?.let { putExtra(MainActivity.EXTRA_OPEN_DEVICE_ID, it) } }
            return PendingIntent.getActivity(
                context, appWidgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun draw(
            context: Context,
            style: Style,
            stored: Pair<SystemStatus, Long>?,
            open: PendingIntent,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, style.layout)
            views.setOnClickPendingIntent(android.R.id.background, open)

            if (stored == null) {
                views.setTextViewText(R.id.widget_host, context.getString(R.string.widget_no_computer))
                views.setTextViewText(R.id.widget_age, "")
                views.setViewVisibility(R.id.widget_meters, View.GONE)
                if (style.stats) views.setViewVisibility(R.id.widget_stats, View.GONE)
                views.setViewVisibility(R.id.widget_hint, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_hint,
                    context.getString(R.string.widget_open_to_connect),
                )
                return views
            }

            val (status, savedAtMillis) = stored
            views.setViewVisibility(R.id.widget_hint, View.GONE)
            views.setViewVisibility(R.id.widget_meters, View.VISIBLE)
            views.setTextViewText(
                R.id.widget_host,
                status.hostname.ifEmpty { context.getString(R.string.widget_computer) },
            )
            views.setTextViewText(R.id.widget_age, ageLabel(context, savedAtMillis))

            for (i in 0 until style.slots) {
                val metric = status.metrics.getOrNull(i)
                if (metric == null) {
                    views.setViewVisibility(ROWS[i], View.GONE)
                    continue
                }
                val percent = metric.percent.toInt().coerceIn(0, 100)
                views.setViewVisibility(ROWS[i], View.VISIBLE)
                views.setTextViewText(
                    LABELS[i],
                    if (style.shortLabels) shortLabel(metric.key, metric.label) else metric.label,
                )
                views.setProgressBar(BARS[i], 100, percent, false)
                views.setTextViewText(VALUES[i], "$percent%")
                if (style.detail) {
                    // Gone rather than blank when the computer sends none: an
                    // empty line where a number should be reads as a reading
                    // that failed, not one that was never offered.
                    if (metric.detail.isEmpty()) {
                        views.setViewVisibility(DETAILS[i], View.GONE)
                    } else {
                        views.setViewVisibility(DETAILS[i], View.VISIBLE)
                        views.setTextViewText(DETAILS[i], metric.detail)
                    }
                }
            }

            if (style.stats) {
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
         * A label for a narrow column. The computer's own label is kept
         * whenever it is already short; the common long ones are abbreviated
         * the way every system monitor does, so "Memory" does not ellipsize
         * to "Mem…" beside its bar.
         */
        internal fun shortLabel(key: String, label: String): String {
            val probe = "$key $label".lowercase()
            return when {
                label.length <= 5 -> label
                "cpu" in probe || "processor" in probe -> "CPU"
                "swap" in probe -> "Swap"
                "mem" in probe || "ram" in probe -> "RAM"
                "disk" in probe || "storage" in probe || "root" in probe -> "Disk"
                "temp" in probe -> "Temp"
                "gpu" in probe -> "GPU"
                "batt" in probe -> "Batt"
                else -> label.take(5)
            }
        }

        /**
         * How old the reading is, in words. Deliberately coarse: second-level
         * precision would imply the widget is live, and it is not.
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

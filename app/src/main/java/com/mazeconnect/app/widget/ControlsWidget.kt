package com.mazeconnect.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.mazeconnect.app.R

/**
 * Killswitches on the home screen — the widget that *does* something.
 *
 * The dashboard widget is a readout. This one is a control: each cell toggles
 * one device on the paired computer, without opening the app at all. For a
 * remote-control application that is the point of having a home screen
 * presence in the first place.
 *
 * A tap opens [GuardConfirmActivity], which asks and then acts. It used to
 * act on the press directly, and that was wrong twice over: a widget press has
 * no way to report anything, so an unreachable computer looked exactly like a
 * dead button — and this is the privileged capability, so the place easiest to
 * hit by accident should not be the one place without a confirmation.
 *
 * **What a tap cannot do:** name a verb. It carries a device from a fixed
 * list and nothing else, and the desktop refuses anything outside its own
 * enum regardless. PANIC and RESTORE are not reachable from here any more
 * than from anywhere else.
 *
 * Each placed instance controls whichever computer [WidgetDeviceConfig] has
 * it configured for, set via [ControlsWidgetConfigureActivity] when the
 * widget is added — otherwise two instances would necessarily fight over
 * one computer.
 */
class ControlsWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        for (id in widgetIds) manager.updateAppWidget(id, render(context, id))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) WidgetDeviceConfig.clear(context, id)
    }

    /** A resize: pick the layout for the new size (see [WidgetSizing]). */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        manager.updateAppWidget(appWidgetId, render(context, appWidgetId))
    }

    // No onReceive: a press opens GuardConfirmActivity instead of acting
    // here. A widget press has no way to report anything — if the computer is
    // unreachable the cell simply does not change, which is indistinguishable
    // from the button being dead. And this is the privileged capability, so
    // the one place that should *not* skip the confirmation is the one that is
    // easiest to hit by accident.

    companion object {
        const val EXTRA_DEVICE = "device"
        const val EXTRA_TARGET_DEVICE_ID = "targetDeviceId"

        /** The four worth a home-screen button. `usb` is deliberately not
         *  here: blocking it from a phone is far more likely to strand
         *  somebody than to help them. */
        private val DEVICES = listOf("camera", "microphone", "wifi", "bluetooth")
        private val LABELS = listOf("Camera", "Mic", "Wi-Fi", "BT")

        private val CELLS = intArrayOf(
            R.id.control_1, R.id.control_2, R.id.control_3, R.id.control_4,
        )
        private val NAMES = intArrayOf(
            R.id.control_name_1, R.id.control_name_2, R.id.control_name_3, R.id.control_name_4,
        )
        private val ICONS = intArrayOf(
            R.id.control_icon_1, R.id.control_icon_2, R.id.control_icon_3, R.id.control_icon_4,
        )
        private val STATES = intArrayOf(
            R.id.control_state_1, R.id.control_state_2, R.id.control_state_3, R.id.control_state_4,
        )

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, ControlsWidget::class.java))
            for (id in ids) manager.updateAppWidget(id, render(context, id))
        }

        /** Redraw one instance right after it is configured. */
        fun refreshOne(context: Context, appWidgetId: Int) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            manager.updateAppWidget(appWidgetId, render(context, appWidgetId))
        }

        /**
         * Two layouts: the normal one with the computer's name above the
         * cells, and a single row for a short cell (a 4x1 in landscape, a
         * dense grid). Sizes measured against each layout — see the comment
         * at the top of the two XML files.
         */
        private val LAYOUTS = listOf(
            SizedLayout(R.layout.widget_controls_slim, 250f, 46f),
            SizedLayout(R.layout.widget_controls, 180f, 94f),
        )

        private fun render(context: Context, appWidgetId: Int): RemoteViews =
            WidgetSizing.build(context, appWidgetId, LAYOUTS, R.layout.widget_controls) { layout ->
                draw(context, appWidgetId, layout)
            }

        private fun draw(context: Context, appWidgetId: Int, layout: Int): RemoteViews {
            val views = RemoteViews(context.packageName, layout)
            val store = WidgetSnapshotStore(context)
            val deviceId = WidgetDeviceConfig.deviceIdFor(context, appWidgetId)
                ?: store.mostRecentDeviceId()
            val stored = deviceId?.let { store.load(it) }
            val guard = deviceId?.let { store.guardStates(it) } ?: emptyMap()

            val openApp =
                PendingIntent.getActivity(
                    context, appWidgetId,
                    Intent(context, com.mazeconnect.app.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .apply {
                            deviceId?.let {
                                putExtra(com.mazeconnect.app.MainActivity.EXTRA_OPEN_DEVICE_ID, it)
                            }
                        },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            // The whole card opens the app; each cell's own intent, set
            // below, takes precedence inside the cell.
            views.setOnClickPendingIntent(android.R.id.background, openApp)
            views.setOnClickPendingIntent(R.id.widget_host, openApp)

            views.setTextViewText(
                R.id.widget_host,
                stored?.first?.hostname?.ifEmpty { null }
                    ?: context.getString(R.string.widget_no_computer),
            )
            views.setTextViewText(R.id.widget_age, "")

            if (guard.isEmpty()) {
                views.setViewVisibility(R.id.control_row, View.GONE)
                views.setViewVisibility(R.id.widget_hint, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_hint,
                    context.getString(R.string.widget_open_to_connect),
                )
                return views
            }

            views.setViewVisibility(R.id.control_row, View.VISIBLE)
            views.setViewVisibility(R.id.widget_hint, View.GONE)

            for (i in DEVICES.indices) {
                val device = DEVICES[i]
                val state = guard[device]
                views.setTextViewText(NAMES[i], LABELS[i])
                // The mark follows the state too: full strength when blocked
                // — the protected reading — and dim otherwise, so a glance at
                // the row says which switches are on without reading words.
                views.setInt(
                    ICONS[i],
                    "setColorFilter",
                    if (state == "off") 0xFFF2F1EC.toInt() else 0xFF7A7A7F.toInt(),
                )
                views.setTextViewText(
                    STATES[i],
                    when (state) {
                        // "Blocked" is the protected state, and it is the
                        // opposite of what "on" suggests — so the cell says
                        // the word rather than relying on an indicator.
                        "off" -> context.getString(R.string.widget_blocked)
                        "on" -> context.getString(R.string.widget_allowed)
                        "none" -> context.getString(R.string.widget_absent)
                        else -> context.getString(R.string.widget_unknown)
                    },
                )

                // A device the machine does not have is not a button.
                if (state == "none" || state == null) {
                    views.setOnClickPendingIntent(CELLS[i], null)
                    continue
                }
                val toggle = Intent(context, GuardConfirmActivity::class.java).apply {
                    putExtra(EXTRA_DEVICE, device)
                    deviceId?.let { putExtra(EXTRA_TARGET_DEVICE_ID, it) }
                    // NEW_TASK only. CLEAR_TASK was here too, and it cleared
                    // *the app's* task: opening this confirmation finished
                    // MainActivity, whose ViewModel then tore the link down,
                    // so the computer showed "disconnected" the instant the
                    // dialog appeared. The activity now has its own affinity
                    // (see the manifest) and never touches the app's task.
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                views.setOnClickPendingIntent(
                    CELLS[i],
                    PendingIntent.getActivity(
                        // A distinct request code per cell — combined with the
                        // widget id so two widgets' cells never collide either:
                        // PendingIntents that differ only in extras are
                        // considered equal otherwise, and one code would give
                        // every cell the first device.
                        context, appWidgetId * 8 + i, toggle,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
            return views
        }
    }
}

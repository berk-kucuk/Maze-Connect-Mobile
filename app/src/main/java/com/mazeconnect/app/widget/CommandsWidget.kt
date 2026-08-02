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
 * Favourite commands, on the home screen — the same idea as
 * [ControlsWidget], for the Commands capability instead of Guard.
 *
 * A cell is just a label: unlike a killswitch, a command has no persistent
 * on/off state to draw, so there is nothing here besides what a tap runs.
 * Which commands appear is decided entirely on the computer — see
 * `CommandsView.qml`'s "Show on phone widget" toggle — and this widget only
 * ever draws whatever [WidgetSnapshotStore] was last told; it cannot pin or
 * unpin anything itself.
 *
 * A tap opens [CommandRunActivity] rather than running the command directly,
 * for the same two reasons [GuardConfirmActivity] does: a widget press has
 * no other way to report whether it worked, and pressing a cell may be the
 * thing that starts the app in the first place.
 *
 * Each placed instance runs commands on whichever computer
 * [WidgetDeviceConfig] has it configured for, set via
 * [CommandsWidgetConfigureActivity] when the widget is added — otherwise two
 * instances would necessarily fight over one computer's command list.
 */
class CommandsWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        for (id in widgetIds) manager.updateAppWidget(id, render(context, id))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) WidgetDeviceConfig.clear(context, id)
    }

    companion object {
        const val EXTRA_COMMAND_ID = "commandId"
        const val EXTRA_COMMAND_LABEL = "commandLabel"
        const val EXTRA_COMMAND_CONFIRM = "commandConfirm"
        const val EXTRA_TARGET_DEVICE_ID = "targetDeviceId"

        private val CELLS = intArrayOf(
            R.id.command_1, R.id.command_2, R.id.command_3, R.id.command_4,
        )
        private val NAMES = intArrayOf(
            R.id.command_name_1, R.id.command_name_2, R.id.command_name_3, R.id.command_name_4,
        )

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, CommandsWidget::class.java))
            for (id in ids) manager.updateAppWidget(id, render(context, id))
        }

        /** Redraw one instance right after it is configured. */
        fun refreshOne(context: Context, appWidgetId: Int) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            manager.updateAppWidget(appWidgetId, render(context, appWidgetId))
        }

        private fun render(context: Context, appWidgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_commands)
            val store = WidgetSnapshotStore(context)
            val deviceId = WidgetDeviceConfig.deviceIdFor(context, appWidgetId)
                ?: store.mostRecentDeviceId()
            val stored = deviceId?.let { store.load(it) }
            val pinned = deviceId?.let { store.pinnedCommands(it) } ?: emptyList()

            views.setOnClickPendingIntent(
                R.id.widget_host,
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
                ),
            )

            views.setTextViewText(
                R.id.widget_host,
                stored?.first?.hostname?.ifEmpty { null }
                    ?: context.getString(R.string.widget_no_computer),
            )
            views.setTextViewText(R.id.widget_age, "")

            // No computer at all vs. a computer with nothing pinned yet are
            // different situations, and the hint says which one it is —
            // the first file is missing, the second is just empty.
            if (deviceId == null) {
                views.setViewVisibility(R.id.command_row, View.GONE)
                views.setViewVisibility(R.id.widget_hint, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_hint,
                    context.getString(R.string.widget_open_to_connect),
                )
                return views
            }
            if (pinned.isEmpty()) {
                views.setViewVisibility(R.id.command_row, View.GONE)
                views.setViewVisibility(R.id.widget_hint, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_hint,
                    context.getString(R.string.widget_commands_none_pinned),
                )
                return views
            }

            views.setViewVisibility(R.id.command_row, View.VISIBLE)
            views.setViewVisibility(R.id.widget_hint, View.GONE)

            for (i in CELLS.indices) {
                val command = pinned.getOrNull(i)
                if (command == null) {
                    views.setViewVisibility(CELLS[i], View.INVISIBLE)
                    views.setOnClickPendingIntent(CELLS[i], null)
                    continue
                }
                views.setViewVisibility(CELLS[i], View.VISIBLE)
                views.setTextViewText(NAMES[i], command.label)

                val run = Intent(context, CommandRunActivity::class.java).apply {
                    putExtra(EXTRA_COMMAND_ID, command.id)
                    putExtra(EXTRA_COMMAND_LABEL, command.label)
                    putExtra(EXTRA_COMMAND_CONFIRM, command.confirm)
                    putExtra(EXTRA_TARGET_DEVICE_ID, deviceId)
                    // Its own affinity, same reasoning as GuardConfirmActivity:
                    // this must never touch MainActivity's task.
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                views.setOnClickPendingIntent(
                    CELLS[i],
                    PendingIntent.getActivity(
                        // A distinct request code per cell, combined with the
                        // widget id so two widgets' cells never collide either
                        // — see ControlsWidget for why that matters.
                        context, appWidgetId * 8 + i, run,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
            return views
        }
    }
}

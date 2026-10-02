package com.mazeconnect.app.widget

import android.content.Context
import android.widget.RemoteViews
import com.mazeconnect.app.R

/**
 * What every widget's header shares: the computer's name, a dot that says
 * whether the reading is fresh, and — on the readouts — how old it is.
 *
 * The widget draws the last snapshot the app saw, never a live one, so the
 * dot is the at-a-glance version of the age: filled while the reading is a
 * few minutes old at most, hollow once it is older or there is none.
 */
internal object WidgetChrome {

    /** Colours for state that RemoteViews sets at runtime, matching
     *  widget_styles.xml. */
    const val PAPER = 0xFFF2F1EC.toInt()
    const val DIM = 0xFF8E8E93.toInt()
    const val FAINT = 0xFF56565B.toInt()
    const val INK = 0xFF0A0A0B.toInt()
    const val INK_DIM = 0x9E0A0A0B.toInt()

    /** How long a reading counts as fresh. */
    private const val FRESH_MS = 5 * 60_000L

    /**
     * Name, freshness dot and age. [savedAtMillis] null means there is no
     * reading at all. Layouts without an age line simply lack the view, and
     * RemoteViews skips an action for a view a layout does not have.
     */
    fun header(views: RemoteViews, context: Context, host: String?, savedAtMillis: Long?) {
        views.setTextViewText(
            R.id.widget_host,
            host?.ifEmpty { null } ?: context.getString(
                if (savedAtMillis == null) R.string.widget_no_computer else R.string.widget_computer,
            ),
        )
        val fresh = savedAtMillis != null &&
            System.currentTimeMillis() - savedAtMillis < FRESH_MS
        views.setImageViewResource(
            R.id.widget_dot,
            if (fresh) R.drawable.widget_dot_live else R.drawable.widget_dot_stale,
        )
        views.setTextViewText(
            R.id.widget_age,
            savedAtMillis?.let { ageLabel(context, it) } ?: "",
        )
    }

    /**
     * How old the reading is, in words. Deliberately coarse: second-level
     * precision would imply the widget is live, and it is not.
     */
    fun ageLabel(context: Context, savedAtMillis: Long): String {
        val minutes = (System.currentTimeMillis() - savedAtMillis) / 60_000
        return when {
            minutes < 1 -> context.getString(R.string.widget_age_now)
            minutes < 60 -> context.getString(R.string.widget_age_minutes, minutes)
            minutes < 60 * 24 -> context.getString(R.string.widget_age_hours, minutes / 60)
            else -> context.getString(R.string.widget_age_old)
        }
    }
}

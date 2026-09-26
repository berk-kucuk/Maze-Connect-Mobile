package com.mazeconnect.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews

/**
 * One layout, and the smallest size (in dp) it was measured to fit.
 *
 * The sizes are measured from the layout itself — padding plus each line's
 * text height with font padding off, plus the bars — not guessed from a cell
 * count. Guessing is what every earlier round of widget fixes did, and it is
 * why they kept trading one complaint for the other: a layout taller than its
 * declared size is clipped on a short grid, and one shorter than the cells it
 * is given leaves a slab of empty card on a tall grid.
 */
data class SizedLayout<T>(val key: T, val widthDp: Float, val heightDp: Float)

/**
 * Picks a layout for the size a widget actually has, instead of assuming one.
 *
 * A launcher cell has no fixed size. The same "4×1" is about 276×102 dp on a
 * portrait Pixel, 554×51 dp in landscape, and 360×116 dp on a Samsung 4×5
 * grid — so any single layout is either clipped on one of those or half empty
 * on another. The widget is therefore given several, each sized for a range,
 * and shows whichever fits the space it was really given:
 *
 *  * **API 31+**: a size-mapped RemoteViews. The launcher itself picks per
 *    orientation and re-picks on resize, with no round trip through the app.
 *  * **API 28-30**: the same choice made here, from the min/max sizes the
 *    launcher reports in the widget's options, as a landscape/portrait pair.
 *
 * Both follow the platform's own rule, so a phone on either side of API 31
 * shows the same layout for the same size.
 */
object WidgetSizing {

    /**
     * The platform's best-fit rule (RemoteViews.findBestSizeLayout): among
     * the layouts that fit, the one closest in size; if none fits, the
     * smallest — a clipped small layout reads better than a clipped big one.
     */
    fun <T> pick(widthDp: Float, heightDp: Float, layouts: List<SizedLayout<T>>): SizedLayout<T> {
        require(layouts.isNotEmpty())
        val fitting = layouts.filter { it.widthDp <= widthDp && it.heightDp <= heightDp }
        if (fitting.isEmpty()) return layouts.minBy { it.widthDp * it.heightDp }
        return fitting.minBy {
            val dw = widthDp - it.widthDp
            val dh = heightDp - it.heightDp
            dw * dw + dh * dh
        }
    }

    /**
     * RemoteViews that show the right layout for this instance's size.
     *
     * [fallback] is drawn when the launcher has not reported a size yet
     * (pre-31 only; on 31+ the launcher always has one).
     */
    fun <T> build(
        context: Context,
        appWidgetId: Int,
        layouts: List<SizedLayout<T>>,
        fallback: T,
        make: (T) -> RemoteViews,
    ): RemoteViews {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return RemoteViews(layouts.associate { SizeF(it.widthDp, it.heightDp) to make(it.key) })
        }
        val options = runCatching {
            AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        }.getOrNull() ?: return make(fallback)
        val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
        val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        if (minW <= 0 || maxH <= 0) return make(fallback)

        // The platform's own convention: portrait is narrow and tall
        // (min width, max height), landscape wide and short.
        val portrait = pick(minW.toFloat(), maxH.toFloat(), layouts).key
        val landscape = pick(maxW.toFloat(), minH.toFloat(), layouts).key
        return if (portrait == landscape) {
            make(portrait)
        } else {
            RemoteViews(make(landscape), make(portrait))
        }
    }
}

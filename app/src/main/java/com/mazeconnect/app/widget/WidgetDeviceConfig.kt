package com.mazeconnect.app.widget

import android.content.Context

/**
 * Which computer each placed widget instance draws.
 *
 * A widget provider is one class shared by every instance the user has
 * placed — the standard Android answer, the same one a weather widget uses
 * for "which city", is a small `appWidgetId -> deviceId` map filled in by a
 * configuration [android.app.Activity] shown once when the widget is added.
 */
object WidgetDeviceConfig {

    fun save(context: Context, appWidgetId: Int, deviceId: String) {
        prefs(context).edit().putString(key(appWidgetId), deviceId).apply()
    }

    /** Null for a widget that was never configured — placed before this
     *  feature existed, or a configure flow the user backed out of. */
    fun deviceIdFor(context: Context, appWidgetId: Int): String? =
        prefs(context).getString(key(appWidgetId), null)

    /** Called from `onDeleted()`: otherwise the mapping outlives the widget
     *  and could resurface stale if Android ever reuses the id. */
    fun clear(context: Context, appWidgetId: Int) {
        prefs(context).edit().remove(key(appWidgetId)).apply()
    }

    private fun key(appWidgetId: Int) = "widget-$appWidgetId"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("widget-device-config", Context.MODE_PRIVATE)
}

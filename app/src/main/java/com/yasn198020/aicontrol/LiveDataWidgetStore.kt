package com.yasn198020.aicontrol

import android.content.Context

object LiveDataWidgetStore {
    private const val PREFS = "live_data_widget_preferences"
    private const val KEY_DEVICE = "device_"
    private const val KEY_WIDGET = "widget_"

    data class Selection(val deviceId: String, val widgetId: String)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, appWidgetId: Int): Selection? {
        val p = prefs(context)
        val deviceId = p.getString(KEY_DEVICE + appWidgetId, null)?.trim().orEmpty()
        val widgetId = p.getString(KEY_WIDGET + appWidgetId, null)?.trim().orEmpty()
        return if (deviceId.isBlank() || widgetId.isBlank()) null else Selection(deviceId, widgetId)
    }

    fun save(context: Context, appWidgetId: Int, deviceId: String, widgetId: String) {
        prefs(context).edit()
            .putString(KEY_DEVICE + appWidgetId, deviceId)
            .putString(KEY_WIDGET + appWidgetId, widgetId)
            .apply()
    }

    fun clear(context: Context, appWidgetId: Int) {
        prefs(context).edit()
            .remove(KEY_DEVICE + appWidgetId)
            .remove(KEY_WIDGET + appWidgetId)
            .apply()
    }
}

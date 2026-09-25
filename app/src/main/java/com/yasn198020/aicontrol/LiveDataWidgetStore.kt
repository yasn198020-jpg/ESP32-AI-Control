package com.yasn198020.aicontrol

import android.content.Context

object LiveDataWidgetStore {
    private const val PREFS = "live_data_widget_preferences"
    private const val KEY_DEVICE = "device_"
    private const val KEY_WIDGET = "widget_"
    private const val KEY_LOW = "low_"
    private const val KEY_HIGH = "high_"
    private const val KEY_COLOR_LOW = "color_low_"
    private const val KEY_COLOR_MID = "color_mid_"
    private const val KEY_COLOR_HIGH = "color_high_"

    data class Selection(
        val deviceId: String,
        val widgetId: String,
        val low: Float = 0f,
        val high: Float = 20f,
        val colorLow: Int = 0xFF1976D2.toInt(),
        val colorMid: Int = 0xFF2E7D32.toInt(),
        val colorHigh: Int = 0xFFD32F2F.toInt()
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, appWidgetId: Int): Selection? {
        val p = prefs(context)
        val deviceId = p.getString(KEY_DEVICE + appWidgetId, null)?.trim().orEmpty()
        val widgetId = p.getString(KEY_WIDGET + appWidgetId, null)?.trim().orEmpty()
        return if (deviceId.isBlank() || widgetId.isBlank()) null else Selection(
            deviceId,
            widgetId,
            p.getString(KEY_LOW + appWidgetId, "0")?.toFloatOrNull() ?: 0f,
            p.getString(KEY_HIGH + appWidgetId, "20")?.toFloatOrNull() ?: 20f,
            p.getInt(KEY_COLOR_LOW + appWidgetId, 0xFF1976D2.toInt()),
            p.getInt(KEY_COLOR_MID + appWidgetId, 0xFF2E7D32.toInt()),
            p.getInt(KEY_COLOR_HIGH + appWidgetId, 0xFFD32F2F.toInt())
        )
    }

    fun save(context: Context, appWidgetId: Int, deviceId: String, widgetId: String, low: Float = 0f, high: Float = 20f, colorLow: Int = 0xFF1976D2.toInt(), colorMid: Int = 0xFF2E7D32.toInt(), colorHigh: Int = 0xFFD32F2F.toInt()) {
        prefs(context).edit()
            .putString(KEY_DEVICE + appWidgetId, deviceId)
            .putString(KEY_WIDGET + appWidgetId, widgetId)
            .putString(KEY_LOW + appWidgetId, low.toString())
            .putString(KEY_HIGH + appWidgetId, high.toString())
            .putInt(KEY_COLOR_LOW + appWidgetId, colorLow)
            .putInt(KEY_COLOR_MID + appWidgetId, colorMid)
            .putInt(KEY_COLOR_HIGH + appWidgetId, colorHigh)
            .apply()
    }

    fun clear(context: Context, appWidgetId: Int) {
        prefs(context).edit()
            .remove(KEY_DEVICE + appWidgetId)
            .remove(KEY_WIDGET + appWidgetId)
            .remove(KEY_LOW + appWidgetId)
            .remove(KEY_HIGH + appWidgetId)
            .remove(KEY_COLOR_LOW + appWidgetId)
            .remove(KEY_COLOR_MID + appWidgetId)
            .remove(KEY_COLOR_HIGH + appWidgetId)
            .apply()
    }
}

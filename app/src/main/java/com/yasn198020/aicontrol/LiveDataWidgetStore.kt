package com.yasn198020.aicontrol

import android.content.Context

object LiveDataWidgetStore {
    private const val PREFS = "live_data_widget_preferences"
    private const val KEY_DEVICE = "device_"
    private const val KEY_WIDGET = "widget_"
    private const val KEY_THRESHOLDS = "thresholds_"
    private const val KEY_BELOW_COLOR = "below_color_"

    data class Threshold(
        val value: Float,
        val color: Int
    )

    data class Selection(
        val deviceId: String,
        val widgetId: String,
        val thresholds: List<Threshold>,
        val belowColor: Int
    ) {
        fun colorFor(value: Float): Int {
            var color = belowColor
            for (threshold in thresholds.sortedBy { it.value }) {
                if (value < threshold.value) break
                color = threshold.color
            }
            return color
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, appWidgetId: Int): Selection? {
        val p = prefs(context)
        val deviceId = p.getString(KEY_DEVICE + appWidgetId, null)?.trim().orEmpty()
        val widgetId = p.getString(KEY_WIDGET + appWidgetId, null)?.trim().orEmpty()
        if (deviceId.isBlank() || widgetId.isBlank()) return null

        val thresholds = p.getString(KEY_THRESHOLDS + appWidgetId, null)
            ?.split(';')
            ?.mapNotNull { item ->
                val parts = item.split('|')
                if (parts.size != 2) return@mapNotNull null
                val value = parts[0].toFloatOrNull() ?: return@mapNotNull null
                val color = parts[1].toIntOrNull() ?: return@mapNotNull null
                Threshold(value, color)
            }
            ?.sortedBy { it.value }

        if (thresholds != null && thresholds.isNotEmpty()) {
            return Selection(
                deviceId = deviceId,
                widgetId = widgetId,
                thresholds = thresholds,
                belowColor = p.getInt(KEY_BELOW_COLOR + appWidgetId, 0xFF1976D2.toInt())
            )
        }

        // Backward compatibility with the previous two-threshold format.
        val low = p.getString("low_" + appWidgetId, "0")?.toFloatOrNull() ?: 0f
        val high = p.getString("high_" + appWidgetId, "20")?.toFloatOrNull() ?: 20f
        val lowColor = p.getInt("color_low_" + appWidgetId, 0xFF1976D2.toInt())
        val midColor = p.getInt("color_mid_" + appWidgetId, 0xFF2E7D32.toInt())
        val highColor = p.getInt("color_high_" + appWidgetId, 0xFFD32F2F.toInt())

        return Selection(
            deviceId = deviceId,
            widgetId = widgetId,
            thresholds = listOf(
                Threshold(low, midColor),
                Threshold(high, highColor)
            ).sortedBy { it.value },
            belowColor = lowColor
        )
    }

    fun save(
        context: Context,
        appWidgetId: Int,
        deviceId: String,
        widgetId: String,
        thresholds: List<Threshold>,
        belowColor: Int
    ) {
        val serialized = thresholds
            .sortedBy { it.value }
            .joinToString(";") { "${it.value}|${it.color}" }

        prefs(context).edit()
            .putString(KEY_DEVICE + appWidgetId, deviceId)
            .putString(KEY_WIDGET + appWidgetId, widgetId)
            .putString(KEY_THRESHOLDS + appWidgetId, serialized)
            .putInt(KEY_BELOW_COLOR + appWidgetId, belowColor)
            .apply()
    }

    fun clear(context: Context, appWidgetId: Int) {
        prefs(context).edit()
            .remove(KEY_DEVICE + appWidgetId)
            .remove(KEY_WIDGET + appWidgetId)
            .remove(KEY_THRESHOLDS + appWidgetId)
            .remove(KEY_BELOW_COLOR + appWidgetId)
            .remove("low_" + appWidgetId)
            .remove("high_" + appWidgetId)
            .remove("color_low_" + appWidgetId)
            .remove("color_mid_" + appWidgetId)
            .remove("color_high_" + appWidgetId)
            .apply()
    }
}

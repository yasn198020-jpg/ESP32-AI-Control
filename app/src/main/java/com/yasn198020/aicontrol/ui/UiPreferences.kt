package com.yasn198020.aicontrol.ui

import android.content.SharedPreferences

class UiPreferences(private val prefs: SharedPreferences) {
    val fontScale: Float
        get() = prefs.getFloat("ui_font_scale", 0.85f).coerceIn(0.70f, 1.10f)

    fun saveFontScale(value: Float) {
        prefs.edit()
            .putFloat("ui_font_scale", value.coerceIn(0.70f, 1.10f))
            .apply()
    }
}

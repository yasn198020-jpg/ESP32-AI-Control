package com.yasn198020.aicontrol.marfa

import com.yasn198020.aicontrol.*
import com.yasn198020.aicontrol.voice.MarfaVoiceService

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat

class MarfaToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(APP_SETTINGS_PREFS, MODE_PRIVATE)
        val active = prefs.getBoolean(MARFA_ACTIVE_PREF, false)

        if (active) {
            stopService(Intent(this, MarfaVoiceService::class.java))
            MarfaShortcutInstaller.setActive(this, false)
        } else {
            val intent = Intent(this, MarfaVoiceService::class.java)
            try {
                ContextCompat.startForegroundService(this, intent)
            } catch (_: Exception) {
            }
            MarfaShortcutInstaller.setActive(this, true)
        }

        finish()
    }
}
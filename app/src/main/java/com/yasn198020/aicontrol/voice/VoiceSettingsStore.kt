package com.yasn198020.aicontrol.voice

import android.content.SharedPreferences

/** Persistent TTS settings. Keeps voice preference storage out of the Compose screen. */
class VoiceSettingsStore(private val prefs: SharedPreferences) {
    val preset: String
        get() = prefs.getString("voice_preset", "friendly") ?: "friendly"

    val rate: Float
        get() = prefs.getFloat("voice_rate", 0.92f)

    val pitch: Float
        get() = prefs.getFloat("voice_pitch", 1.05f)

    val voiceName: String
        get() = prefs.getString("tts_voice", "") ?: ""

    fun savePreset(id: String, rate: Float, pitch: Float) {
        prefs.edit()
            .putString("voice_preset", id)
            .putFloat("voice_rate", rate)
            .putFloat("voice_pitch", pitch)
            .apply()
    }

    fun saveVoiceName(name: String) {
        prefs.edit()
            .putString("tts_voice", name)
            .apply()
    }
}

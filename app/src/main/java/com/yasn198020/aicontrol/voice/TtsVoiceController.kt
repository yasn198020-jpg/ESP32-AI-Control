package com.yasn198020.aicontrol.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

class TtsVoiceController(
    private val context: Context,
    private val speech: TextToSpeech
) {
    fun apply(name: String, rate: Float, pitch: Float) {
        try {
            speech.language = Locale("ru", "RU")
            if (name.isNotBlank()) {
                speech.voices.firstOrNull { it.name == name }?.let { speech.voice = it }
            }
            speech.setSpeechRate(rate)
            speech.setPitch(pitch)
        } catch (_: Exception) {
        }
    }

    fun availableVoices(): List<Voice> =
        try {
            speech.voices.sortedWith(
                compareBy<Voice> { it.locale.language != "ru" }
                    .thenBy { it.locale.displayName }
                    .thenBy { it.name }
            )
        } catch (_: Exception) {
            emptyList()
        }

    fun saveSelection(name: String) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putString("tts_voice", name)
            .apply()
    }

    fun shutdown() {
        try {
            speech.stop()
            speech.shutdown()
        } catch (_: Exception) {
        }
    }
}

package com.yasn198020.aicontrol.ui

import java.util.Locale

data class VoicePreset(
    val id: String,
    val title: String,
    val description: String,
    val rate: Float,
    val pitch: Float
)

val voicePresets = listOf(
    VoicePreset("soft", "🌸 Нежный", "Мягкий и спокойный", 0.88f, 1.12f),
    VoicePreset("friendly", "😊 Дружелюбный", "Тёплый и естественный", 0.92f, 1.05f),
    VoicePreset("natural", "🎧 Естественный", "Более нейтральный", 0.98f, 1.00f),
    VoicePreset("assistant", "🤖 Ассистент", "Чёткий и спокойный", 0.94f, 0.96f)
)

fun voiceLanguageLabel(locale: Locale): String {
    val language = locale.getDisplayLanguage(Locale("ru", "RU")).ifBlank { locale.language }
    val country = locale.getDisplayCountry(Locale("ru", "RU"))
    return if (country.isBlank()) language else "$language — $country"
}

fun voiceLabel(voice: android.speech.tts.Voice): String = voice.name

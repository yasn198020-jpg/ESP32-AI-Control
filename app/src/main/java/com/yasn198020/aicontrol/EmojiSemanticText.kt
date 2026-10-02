package com.yasn198020.aicontrol

import android.icu.lang.UCharacter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Canonical text representation used by Marfa internally.
 *
 * The original page/title text is never changed in the UI. Emoji are converted
 * once into short Russian semantic words so search and Gemma do not need to
 * reason about Unicode code points or long English Unicode names.
 */
object EmojiSemanticText {
    private val cache = ConcurrentHashMap<String, String>()

    // Common IoTManager page symbols. Both the plain and variation-selector
    // forms are covered because Android may preserve either representation.
    private val replacements = linkedMapOf(
        "⚙️" to "настройки",
        "⚙" to "настройки",
        "🍅" to "помидоры",
        "🥒" to "огурцы",
        "🔨" to "инструменты",
        "🛠️" to "инструменты",
        "🛠" to "инструменты",
        "🌱" to "растения",
        "🌿" to "зелень",
        "🌡️" to "температура",
        "🌡" to "температура",
        "💧" to "вода",
        "💡" to "свет",
        "🚪" to "дверь",
        "🪟" to "окно",
        "🏠" to "дом",
        "🌿" to "зелень",
        "☀️" to "солнце",
        "☀" to "солнце",
        "🌧️" to "дождь",
        "🌧" to "дождь",
        "❄️" to "холод",
        "❄" to "холод",
        "🔥" to "нагрев",
        "🔌" to "питание",
        "📡" to "связь",
        "📈" to "графики",
        "📊" to "статистика",
        "🔔" to "уведомления"
    )

    fun normalize(value: String): String {
        if (value.isBlank()) return value
        return cache.getOrPut(value) {
            normalizeUncached(value)
        }
    }

    fun isEmojiOnly(value: String): Boolean {
        if (value.isBlank()) return false
        return normalize(value).isBlank() || value.none { it.isLetterOrDigit() || it.isWhitespace() }
    }

    private fun normalizeUncached(value: String): String {
        var text = value
        replacements.forEach { (emoji, meaning) ->
            text = text.replace(emoji, " $meaning ")
        }

        // Do not leak raw emoji/code points into Marfa's semantic representation.
        // Unknown symbols get a short cached Unicode-name fallback. This is a
        // compatibility path, not the hot inference loop.
        val out = StringBuilder(text.length + 16)
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            val chars = String(Character.toChars(codePoint))
            val isWord = Character.isLetterOrDigit(codePoint) || Character.isWhitespace(codePoint)
            if (isWord) {
                out.append(chars)
            } else {
                val name = runCatching { UCharacter.getName(codePoint) }.getOrNull()
                if (!name.isNullOrBlank() &&
                    !name.startsWith("VARIATION SELECTOR", ignoreCase = true)) {
                    out.append(' ')
                    out.append(translateUnicodeName(name))
                    out.append(' ')
                } else {
                    out.append(' ')
                }
            }
            offset += Character.charCount(codePoint)
        }

        return out.toString()
            .lowercase(Locale("ru", "RU"))
            .replace('ё', 'е')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun translateUnicodeName(name: String): String {
        val normalized = name.replace('_', ' ').lowercase(Locale.ROOT)
        val known = mapOf(
            "tomato" to "помидоры",
            "cucumber" to "огурцы",
            "gear" to "настройки",
            "hammer and wrench" to "инструменты",
            "hammer" to "инструменты",
            "door" to "дверь",
            "door" to "дверь",
            "window" to "окно",
            "house" to "дом",
            "seedling" to "растения",
            "herb" to "зелень",
            "thermometer" to "температура",
            "droplet" to "вода",
            "light bulb" to "свет",
            "plug" to "питание",
            "satellite antenna" to "связь",
            "chart" to "графики",
            "bell" to "уведомления",
            "sun" to "солнце",
            "cloud" to "облако",
            "snowflake" to "холод",
            "fire" to "нагрев"
        )
        known[normalized]?.let { return it }
        return normalized
            .removePrefix("variation selector ")
            .replace(Regex("\\s+"), " ")
    }
}

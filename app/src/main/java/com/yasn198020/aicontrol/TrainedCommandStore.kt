package com.yasn198020.aicontrol

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class TrainedVoiceCommand(
    val phrase: String,
    val deviceId: String,
    val widgetId: String,
    val value: String,
    val variants: List<String> = emptyList()
)

class TrainedCommandStore(private val prefs: SharedPreferences) {
    private val key = "trained_voice_commands"

    fun load(): List<TrainedVoiceCommand> {
        val raw = prefs.getString(key, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val phrase = o.optString("phrase")
                    val variants = buildList {
                        val a = o.optJSONArray("variants")
                        if (a != null) {
                            for (j in 0 until a.length()) {
                                val v = a.optString(j).trim()
                                if (v.isNotBlank()) add(v)
                            }
                        }
                    }.ifEmpty { listOf(phrase) }
                    add(
                        TrainedVoiceCommand(
                            phrase = phrase,
                            deviceId = o.optString("deviceId"),
                            widgetId = o.optString("widgetId"),
                            value = o.optString("value"),
                            variants = variants.distinct()
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(command: TrainedVoiceCommand) {
        val commands = load()
            .filterNot {
                it.phrase.equals(command.phrase, true) &&
                    it.deviceId == command.deviceId &&
                    it.widgetId == command.widgetId
            }
            .toMutableList()
        commands.add(command.copy(variants = (command.variants + command.phrase).distinct()))
        save(commands)
    }

    fun addVariant(phrase: String, variant: String) {
        val cleanPhrase = phrase.trim()
        val cleanVariant = variant.trim()
        if (cleanPhrase.isBlank() || cleanVariant.isBlank()) return

        val commands = load().map { command ->
            if (command.phrase.equals(cleanPhrase, true)) {
                command.copy(variants = (command.variants + cleanVariant).distinct())
            } else command
        }
        save(commands)
    }

    fun remove(command: TrainedVoiceCommand) {
        save(load().filterNot {
            it.phrase == command.phrase &&
                it.deviceId == command.deviceId &&
                it.widgetId == command.widgetId &&
                it.value == command.value
        })
    }

    fun clear() {
        prefs.edit().remove(key).apply()
    }

    private fun save(commands: List<TrainedVoiceCommand>) {
        val array = JSONArray()
        commands.forEach {
            array.put(
                JSONObject()
                    .put("phrase", it.phrase)
                    .put("deviceId", it.deviceId)
                    .put("widgetId", it.widgetId)
                    .put("value", it.value)
                    .put("variants", JSONArray(it.variants.distinct()))
            )
        }
        prefs.edit().putString(key, array.toString()).apply()
    }
}

class TrainedCommandMatcher(private val store: TrainedCommandStore) {
    fun match(text: String): TrainedVoiceCommand? = matchAll(text).firstOrNull()

    fun matchAll(text: String): List<TrainedVoiceCommand> {
        val normalized = normalize(text)
        if (normalized.isBlank()) return emptyList()

        val commands = store.load()
        if (commands.isEmpty()) return emptyList()

        // 1. Exact phrase/variant match.
        val exact = commands.filter { command ->
            command.variants.any { normalize(it) == normalized } ||
                normalize(command.phrase) == normalized
        }
        if (exact.isNotEmpty()) return exact

        // 2. A spoken sentence may contain several trained commands.
        // Collect every distinct phrase group instead of selecting only the longest one.
        val contained = commands
            .filter { command ->
                command.variants.any { variant ->
                    val v = normalize(variant)
                    v.isNotBlank() && containsPhrase(normalized, v)
                }
            }
            .groupBy { normalize(it.phrase) }

        if (contained.isNotEmpty()) {
            return contained.values.flatten()
        }

        // 3. Fuzzy matching for speech-recognition errors.
        // We compare each trained variant with the spoken text and also with
        // individual windows of the same number of words.
        val candidates = commands
            .groupBy { normalize(it.phrase) }
            .mapNotNull { (phraseKey, group) ->
                val variants = group
                    .flatMap { it.variants + it.phrase }
                    .map(::normalize)
                    .filter { it.isNotBlank() }
                    .distinct()

                val score = variants.maxOfOrNull { similarityAgainstText(normalized, it) } ?: 0.0
                if (score >= 0.78) phraseKey to score else null
            }

        if (candidates.isEmpty()) return emptyList()

        val bestScore = candidates.maxOf { it.second }
        if (bestScore < 0.78) return emptyList()

        val bestGroups = candidates
            .filter { it.second >= bestScore - 0.04 }
            .map { it.first }
            .toSet()

        return commands.filter { normalize(it.phrase) in bestGroups }
    }

    private fun containsPhrase(text: String, phrase: String): Boolean {
        if (text == phrase) return true
        return (" $text ").contains(" $phrase ")
    }

    private fun similarityAgainstText(text: String, phrase: String): Double {
        if (text == phrase) return 1.0
        if (text.contains(phrase)) return 0.96

        val textWords = text.split(" ").filter { it.isNotBlank() }
        val phraseWords = phrase.split(" ").filter { it.isNotBlank() }
        if (phraseWords.isEmpty() || textWords.isEmpty()) return 0.0

        val windows = mutableListOf<String>()
        val sizes = (maxOf(1, phraseWords.size - 1)..minOf(textWords.size, phraseWords.size + 1))
        for (size in sizes) {
            for (i in 0..(textWords.size - size)) {
                windows += textWords.subList(i, i + size).joinToString(" ")
            }
        }

        return windows.maxOfOrNull { window ->
            val charScore = normalizedEditSimilarity(window, phrase)
            val tokenScore = tokenSimilarity(window, phrase)
            maxOf(charScore, tokenScore)
        } ?: 0.0
    }

    private fun tokenSimilarity(a: String, b: String): Double {
        val aa = a.split(" ").filter { it.isNotBlank() }.map(::stem).toSet()
        val bb = b.split(" ").filter { it.isNotBlank() }.map(::stem).toSet()
        if (aa.isEmpty() || bb.isEmpty()) return 0.0
        val common = aa.intersect(bb).size.toDouble()
        return (2.0 * common) / (aa.size + bb.size)
    }

    private fun stem(word: String): String {
        val w = word.lowercase()
        val endings = listOf(
            "иями", "ами", "ого", "ему", "ому", "ыми", "ими",
            "ать", "ить", "еть", "ять", "уй", "ой", "ый", "ий",
            "ая", "яя", "ое", "ее", "ые", "ие", "ы", "и", "а", "я", "о", "е"
        )
        for (ending in endings) {
            if (w.length > ending.length + 2 && w.endsWith(ending)) {
                return w.removeSuffix(ending)
            }
        }
        return w
    }

    private fun normalizedEditSimilarity(a: String, b: String): Double {
        val maxLength = maxOf(a.length, b.length)
        if (maxLength == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxLength
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost
                )
            }
            previous = current
        }
        return previous[b.length]
    }

    private fun normalize(value: String): String =
        value.lowercase()
            .replace('ё', 'е')
            .replace(Regex("[^a-zа-я0-9]+"), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
}

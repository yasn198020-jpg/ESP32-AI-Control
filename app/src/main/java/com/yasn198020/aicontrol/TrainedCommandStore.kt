package com.yasn198020.aicontrol

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class TrainedVoiceCommand(
    val phrase: String,
    val deviceId: String,
    val widgetId: String,
    val value: String
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
                    add(
                        TrainedVoiceCommand(
                            o.optString("phrase"),
                            o.optString("deviceId"),
                            o.optString("widgetId"),
                            o.optString("value")
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
        commands.add(command)
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

    private fun save(commands: List<TrainedVoiceCommand>) {
        val array = JSONArray()
        commands.forEach {
            array.put(
                JSONObject()
                    .put("phrase", it.phrase)
                    .put("deviceId", it.deviceId)
                    .put("widgetId", it.widgetId)
                    .put("value", it.value)
            )
        }
        prefs.edit().putString(key, array.toString()).apply()
    }
}

class TrainedCommandMatcher(private val store: TrainedCommandStore) {
    fun match(text: String): TrainedVoiceCommand? {
        val normalized = normalize(text)
        if (normalized.isBlank()) return null

        val exact = store.load().firstOrNull { normalize(it.phrase) == normalized }
        if (exact != null) return exact

        // Also accept a trained phrase when the spoken command contains it.
        return store.load()
            .filter { normalized.contains(normalize(it.phrase)) }
            .maxByOrNull { normalize(it.phrase).length }
    }

    private fun normalize(value: String): String =
        value.lowercase()
            .replace('ё', 'е')
            .replace(Regex("[^a-zа-я0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
}

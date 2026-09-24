package com.yasn198020.aicontrol.voice

import android.content.SharedPreferences
import com.yasn198020.aicontrol.devices.WidgetState

data class TrainingTarget(
    val deviceId: String,
    val widgetId: String,
    val title: String
)

data class TrainingSaveResult(
    val success: Boolean,
    val message: String,
    val phrase: String = ""
)

class VoiceTrainingController(prefs: SharedPreferences) {
    private val store = TrainedCommandStore(prefs)
    private val matcher = TrainedCommandMatcher(store)

    fun load(): List<TrainedVoiceCommand> = store.load()

    fun matchAll(text: String): List<TrainedVoiceCommand> = matcher.matchAll(text)

    fun save(
        target: TrainingTarget,
        phrase: String,
        trainingValue: String,
        attachToExisting: Boolean,
        selectedExistingPhrase: String?
    ): TrainingSaveResult {
        val clean = phrase.trim()
        val finalPhrase = if (attachToExisting) selectedExistingPhrase?.trim().orEmpty() else clean
        if (finalPhrase.isBlank()) {
            return TrainingSaveResult(
                false,
                if (attachToExisting) "Выберите существующую команду" else "Фраза не распознана"
            )
        }

        store.add(
            TrainedVoiceCommand(
                finalPhrase,
                target.deviceId,
                target.widgetId,
                trainingValue
            )
        )
        return TrainingSaveResult(
            true,
            "Действие добавлено к команде: $finalPhrase",
            finalPhrase
        )
    }

    fun addVariant(phrase: String, variant: String): List<TrainedVoiceCommand> {
        store.addVariant(phrase, variant)
        return store.load()
    }

    fun remove(command: TrainedVoiceCommand): List<TrainedVoiceCommand> {
        store.remove(command)
        return store.load()
    }

    fun clear(): List<TrainedVoiceCommand> {
        store.clear()
        return store.load()
    }
}

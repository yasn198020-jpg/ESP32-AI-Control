package com.yasn198020.aicontrol

import android.content.Context
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class VoiceCommandManager(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null

    fun startRussian() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onStatus("Распознавание речи недоступно")
            return
        }

        stop()

        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    onStatus("Говорите…")
                }

                override fun onBeginningOfSpeech() {
                    onStatus("Слушаю…")
                }

                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    onStatus("Обрабатываю…")
                }

                override fun onError(error: Int) {
                    onStatus(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "Не удалось распознать команду"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не услышал команду"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон"
                            else -> "Ошибка распознавания: $error"
                        }
                    )
                    stop()
                }

                override fun onResults(results: android.os.Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()

                    if (text.isBlank()) {
                        onStatus("Команда не распознана")
                    } else {
                        onResult(text)
                        onStatus("Команда распознана")
                    }
                    stop()
                }

                override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
                override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            onStatus("Не удалось запустить микрофон: " + (e.message ?: "ошибка"))
            stop()
        }
    }

    fun stop() {
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }
}

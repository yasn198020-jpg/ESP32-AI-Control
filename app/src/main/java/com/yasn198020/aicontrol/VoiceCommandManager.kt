package com.yasn198020.aicontrol

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Handler
import android.os.Looper

class VoiceCommandManager(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onWakeWord: () -> Unit = {}
) {
    private var recognizer: SpeechRecognizer? = null
    private var wakeWordEnabled = false
    private val handler = Handler(Looper.getMainLooper())

    private fun hasMicrophonePermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    private fun available(): Boolean =
        hasMicrophonePermission() && SpeechRecognizer.isRecognitionAvailable(context)

    fun startWakeWord() {
        wakeWordEnabled = true

        if (!hasMicrophonePermission()) {
            onStatus("Нужно разрешение на микрофон")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onStatus("На телефоне нет доступного сервиса распознавания речи")
            return
        }

        startWakeRecognizer()
    }

    private fun startWakeRecognizer() {
        if (!wakeWordEnabled || !available()) return

        stopRecognizerOnly()

        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    onStatus("Слушаю: «Марфа»")
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                    stopRecognizerOnly()
                    if (wakeWordEnabled) {
                        handler.postDelayed({ startWakeRecognizer() }, 1200L)
                    }
                }

                override fun onResults(results: android.os.Bundle?) {
                    val resultsList = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        .orEmpty()

                    val wakeDetected = resultsList.any { containsWakeWord(it) }

                    stopRecognizerOnly()

                    if (wakeDetected && wakeWordEnabled) {
                        // Hand the microphone directly to command recognition.
                        // Do not start another wake session after the wake word.
                        wakeWordEnabled = false
                        handler.removeCallbacksAndMessages(null)
                        onStatus("Марфа услышала. Говорите команду…")
                        onWakeWord()
                    } else if (wakeWordEnabled) {
                        handler.postDelayed({ startWakeRecognizer() }, 700L)
                    }
                }

                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val partials = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        .orEmpty()

                    if (wakeWordEnabled && partials.any { containsWakeWord(it) }) {
                        // Do not restart the wake recognizer here. The UI immediately
                        // switches to command recognition after onWakeWord().
                        wakeWordEnabled = false
                        handler.removeCallbacksAndMessages(null)
                        stopRecognizerOnly()
                        onStatus("Марфа услышала. Говорите команду…")
                        onWakeWord()
                    }
                }

                override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // Keep the wake-word recognition session open longer so the microphone does not
            // repeatedly turn on/off while waiting for «Марфа».
            putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 300000L)
            putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 300000L)
            putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 300000L)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите «Марфа»")
        }

        try {
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            stopRecognizerOnly()
            if (wakeWordEnabled) {
                handler.postDelayed({ startWakeRecognizer() }, 800L)
            }
        }
    }

    fun startRussian() {
        wakeWordEnabled = false

        if (!hasMicrophonePermission()) {
            onStatus("Нет разрешения на микрофон")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onStatus("На телефоне нет доступного сервиса распознавания речи")
            return
        }

        stopRecognizerOnly()

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
                    stopRecognizerOnly()
                    startWakeWord()
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

                    stopRecognizerOnly()
                    startWakeWord()
                }

                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()

                    if (partial.isNotBlank()) {
                        onStatus("Слышу: $partial")
                    }
                }

                override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // Give enough time to say the command after «Марфа».
            putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 5000L)
            putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 5000L)
            putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 5000L)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите команду")
        }

        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            onStatus("Не удалось запустить микрофон: " + (e.message ?: "ошибка"))
            stopRecognizerOnly()
            startWakeWord()
        }
    }

    private fun containsWakeWord(text: String): Boolean {
        val normalized = text
            .lowercase()
            .replace('ё', 'е')
            .replace(Regex("[^а-яa-z0-9]+"), " ")
            .trim()

        return normalized.split(Regex("\\s+"))
            .any { it == "марфа" || it == "марфу" || it == "марфе" || it == "марфой" }
    }

    private fun stopRecognizerOnly() {
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    fun stop() {
        wakeWordEnabled = false
        handler.removeCallbacksAndMessages(null)
        stopRecognizerOnly()
    }
}

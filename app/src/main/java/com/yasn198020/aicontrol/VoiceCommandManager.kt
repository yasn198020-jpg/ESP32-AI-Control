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
    private var listening = false
    private var lastPartialText = ""
    private val handler = Handler(Looper.getMainLooper())

    private fun hasMicrophonePermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    private fun available(): Boolean =
        hasMicrophonePermission() && SpeechRecognizer.isRecognitionAvailable(context)

    fun startWakeWord() {
        startListening()
    }

    private fun startListening() {
        if (!available()) {
            onStatus(
                if (!hasMicrophonePermission()) {
                    "Нужно разрешение на микрофон"
                } else {
                    "На телефоне нет доступного сервиса распознавания речи"
                }
            )
            return
        }

        if (listening && recognizer != null) return

        listening = true
        handler.removeCallbacksAndMessages(null)
        releaseRecognizer()
        lastPartialText = ""

        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    onStatus("Слушаю…")
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    onStatus("Обрабатываю…")
                }

                override fun onError(error: Int) {
                    releaseRecognizer()
                    if (listening) {
                        handler.postDelayed({ startListening() }, 1500L)
                    }
                }

                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()

                    if (partial.isBlank()) return
                    lastPartialText = partial
                    onStatus("Слышу: $partial")
                }

                override fun onResults(results: android.os.Bundle?) {
                    val candidates = buildList {
                        if (lastPartialText.isNotBlank()) add(lastPartialText)
                        addAll(
                            results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                .orEmpty()
                        )
                    }

                    val text = candidates
                        .asSequence()
                        .map { normalizeCommand(it) }
                        .firstOrNull { it.isNotBlank() }
                        .orEmpty()

                    releaseRecognizer()
                    lastPartialText = ""

                    if (!listening) return

                    if (text.isNotBlank()) {
                        onStatus("Команда: $text")
                        onResult(text)
                    }

                    handler.postDelayed({ startListening() }, 1000L)
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
            putExtra(
                "android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS",
                30000L
            )
            putExtra(
                "android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS",
                30000L
            )
            putExtra(
                "android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS",
                1000L
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        try {
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            releaseRecognizer()
            if (listening) {
                handler.postDelayed({ startListening() }, 1500L)
            }
        }
    }

    fun startRussian() {
        startListening()
    }

    private fun normalizeCommand(text: String): String {
        val normalized = text
            .lowercase()
            .replace('ё', 'е')
            .replace(Regex("[^а-яa-z0-9]+"), " ")
            .trim()

        if (normalized.isBlank()) return ""

        val words = normalized.split(Regex("\s+"))
        val wakeIndex = words.indexOfFirst {
            it == "марфа" || it == "марфу" || it == "марфе" || it == "марфой"
        }

        return if (wakeIndex >= 0) {
            words.drop(wakeIndex + 1).joinToString(" ").trim()
        } else {
            normalized
        }
    }

    private fun releaseRecognizer() {
        val current = recognizer ?: return
        recognizer = null
        try {
            current.stopListening()
            current.cancel()
        } catch (_: Exception) {
        }
        try {
            current.destroy()
        } catch (_: Exception) {
        }
    }

    fun stop() {
        listening = false
        lastPartialText = ""
        handler.removeCallbacksAndMessages(null)
        releaseRecognizer()
    }
}

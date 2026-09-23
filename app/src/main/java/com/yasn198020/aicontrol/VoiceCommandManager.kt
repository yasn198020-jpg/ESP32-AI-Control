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
    private var commandMode = false
    private var wakeWordDetected = false
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
        wakeWordEnabled = true
        commandMode = false
        wakeWordDetected = false
        lastPartialText = ""

        if (!hasMicrophonePermission()) {
            onStatus("Нужно разрешение на микрофон")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onStatus("На телефоне нет доступного сервиса распознавания речи")
            return
        }

        startRecognitionSession()
    }

    private fun startRecognitionSession() {
        if (!wakeWordEnabled || !available()) return

        stopRecognizerOnly()
        wakeWordDetected = false
        lastPartialText = ""

        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    onStatus(if (commandMode) "Говорите команду…" else "Слушаю: «Марфа»")
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    onStatus("Обрабатываю…")
                }

                override fun onError(error: Int) {
                    stopRecognizerOnly()
                    if (wakeWordEnabled) {
                        val delay = if (commandMode) 350L else 900L
                        handler.postDelayed({ startRecognitionSession() }, delay)
                    }
                }

                override fun onResults(results: android.os.Bundle?) {
                    val resultsList = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        .orEmpty()

                    val candidates = buildList {
                        if (lastPartialText.isNotBlank()) add(lastPartialText)
                        addAll(resultsList)
                    }

                    val detected = wakeWordDetected || candidates.any { containsWakeWord(it) }
                    stopRecognizerOnly()

                    if (!wakeWordEnabled) return

                    if (detected) {
                        val command = candidates
                            .asSequence()
                            .map { extractCommandAfterWakeWord(it) }
                            .firstOrNull { it.isNotBlank() }
                            .orEmpty()

                        if (command.isNotBlank()) {
                            commandMode = false
                            wakeWordDetected = false
                            lastPartialText = ""
                            onStatus("Команда: $command")
                            onResult(command)
                            handler.removeCallbacksAndMessages(null)
                            handler.postDelayed({ startRecognitionSession() }, 450L)
                        } else {
                            commandMode = true
                            wakeWordDetected = false
                            lastPartialText = ""
                            onStatus("Марфа услышала. Говорите команду…")
                            onWakeWord()
                            handler.removeCallbacksAndMessages(null)
                            handler.postDelayed({ startRecognitionSession() }, 120L)
                        }
                    } else {
                        commandMode = false
                        wakeWordDetected = false
                        lastPartialText = ""
                        handler.postDelayed({ startRecognitionSession() }, 350L)
                    }
                }

                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val partials = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        .orEmpty()

                    val partial = partials.firstOrNull()?.trim().orEmpty()
                    if (partial.isBlank()) return

                    lastPartialText = partial

                    if (!commandMode && containsWakeWord(partial)) {
                        wakeWordDetected = true
                        onStatus("Марфа услышала. Продолжайте…")
                    } else if (commandMode) {
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

            if (commandMode) {
                putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 5000L)
                putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 5000L)
                putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 5000L)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите команду")
            } else {
                putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 300000L)
                putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 300000L)
                putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 300000L)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите «Марфа»")
            }

            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        try {
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            stopRecognizerOnly()
            if (wakeWordEnabled) {
                handler.postDelayed({ startRecognitionSession() }, 800L)
            }
        }
    }

    fun startRussian() {
        wakeWordEnabled = true
        commandMode = true
        wakeWordDetected = false
        lastPartialText = ""
        handler.removeCallbacksAndMessages(null)
        startRecognitionSession()
    }

    private fun extractCommandAfterWakeWord(text: String): String {
        val normalized = text
            .lowercase()
            .replace('ё', 'е')
            .replace(Regex("[^а-яa-z0-9]+"), " ")
            .trim()

        val words = normalized.split(Regex("\s+"))
        val index = words.indexOfFirst {
            it == "марфа" || it == "марфу" || it == "марфе" || it == "марфой"
        }

        if (index < 0 || index + 1 >= words.size) return ""
        return words.drop(index + 1).joinToString(" ").trim()
    }

    private fun containsWakeWord(text: String): Boolean {
        val normalized = text
            .lowercase()
            .replace('ё', 'е')
            .replace(Regex("[^а-яa-z0-9]+"), " ")
            .trim()

        return normalized.split(Regex("\s+"))
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
        commandMode = false
        wakeWordDetected = false
        lastPartialText = ""
        handler.removeCallbacksAndMessages(null)
        stopRecognizerOnly()
    }
}

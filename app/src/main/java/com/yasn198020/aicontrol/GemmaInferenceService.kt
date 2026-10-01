package com.yasn198020.aicontrol

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale

class GemmaInferenceService : Service() {

    companion object {
        const val EXTRA_COMMAND = "command"
        const val EXTRA_CATALOG = "catalog"
        const val EXTRA_RESULT = "result"
        const val EXTRA_RAW_PROMPT = "raw_prompt"
        const val EXTRA_MAX_TOKENS = "max_tokens"

        private const val MODEL_FILE_NAME = "marfa-gemma.gguf"
        private const val CONTEXT_SIZE = 768
        private const val MAX_THREADS = 6
        private const val MAX_TOKENS = 96

        private const val CHAT_SYSTEM_PROMPT = """
Ты локальный семантический интерпретатор команд IoTManager.
Твоя задача — понять смысл русской фразы пользователя и выбрать существующий объект.
Учитывай падежи, окончания, разговорные формы, местоимения и смысловой контекст.
Не требуй точного совпадения слов и не используй фиксированный словарь предметов.
Например, «помидор», «помидора», «помидорами» должны восприниматься как один смысл,
но тот же принцип применяй к любому другому слову и предмету.
Связывай контекст пользователя с полями device, page и title из CATALOG.
«закрой дверь» — это объект двери, даже если рядом есть элементы с названиями
«закрыть», «открыть» или похожими словами.
Приоритет для управления: логический объект/состояние, а не физическое реле/GPIO,
если пользователь прямо не попросил реле, выход или канал.
Никогда не придумывай widgetId. Используй только ID из CATALOG.
Если подходящего объекта нет — not_found.
Если несколько объектов подходят одинаково хорошо — clarify.
Не выполняй MQTT, сценарии, ручной режим и зависимости: это делает приложение.
Верни ТОЛЬКО JSON без Markdown:
{"kind":"control|read_value|clarify|not_found","widgetId":"","value":"","delayMs":0,"reply":""}
CATALOG fields: id, device, page, title, type.
"""
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loadedModel: LlamaModel? = null
    private var loadedPath = ""
    private val inferenceMutex = Mutex()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent?.getStringExtra(EXTRA_COMMAND).orEmpty()
        val rawPrompt = intent?.getStringExtra(EXTRA_RAW_PROMPT).orEmpty()
        val catalog = intent?.getStringExtra(EXTRA_CATALOG).orEmpty()
        val requestedMaxTokens = intent?.getIntExtra(EXTRA_MAX_TOKENS, MAX_TOKENS)?.coerceIn(1, MAX_TOKENS) ?: MAX_TOKENS
        val receiver = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT, ResultReceiver::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT)
        }

        scope.launch {
            val startedAt = System.currentTimeMillis()
            fun stage(message: String) {
                receiver?.send(2, Bundle().apply {
                    putString("stage", message)
                    putLong("elapsed", System.currentTimeMillis() - startedAt)
                })
            }
            try {
                inferenceMutex.withLock {
                    stage("Сервис получил запрос")
                    if (command.isBlank() && rawPrompt.isBlank()) throw Exception("Пустая команда")
                    if (rawPrompt.isBlank() && catalog.isBlank()) throw Exception("Пустой каталог виджетов")

                    stage("Загрузка модели")
                    val model = loadModel()
                    stage("Модель загружена")
                    stage("Запуск inference: maxTokens=$requestedMaxTokens")
                    val result = if (rawPrompt.isNotBlank()) {
                        Llama.complete(
                            model = model,
                            prompt = rawPrompt,
                            systemPrompt = "Ты обычный русскоязычный помощник. Отвечай естественно и кратко.",
                            maxTokens = requestedMaxTokens
                        )
                    } else {
                        Llama.complete(
                            model = model,
                            prompt = "КОМАНДА:\n" + command + "\n\nCATALOG:\n" + catalog,
                            systemPrompt = CHAT_SYSTEM_PROMPT,
                            maxTokens = MAX_TOKENS
                        )
                    }

                    stage("Ответ получен")
                    receiver?.send(0, Bundle().apply {
                        putString("text", result.text)
                    })
                }
            } catch (t: Throwable) {
                receiver?.send(1, Bundle().apply {
                    putString("error", t.message ?: "Ошибка нативного Gemma-движка")
                })
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun loadModel(): LlamaModel {
        val modelFile = File(
            getExternalFilesDir("models") ?: File(filesDir, "models"),
            MODEL_FILE_NAME
        )
        val path = modelFile.absolutePath

        loadedModel?.let { if (loadedPath == path) return it }

        if (!modelFile.isFile || modelFile.length() <= 1_000_000L) {
            throw Exception("Файл Gemma GGUF не найден или повреждён")
        }

        val memory = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        memory.getMemoryInfo(info)

        // Do not try to start a large native model when Android is already under
        // memory pressure. This check is intentionally conservative.
        val required = maxOf(
            3_500L * 1024L * 1024L,
            modelFile.length() * 5L / 4L + 768L * 1024L * 1024L
        )
        if (info.availMem < required) {
            throw Exception(
                "Недостаточно RAM для Gemma: свободно " +
                    formatBytes(info.availMem) + ", нужно примерно " +
                    formatBytes(required)
            )
        }

        loadedModel?.let { runCatching { Llama.releaseModel(it) } }
        loadedModel = Llama.loadModel(
            path,
            LlamaConfig(
                contextSize = CONTEXT_SIZE,
                threads = minOf(MAX_THREADS, maxOf(2, Runtime.getRuntime().availableProcessors() - 1)),
                gpuLayers = 0,
                temperature = 0.1f,
                topP = 0.9f,
                topK = 40
            )
        )
        loadedPath = path
        return loadedModel!!
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) String.format(Locale.US, "%.1f ГБ", mb)
        else String.format(Locale.US, "%.0f МБ", mb)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        loadedModel?.let { runCatching { Llama.releaseModel(it) } }
        loadedModel = null
        loadedPath = ""
        scope.cancel()
        super.onDestroy()
    }
}

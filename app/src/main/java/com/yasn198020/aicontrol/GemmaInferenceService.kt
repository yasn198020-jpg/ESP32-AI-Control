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
import org.json.JSONObject
import java.io.File
import java.util.Locale

class GemmaInferenceService : Service() {

    companion object {
        const val EXTRA_COMMAND = "command"
        const val EXTRA_CATALOG = "catalog"
        const val EXTRA_RESULT = "result"
        const val EXTRA_RAW_PROMPT = "raw_prompt"
        const val EXTRA_MAX_TOKENS = "max_tokens"
        const val EXTRA_THREADS = "threads"
        const val EXTRA_CONTEXT = "context"

        private const val MODEL_FILE_NAME = "marfa-gemma3-1b-q4km.gguf"
        private const val MODEL_URL = "https://huggingface.co/ggml-org/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf"
        private const val MODEL_MIN_BYTES = 700L * 1024L * 1024L
        private const val DEFAULT_CONTEXT_SIZE = 768
        private const val MIN_CONTEXT_SIZE = 128
        private const val MAX_CONTEXT_SIZE = 768
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
    private var loadedThreads = 0
    private var loadedContext = 0

    private var nativeHandle = 0L
    private var nativePath = ""
    private var nativeThreads = 0
    private var nativeContext = 0

    private val inferenceMutex = Mutex()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent?.getStringExtra(EXTRA_COMMAND).orEmpty()
        val rawPrompt = intent?.getStringExtra(EXTRA_RAW_PROMPT).orEmpty()
        val catalog = intent?.getStringExtra(EXTRA_CATALOG).orEmpty()
        val requestedMaxTokens = intent?.getIntExtra(EXTRA_MAX_TOKENS, MAX_TOKENS)?.coerceIn(1, MAX_TOKENS) ?: MAX_TOKENS
        val requestedThreads = intent?.getIntExtra(EXTRA_THREADS, 0)?.takeIf { it > 0 }?.coerceIn(1, MAX_THREADS) ?: 0
        val requestedContext = intent?.getIntExtra(EXTRA_CONTEXT, DEFAULT_CONTEXT_SIZE)?.coerceIn(MIN_CONTEXT_SIZE, MAX_CONTEXT_SIZE) ?: DEFAULT_CONTEXT_SIZE
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

                    stage("Проверка облегчённой модели Gemma 3 1B")
                    ensureModelFile { message -> stage(message) }

                    if (MarfaLlamaNative.isAvailable()) {
                        stage("Нативный llama.cpp backend: arm64-v8a + KleidiAI")
                        val handle = loadNativeModel(requestedThreads, requestedContext)
                        stage(
                            "Модель загружена native: " +
                                formatBytes(modelFileSize()) +
                                " (" + modelFileSize() + " байт), ABI=" +
                                android.os.Build.SUPPORTED_ABIS.joinToString(",") +
                                ", llama.cpp=" + MarfaLlamaNative.nativeVersion(handle)
                        )

                        val prompt = if (rawPrompt.isNotBlank()) {
                            rawPrompt
                        } else {
                            "КОМАНДА:\n" + command + "\n\nCATALOG:\n" + catalog
                        }
                        val systemPrompt = if (rawPrompt.isNotBlank()) {
                            "Ты обычный русскоязычный помощник. Отвечай естественно и кратко."
                        } else {
                            CHAT_SYSTEM_PROMPT
                        }
                        val maxTokens = if (rawPrompt.isNotBlank()) requestedMaxTokens else MAX_TOKENS

                        stage(
                            "Запуск НАТИВНОГО llama.cpp inference: maxTokens=" +
                                maxTokens +
                                ", threads=" +
                                (if (requestedThreads > 0) requestedThreads else "auto") +
                                ", context=$requestedContext"
                        )

                        val nativeJson = JSONObject(
                            MarfaLlamaNative.nativeGenerate(
                                handle = handle,
                                prompt = prompt,
                                systemPrompt = systemPrompt,
                                maxTokens = maxTokens
                            )
                        )
                        val nativeError = nativeJson.optString("error").trim()
                        if (nativeError.isNotBlank()) {
                            throw Exception("Нативный llama.cpp: $nativeError")
                        }

                        val text = nativeJson.optString("text")
                        val tokensPerSecond = nativeJson.optDouble("tokensPerSecond", 0.0)
                        stage(
                            "Ответ получен native: " +
                                tokensPerSecond + " ток/с; promptTokens=" +
                                nativeJson.optInt("promptTokens", 0) +
                                "; generatedTokens=" +
                                nativeJson.optInt("generatedTokens", 0) +
                                "; textChars=" + text.length
                        )
                        receiver?.send(0, Bundle().apply {
                            putString("text", text)
                            putString("tokens_per_second", tokensPerSecond.toString())
                            putString("backend", "native-llama.cpp")
                        })
                    } else {
                        stage("Нативный backend недоступен — резервный llama-android AAR")
                        val model = loadModel(requestedThreads, requestedContext)
                        stage("Модель загружена: " + formatBytes(modelFileSize()) + " (" + modelFileSize() + " байт), ABI=" + android.os.Build.SUPPORTED_ABIS.joinToString(",") + ", CPU=" + Runtime.getRuntime().availableProcessors())
                        stage("Запуск резервного llama-android inference: maxTokens=$requestedMaxTokens, threads=" + (if (requestedThreads > 0) requestedThreads else "auto") + ", context=$requestedContext")
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

                        stage("Ответ получен AAR: " + result.tokensPerSecond + " ток/с; textChars=" + result.text.length)
                        receiver?.send(0, Bundle().apply {
                            putString("text", result.text)
                            putString("tokens_per_second", result.tokensPerSecond.toString())
                            putString("backend", "llama-android-aar")
                        })
                    }
                }
            } catch (t: Throwable) {
                receiver?.send(1, Bundle().apply {
                    putString("error", t.message ?: "Ошибка нативного Gemma-движка")
                })
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureModelFile(stage: (String) -> Unit) {
        val file = File(getExternalFilesDir("models") ?: File(filesDir, "models"), MODEL_FILE_NAME)
        if (file.isFile && file.length() >= MODEL_MIN_BYTES) return
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, MODEL_FILE_NAME + ".download")
        stage("Скачивание Gemma 3 1B Q4_K_M (~806 МБ)")
        val connection = (java.net.URL(MODEL_URL).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) throw Exception("Не удалось скачать Gemma: HTTP " + connection.responseCode)
            val total = connection.contentLengthLong
            var done = 0L
            var lastReport = -10
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent >= lastReport + 10) { lastReport = percent; stage("Скачивание Gemma: " + percent + "%") }
                        }
                    }
                }
            }
            if (temp.length() < MODEL_MIN_BYTES) throw Exception("Скачанный GGUF слишком маленький: " + formatBytes(temp.length()))
            if (file.exists()) file.delete()
            if (!temp.renameTo(file)) throw Exception("Не удалось сохранить Gemma GGUF")
            stage("Gemma 3 1B скачана: " + formatBytes(file.length()))
        } finally {
            connection.disconnect()
            if (temp.exists() && temp.length() < MODEL_MIN_BYTES) temp.delete()
        }
    }
    private suspend fun loadModel(requestedThreads: Int, requestedContext: Int): LlamaModel {
        val modelFile = File(
            getExternalFilesDir("models") ?: File(filesDir, "models"),
            MODEL_FILE_NAME
        )
        val path = modelFile.absolutePath

        val cpuThreads = requestedThreads.takeIf { it > 0 } ?: minOf(MAX_THREADS, maxOf(2, Runtime.getRuntime().availableProcessors() - 1))
        loadedModel?.let { if (loadedPath == path && loadedThreads == cpuThreads && loadedContext == requestedContext) return it }

        if (!modelFile.isFile || modelFile.length() < MODEL_MIN_BYTES) {
            throw Exception("Файл Gemma 3 1B GGUF не найден или неполный: " + formatBytes(modelFile.length()))
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
                contextSize = requestedContext,
                threads = cpuThreads,
                gpuLayers = 0,
                temperature = 0.1f,
                topP = 0.9f,
                topK = 40
            )
        )
        loadedPath = path
        loadedThreads = cpuThreads
        loadedContext = requestedContext
        return loadedModel!!
    }

    private fun loadNativeModel(requestedThreads: Int, requestedContext: Int): Long {
        val modelFile = File(
            getExternalFilesDir("models") ?: File(filesDir, "models"),
            MODEL_FILE_NAME
        )
        val path = modelFile.absolutePath
        val cpuThreads = requestedThreads.takeIf { it > 0 }
            ?: minOf(MAX_THREADS, maxOf(2, Runtime.getRuntime().availableProcessors() - 1))

        if (nativeHandle != 0L &&
            nativePath == path &&
            nativeThreads == cpuThreads &&
            nativeContext == requestedContext) {
            return nativeHandle
        }

        if (!modelFile.isFile || modelFile.length() < MODEL_MIN_BYTES) {
            throw Exception("Файл Gemma 3 1B GGUF не найден или неполный: " + formatBytes(modelFile.length()))
        }

        nativeHandle.takeIf { it != 0L }?.let {
            runCatching { MarfaLlamaNative.nativeRelease(it) }
        }

        val handle = MarfaLlamaNative.nativeLoadModel(
            modelPath = path,
            threads = cpuThreads,
            contextSize = requestedContext
        )
        if (handle == 0L) {
            throw Exception("Нативный llama.cpp не смог загрузить модель")
        }

        nativeHandle = handle
        nativePath = path
        nativeThreads = cpuThreads
        nativeContext = requestedContext
        return handle
    }

    private fun modelFileSize(): Long {
        val file = File(getExternalFilesDir("models") ?: File(filesDir, "models"), MODEL_FILE_NAME)
        return file.length()
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
        loadedThreads = 0
        loadedContext = 0
        scope.cancel()
        super.onDestroy()
    }
}

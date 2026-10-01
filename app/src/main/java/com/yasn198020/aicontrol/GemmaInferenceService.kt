package com.yasn198020.aicontrol

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import com.yasn198020.aicontrol.core.Device
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

        private const val MODEL_FILE_NAME = "marfa-gemma.gguf"
        private const val CONTEXT_SIZE = 1024
        private const val THREADS = 2
        private const val MAX_TOKENS = 160

        private const val SYSTEM_PROMPT = """
Ты локальный семантический интерпретатор голосовых команд IoTManager.
Понимай русский естественный язык, падежи, формы слов и контекст.
Выбирай только существующий объект из CATALOG. Никогда не придумывай ID.
Для управления выбирай логический виджет состояния/управления, а не физическое реле/GPIO,
если пользователь явно не попросил именно реле или выход.
Дальнейшую зависимость к реле, ручному режиму и сценарию обработает приложение.
Помидор/помидора/помидоров/помидорами — один смысловой контекст.
"закрой дверь" означает дверь, а не любой элемент со словом "закрыть".
При нескольких одинаково подходящих объектах верни clarify.
Верни ТОЛЬКО JSON:
{"kind":"control|read_value|clarify|not_found","widgetId":"","value":"","delayMs":0,"reply":""}
"""
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loadedModel: LlamaModel? = null
    private var loadedPath = ""
    private val inferenceMutex = Mutex()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent?.getStringExtra(EXTRA_COMMAND).orEmpty()
        val catalog = intent?.getStringExtra(EXTRA_CATALOG).orEmpty()
        val receiver = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT, ResultReceiver::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT)
        }

        scope.launch {
            try {
                inferenceMutex.withLock {
                    if (command.isBlank()) throw Exception("Пустая команда")
                    if (catalog.isBlank()) throw Exception("Пустой каталог виджетов")

                    val model = loadModel()
                    val result = Llama.complete(
                        model = model,
                        prompt = "КОМАНДА:\n" + command + "\n\nCATALOG:\n" + catalog,
                        systemPrompt = SYSTEM_PROMPT,
                        maxTokens = MAX_TOKENS
                    )

                    receiver?.send(0, Bundle().apply {
                        putString("text", result.text)
                    })
                }
            } catch (t: Throwable) {
                receiver?.send(1, Bundle().apply {
                    putString("error", t.message ?: "Ошибка нативного Gemma-движка")
                })
            } finally {
                stopSelfResult(startId)
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

        loadedModel?.let { if (loadedPath == path && it.isLoaded) return it }

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
                threads = THREADS,
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

package com.yasn198020.aicontrol
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class GemmaLocalEngine private constructor(private val appContext: Context) {
    companion object {
        private const val MODEL_FILE_NAME = "marfa-gemma.gguf"
        private const val CONTEXT_SIZE = 4096
        private const val THREADS = 4
        private const val MAX_TOKENS = 320
        @Volatile private var instance: GemmaLocalEngine? = null
        fun get(context: Context): GemmaLocalEngine =
            instance ?: synchronized(this) {
                instance ?: GemmaLocalEngine(context.applicationContext).also { instance = it }
            }
    }

    private val mutex = Mutex()
    private val modelFile: File
        get() = File(
            appContext.getExternalFilesDir("models") ?: File(appContext.filesDir, "models"),
            MODEL_FILE_NAME
        )

    private var loadedPath = ""
    private var loadedModel: LlamaModel? = null

    fun isModelInstalled(): Boolean = modelFile.isFile && modelFile.length() > 1_000_000L
    fun statusText(): String =
        if (isModelInstalled()) "Gemma установлена • " + formatBytes(modelFile.length())
        else "Gemma не установлена. Выберите файл GGUF."

    suspend fun importModel(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val name = queryDisplayName(uri).orEmpty()
                if (!name.lowercase(Locale.ROOT).endsWith(".gguf")) {
                    return@withContext Result.failure(Exception("Нужен файл модели в формате GGUF"))
                }
                val parent = modelFile.parentFile
                    ?: return@withContext Result.failure(Exception("Нет каталога модели"))
                if (!parent.exists() && !parent.mkdirs()) {
                    return@withContext Result.failure(Exception("Не удалось создать каталог модели"))
                }

                val temp = File(parent, MODEL_FILE_NAME + ".part")
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                } ?: return@withContext Result.failure(Exception("Не удалось открыть файл модели"))

                if (!temp.isFile || temp.length() <= 1_000_000L) {
                    temp.delete()
                    return@withContext Result.failure(Exception("Файл модели слишком маленький или повреждён"))
                }

                loadedModel?.let { runCatching { Llama.releaseModel(it) } }
                loadedModel = null
                loadedPath = ""
                if (modelFile.exists() && !modelFile.delete()) {
                    temp.delete()
                    return@withContext Result.failure(Exception("Не удалось заменить предыдущую модель"))
                }
                if (!temp.renameTo(modelFile)) {
                    temp.delete()
                    return@withContext Result.failure(Exception("Не удалось сохранить модель"))
                }
                Result.success(statusText())
            } catch (e: Throwable) {
                Result.failure(Exception(e.message ?: "Ошибка импорта модели", e))
            }
        }
    }

    suspend fun interpret(command: String, devices: List<Device>): Result<LocalCommandResult> =
        mutex.withLock {
            if (!isModelInstalled()) return@withLock Result.failure(Exception("Gemma не установлена"))
            try {
                val model = loadModelLocked()
                val catalog = buildCatalog(devices)
                val systemPrompt = """
                    Ты локальный семантический интерпретатор голосовых команд IoTManager.
                    Пользователь говорит по-русски естественно, с любыми падежами, формами слов и разговорными фразами.
                    Только пойми смысл и выбери существующий объект из CATALOG.
                    НИКОГДА не выполняй MQTT и не придумывай ID.
                    Для управления выбирай логический виджет состояния/управления, а не физическое реле/GPIO/выход,
                    если пользователь явно не сказал управлять именно реле или выходом.
                    Дальнейшую зависимость к реле, ручному режиму и сценарию обработает приложение.
                    Учитывай контекст: помидор/помидора/помидоров/помидорами — один смысловой контекст.
                    Вкладка, имя устройства и название виджета вместе описывают объект.
                    "закрой дверь" означает дверь, а не любой элемент со словом "закрыть".
                    При нескольких одинаково подходящих объектах верни clarify.
                    Результат: control, read_value, clarify или not_found.
                    Верни ТОЛЬКО JSON:
                    {"kind":"control|read_value|clarify|not_found","widgetId":"","value":"","delayMs":0,"reply":""}
                """.trimIndent()

                val result = Llama.complete(
                    model = model,
                    prompt = "КОМАНДА:\n" + command + "\n\nCATALOG:\n" + catalog,
                    systemPrompt = systemPrompt,
                    maxTokens = MAX_TOKENS
                )
                parseAndValidate(result.text, command, devices)
            } catch (e: Throwable) {
                Result.failure(Exception(e.message ?: "Ошибка Gemma", e))
            }
        }

    private suspend fun loadModelLocked(): LlamaModel {
        val path = modelFile.absolutePath
        loadedModel?.let { if (loadedPath == path) return it }
        loadedModel?.let { runCatching { Llama.releaseModel(it) } }
        loadedModel = Llama.loadModel(
            path,
            LlamaConfig(
                contextSize = CONTEXT_SIZE,
                threads = THREADS,
                gpuLayers = 0,
                temperature = 0.15f,
                topP = 0.9f,
                topK = 40
            )
        )
        loadedPath = path
        return loadedModel!!
    }

    private fun buildCatalog(devices: List<Device>): String {
        val array = JSONArray()
        devices.forEach { device ->
            device.widgets.forEach { widget ->
                array.put(JSONObject().apply {
                    put("device", device.name)
                    put("page", widget.page)
                    put("widgetId", widget.id)
                    put("title", widget.title)
                    put("type", widget.type.name)
                    put("definition", widget.definitionName)
                    put("unit", widget.unit)
                    put("value", widget.value)
                })
            }
        }
        return array.toString()
    }

    private fun parseAndValidate(rawText: String, originalCommand: String, devices: List<Device>): Result<LocalCommandResult> {
        val jsonText = extractJson(rawText) ?: return Result.failure(Exception("Gemma вернула не JSON"))
        val json = runCatching { JSONObject(jsonText) }
            .getOrElse { return Result.failure(Exception("Gemma вернула некорректный JSON")) }
        val kind = json.optString("kind").lowercase(Locale.ROOT)
        val widgetId = json.optString("widgetId").trim()
        val modelValue = json.optString("value").trim()
        val reply = json.optString("reply").trim()

        if (kind == "clarify") {
            return Result.success(LocalCommandResult(
                action = LocalCommandAction.CLARIFY,
                reply = reply.ifBlank { "Уточните, что именно выбрать." }
            ))
        }
        if (kind == "not_found") {
            return Result.success(LocalCommandResult(
                action = LocalCommandAction.NOT_FOUND,
                reply = reply.ifBlank { "Не удалось определить объект." }
            ))
        }

        val pair = devices.asSequence()
            .flatMap { device -> device.widgets.asSequence().map { device to it } }
            .firstOrNull { it.second.id == widgetId }
            ?: return Result.failure(Exception("Gemma выбрала отсутствующий widgetId: " + widgetId))

        val device = pair.first
        val widget = pair.second

        return when (kind) {
            "control" -> {
                if (widget.type != WidgetState.Type.TOGGLE &&
                    widget.type != WidgetState.Type.BUTTON &&
                    widget.type != WidgetState.Type.INPUT) {
                    Result.failure(Exception("Недоступный для управления виджет: " + widget.id))
                } else {
                    val value = normalizeControlValue(modelValue, originalCommand, widget)
                    val modelDelay = json.optLong("delayMs", 0L).coerceAtLeast(0L)
                    val fallbackDelay = if (modelDelay <= 0L)
                        LocalCommandManager().interpret(originalCommand, devices).delayMs
                    else 0L
                    val delay = if (modelDelay > 0L) modelDelay else fallbackDelay

                    Result.success(LocalCommandResult(
                        action = LocalCommandAction.CONTROL,
                        deviceId = device.id,
                        widgetId = widget.id,
                        value = value,
                        reply = safeControlReply(originalCommand, widget.title, value, delay),
                        delayMs = delay,
                        actionItems = listOf(LocalCommandActionItem(device.id, widget.id, value)),
                        needsConfirmation = true
                    ))
                }
            }
            "read_value" -> {
                if (widget.type != WidgetState.Type.VALUE &&
                    widget.type != WidgetState.Type.STATUS &&
                    widget.type != WidgetState.Type.TOGGLE &&
                    widget.type != WidgetState.Type.INPUT) {
                    Result.failure(Exception("Нечитаемый виджет: " + widget.id))
                } else {
                    val actual = widget.value.trim()
                    Result.success(LocalCommandResult(
                        action = LocalCommandAction.READ_VALUE,
                        deviceId = device.id,
                        widgetId = widget.id,
                        value = actual,
                        reply = valueReply(widget, actual)
                    ))
                }
            }
            else -> Result.failure(Exception("Неизвестный kind: " + kind))
        }
    }

    private fun normalizeControlValue(value: String, command: String, widget: WidgetState): String {
        val v = value.lowercase(Locale.ROOT).trim()
        if (widget.type == WidgetState.Type.INPUT &&
            v !in setOf("open", "close", "on", "off", "true", "false")) return value

        return when {
            v in setOf("1", "true", "on", "open", "opened", "открыть", "открой", "включить", "включи") -> "1"
            v in setOf("0", "false", "off", "close", "closed", "закрыть", "закрой", "выключить", "выключи") -> "0"
            command.lowercase(Locale.ROOT).contains("откры") ||
                command.lowercase(Locale.ROOT).contains("подним") ||
                command.lowercase(Locale.ROOT).contains("распах") -> "1"
            command.lowercase(Locale.ROOT).contains("закры") ||
                command.lowercase(Locale.ROOT).contains("опуст") ||
                command.lowercase(Locale.ROOT).contains("запечат") -> "0"
            else -> value.ifBlank { "1" }
        }
    }

    private fun safeControlReply(command: String, title: String, value: String, delayMs: Long): String {
        val n = command.lowercase(Locale("ru", "RU"))
        val action = when {
            n.contains("откры") || n.contains("подним") || n.contains("распах") -> "Открываю"
            n.contains("закры") || n.contains("опуст") || n.contains("запечат") -> "Закрываю"
            n.contains("включ") || n.contains("запусти") || n.contains("зажг") -> "Включаю"
            n.contains("выключ") || n.contains("останов") || n.contains("погаси") -> "Выключаю"
            else -> if (value == "0") "Устанавливаю" else "Включаю"
        }
        val objectTitle = title.ifBlank { "выбранный объект" }
        return if (delayMs > 0L) action + " «" + objectTitle + "» через " + formatDelay(delayMs)
        else action + " «" + objectTitle + "»"
    }

    private fun valueReply(widget: WidgetState, actual: String): String {
        val title = widget.title.ifBlank { widget.id }
        if (actual.isBlank() || actual == "—") return title + ": значение пока неизвестно"
        return if (widget.title.contains("температур", true) || widget.unit.contains("°")) {
            title + ": " + formatTemperatureForSpeech(actual, widget.unit)
        } else {
            val unit = widget.unit.trim().takeIf { it.isNotBlank() }?.let { " " + it }.orEmpty()
            title + ": " + actual + unit
        }
    }

    private fun formatDelay(delayMs: Long): String {
        val seconds = (delayMs / 1000L).coerceAtLeast(0L)
        val minutes = seconds / 60L
        val rest = seconds % 60L
        return when {
            minutes > 0 && rest > 0 -> minutes.toString() + " мин " + rest + " с"
            minutes > 0 -> minutes.toString() + " мин"
            else -> rest.toString() + " с"
        }
    }

    private fun extractJson(text: String): String? {
        val fence = 96.toChar().toString().repeat(3)
        val clean = text.replace(fence + "json", "", true).replace(fence, "").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        return if (start >= 0 && end > start) clean.substring(start, end + 1) else null
    }

    private fun queryDisplayName(uri: Uri): String? {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) return cursor.getString(0) }
        return uri.lastPathSegment
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) String.format(Locale.US, "%.1f ГБ", mb)
        else String.format(Locale.US, "%.0f МБ", mb)
    }
}

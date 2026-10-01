package com.yasn198020.aicontrol

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.OpenableColumns
import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class GemmaLocalEngine private constructor(private val appContext: Context) {
    companion object {
        private const val MODEL_FILE_NAME = "marfa-gemma3-1b-q4km.gguf"
        private const val REQUEST_TIMEOUT_MS = 120_000L
        private const val SYSTEM_PROMPT = """
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
        @Volatile private var instance: GemmaLocalEngine? = null

        fun get(context: Context): GemmaLocalEngine =
            instance ?: synchronized(this) {
                instance ?: GemmaLocalEngine(context.applicationContext).also { instance = it }
            }
    }

    private val modelFile: File
        get() = File(
            appContext.getExternalFilesDir("models") ?: File(appContext.filesDir, "models"),
            MODEL_FILE_NAME
        )

    fun isModelInstalled(): Boolean = modelFile.isFile && modelFile.length() >= 700L * 1024L * 1024L

    fun statusText(): String =
        if (isModelInstalled()) "Gemma установлена • " + formatBytes(modelFile.length())
        else "Gemma 3 1B будет загружена автоматически при первом запуске."

    suspend fun importModel(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
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

    suspend fun interpret(command: String, devices: List<Device>): Result<LocalCommandResult> =
        withContext(Dispatchers.IO) {
            val catalog = buildCatalog(devices, command)
            val resultDeferred = CompletableDeferred<Result<String>>()
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    val error = resultData?.getString("error")
                    if (resultCode == 0 && error.isNullOrBlank()) {
                        resultDeferred.complete(Result.success(resultData?.getString("text").orEmpty()))
                    } else {
                        resultDeferred.complete(Result.failure(Exception(error ?: "Ошибка Gemma")))
                    }
                }
            }

            try {
                val intent = Intent(appContext, GemmaInferenceService::class.java)
                    .putExtra(GemmaInferenceService.EXTRA_COMMAND, command)
                    .putExtra(GemmaInferenceService.EXTRA_CATALOG, catalog)
                    .putExtra(GemmaInferenceService.EXTRA_RESULT, receiver)
                appContext.startService(intent)

                val rawResult = kotlinx.coroutines.withTimeout(REQUEST_TIMEOUT_MS) {
                    resultDeferred.await()
                }
                val raw = rawResult.getOrElse { return@withContext Result.failure(it) }
                parseAndValidate(raw, command, devices)
            } catch (e: Throwable) {
                Result.failure(Exception(e.message ?: "Ошибка Gemma", e))
            }
        }

    private fun buildCatalog(devices: List<Device>, command: String = ""): String {
        data class Candidate(val device: Device, val widget: WidgetState, val score: Int)
        val commandTokens = semanticTokens(command)
        val candidates = devices.flatMap { device ->
            device.widgets.map { widget ->
                val haystack = semanticTokens(device.name + " " + widget.page + " " + widget.title + " " + widget.definitionName)
                var score = commandTokens.count { it in haystack } * 10
                if (widget.type == WidgetState.Type.TOGGLE ||
                    widget.type == WidgetState.Type.BUTTON ||
                    widget.type == WidgetState.Type.INPUT) score += 2
                Candidate(device, widget, score)
            }
        }.sortedByDescending { it.score }
        val selected = if (commandTokens.isEmpty()) candidates.take(24)
        else candidates.filter { it.score > 0 }.take(24).ifEmpty { candidates.take(24) }
        val array = JSONArray()
        selected.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.widget.id)
                put("device", item.device.name)
                put("page", item.widget.page)
                put("title", item.widget.title)
                put("type", item.widget.type.name)
            })
        }
        return array.toString()
    }

    private fun semanticTokens(value: String): Set<String> {
        val stop = setOf("открой","открыть","открывай","закрой","закрыть","закрывай",
            "включи","включить","выключи","выключить","запусти","запустить",
            "останови","остановить","покажи","скажи","узнай","какая","какое","какие",
            "сколько","температура","температур","градус","градуса","через","спустя",
            "пожалуйста","марфа","там","здесь","у","в","на","для","где","около","возле","рядом","и","а","то","же")
        return value.lowercase(Locale("ru","RU")).replace('ё','е')
            .replace(Regex("[^a-zа-я0-9]+"), " ").trim().split(Regex("\\s+"))
            .map { stem(it) }.filter { it.length >= 3 && it !in stop && !it.all(Char::isDigit) }.toSet()
    }

    private fun stem(word: String): String {
        val endings = listOf("иями","ами","ого","ему","ому","ыми","ими","ая","яя","ое","ее","ые","ие",
            "ать","ить","еть","ять","ой","ый","ий","ов","ев","ам","ям","ах","ях","ы","и","а","я","о","е")
        for (ending in endings) {
            if (word.length > ending.length + 2 && word.endsWith(ending)) return word.removeSuffix(ending)
        }
        return word
    }
    fun validateRawResult(rawText: String, originalCommand: String, devices: List<Device>): Result<LocalCommandResult> = parseAndValidate(rawText, originalCommand, devices)

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
                        needsConfirmation = false
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

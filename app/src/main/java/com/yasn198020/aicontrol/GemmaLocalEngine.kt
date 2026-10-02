package com.yasn198020.aicontrol

import android.content.Context
import android.icu.lang.UCharacter
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

data class GemmaChainDiagnostic(
    val catalog: String,
    val raw: String,
    val kind: String,
    val candidateIndex: Int,
    val modelValue: String,
    val modelReply: String,
    val gemmaDeviceId: String = "",
    val gemmaWidgetId: String = "",
    val gemmaWidgetTitle: String = "",
    val gemmaPage: String = "",
    val analyticalCandidate: String = "",
    val analyticalCandidates: List<String> = emptyList(),
    val analyticalClarification: String? = null,
    val scenarioTarget: String = "",
    val finalResult: LocalCommandResult? = null,
    val finalError: String? = null,
    val timings: List<String> = emptyList()
)
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
Не придумывай ID. Выбирай только candidateIndex из CATALOG.
Если подходящего объекта нет — not_found.
Если несколько объектов подходят одинаково хорошо — clarify.
Не выполняй MQTT, сценарии, ручной режим и зависимости: это делает приложение.
Верни ТОЛЬКО JSON без Markdown: {"kind":"control|read_value|clarify|not_found","candidateIndex":-1,"value":"","delayMs":0,"reply":""}
CATALOG fields: index, id, device, page, title, titleSearch, type.
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
            mark("catalog", catalogStarted)
            val resultDeferred = CompletableDeferred<Result<String>>()
            val stages = mutableListOf<String>()
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (resultCode == 2) {
                        resultData?.getString("stage")?.let { stage -> synchronized(stages) { stages.add(stage) } }
                        return
                    }
                    val error = resultData?.getString("error")
                    if (resultCode == 0 && error.isNullOrBlank()) {
                        resultDeferred.complete(Result.success(resultData?.getString("text").orEmpty()))
                    } else {
                        val stageText = synchronized(stages) { stages.joinToString(" -> ") }
                        val detail = error ?: "Ошибка Gemma"
                        resultDeferred.complete(Result.failure(Exception("Gemma service: $detail" + if (stageText.isBlank()) "" else "\nSTAGES: $stageText")))
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
                val haystack = semanticTokens(searchableText(device.name + " " + widget.page + " " + widget.title + " " + widget.definitionName))
                var score = commandTokens.count { it in haystack } * 10
                if (widget.type == WidgetState.Type.TOGGLE ||
                    widget.type == WidgetState.Type.BUTTON ||
                    widget.type == WidgetState.Type.INPUT) score += 2
                Candidate(device, widget, score)
            }
        }.sortedByDescending { it.score }

        // For control commands, use the same semantic resolver that already knows
        // the difference between a logical state widget and a physical actuator.
        // This keeps candidateIndex aligned with Marfa's real IoTManager logic.
        val desiredValue = when {
            Regex("""\b(откры|открой|распах|подним)""", RegexOption.IGNORE_CASE).containsMatchIn(command) -> "1"
            Regex("""\b(закры|закрой|опуст|запечат)""", RegexOption.IGNORE_CASE).containsMatchIn(command) -> "0"
            Regex("""\b(включ|запусти|зажг)""", RegexOption.IGNORE_CASE).containsMatchIn(command) -> "1"
            Regex("""\b(выключ|останов|погаси)""", RegexOption.IGNORE_CASE).containsMatchIn(command) -> "0"
            else -> null
        }
        val orderedCandidates = if (desiredValue != null) {
            val resolution = MarfaAnalyticalEngine().resolveControl(command, devices, desiredValue)
            val rank = resolution.candidates.mapIndexed { index, candidate ->
                candidate.widget.id to index
            }.toMap()
            candidates.sortedWith(
                compareBy<Candidate> { rank[it.widget.id] ?: Int.MAX_VALUE }
                    .thenByDescending { it.score }
                    .thenBy { it.widget.order }
                    .thenBy { it.widget.id }
            )
        } else {
            candidates
        }
        val selectedBase = if (commandTokens.isEmpty()) orderedCandidates.take(12)
        else orderedCandidates.filter { it.score > 0 }.take(12).ifEmpty { orderedCandidates.take(12) }

        // Symbol/emoji-only pages are semantic namespaces. Expose several real
        // candidates from each relevant emoji page, not just one representative.
        // This lets Gemma first identify the page semantics and then choose the
        // correct widget inside that page (for example the door control).
        val nonLexicalPageCandidates = orderedCandidates
            .filter { isNonLexicalPage(it.widget.page) }
            .groupBy { it.widget.page }
            .entries
            .flatMap { (_, pageCandidates) -> pageCandidates.take(8) }
            .take(24)

        val selected = (nonLexicalPageCandidates + selectedBase + orderedCandidates)
            .distinctBy { it.widget.id }
            .take(48)

        val array = JSONArray()
        selected.forEach { item ->
            array.put(JSONObject().apply {
                put("index", array.length())
                put("id", item.widget.id)
                put("device", item.device.name)
                // Use Unicode code points, not the rendered emoji, as the page identifier.
                put("page", unicodeCodePoints(item.widget.page))
                put("pageText", item.widget.page)
                put("title", item.widget.title)
                put("titleSearch", searchableText(item.widget.page + " " + item.widget.title + " " + item.device.name + " " + item.widget.definitionName))
                put("pageCode", unicodeCodePoints(item.widget.page))
                put("type", item.widget.type.name)
            })
        }
        return array.toString()
    }

    private fun buildCandidatePairs(devices: List<Device>, command: String): List<Pair<Device, WidgetState>> {
        data class Candidate(val device: Device, val widget: WidgetState, val score: Int)
        val commandTokens = semanticTokens(command)
        val candidates = devices.flatMap { device ->
            device.widgets.map { widget ->
                val haystack = semanticTokens(searchableText(device.name + " " + widget.page + " " + widget.title + " " + widget.definitionName))
                var score = commandTokens.count { it in haystack } * 10
                if (widget.type == WidgetState.Type.TOGGLE ||
                    widget.type == WidgetState.Type.BUTTON ||
                    widget.type == WidgetState.Type.INPUT) score += 2
                Candidate(device, widget, score)
            }
        }.sortedByDescending { it.score }

        val desiredValue = when {
            Regex("""\b(откры|открой|распах|подним)""", RegexOption.IGNORE_CASE).containsMatchIn(command) -> "1"
            Regex("""\b(закры|закрой|опуст|запечат)""", RegexOption.IGNORE_CASE).containsMatchIn(command) -> "0"
            Regex("""\b(включ|запусти|зажг)""", RegexOption.IGNORE_CASE).containsMatchIn(command) -> "1"
            Regex("""\b(выключ|останов|погаси)""", RegexOption.IGNORE_CASE).containsMatchIn(command) -> "0"
            else -> null
        }
        val orderedCandidates = if (desiredValue != null) {
            val resolution = MarfaAnalyticalEngine().resolveControl(command, devices, desiredValue)
            val rank = resolution.candidates.mapIndexed { index, candidate -> candidate.widget.id to index }.toMap()
            candidates.sortedWith(
                compareBy<Candidate> { rank[it.widget.id] ?: Int.MAX_VALUE }
                    .thenByDescending { it.score }
                    .thenBy { it.widget.order }
                    .thenBy { it.widget.id }
            )
        } else candidates

        val selectedBase = if (commandTokens.isEmpty()) orderedCandidates.take(12)
        else orderedCandidates.filter { it.score > 0 }.take(12).ifEmpty { orderedCandidates.take(12) }

        // Keep the same candidate ordering as buildCatalog(): candidateIndex
        // must point to exactly the same widget that Gemma saw.
        val nonLexicalPageCandidates = orderedCandidates
            .filter { isNonLexicalPage(it.widget.page) }
            .groupBy { it.widget.page }
            .entries
            .flatMap { (_, pageCandidates) -> pageCandidates.take(8) }
            .take(24)

        return (nonLexicalPageCandidates + selectedBase + orderedCandidates)
            .distinctBy { it.widget.id }
            .take(48)
            .map { it.device to it.widget }
    }

    /**
     * Universal search representation. Original titles remain untouched.
     * Symbols/emoji are supplemented with their Unicode standard names instead
     * of using a hard-coded dictionary for individual objects.
     */
    private fun searchableText(value: String): String {
        if (value.isBlank()) return value
        val out = StringBuilder(value.length + 64)
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val chars = String(Character.toChars(codePoint))
            if (Character.isLetterOrDigit(codePoint) || Character.isWhitespace(codePoint)) {
                out.append(chars)
            } else {
                val unicodeName = runCatching { UCharacter.getName(codePoint) }.getOrNull()
                if (!unicodeName.isNullOrBlank()) {
                    out.append(' ').append("U+").append(codePoint.toString(16).uppercase(Locale.ROOT)).append(' ')
                    out.append(unicodeName.replace('_', ' ')).append(' ')
                } else {
                    out.append(' ')
                }
            }
            offset += Character.charCount(codePoint)
        }
        return out.toString()
    }

    /**
     * Stable text-only identifier for each Unicode code point.
     * Example: 🍅 -> U+1F345.
     */
    private fun unicodeCodePoints(value: String): String {
        if (value.isBlank()) return ""
        val out = StringBuilder()
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            if (!Character.isWhitespace(codePoint)) {
                if (out.isNotEmpty()) out.append(' ')
                out.append("U+").append(codePoint.toString(16).uppercase(Locale.ROOT))
            }
            offset += Character.charCount(codePoint)
        }
        return out.toString()
    }

    private fun containsSymbolOrEmoji(value: String): Boolean {
        if (value.isBlank()) return false
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            if (!Character.isLetterOrDigit(codePoint) && !Character.isWhitespace(codePoint)) {
                return true
            }
            offset += Character.charCount(codePoint)
        }
        return false
    }

    private fun isNonLexicalPage(page: String): Boolean =
        page.isNotBlank() && page.none { it.isLetterOrDigit() || it.isWhitespace() }

    private fun semanticTokens(value: String): Set<String> {
        val normalizedValue = searchableText(value)
        val stop = setOf("открой","открыть","открывай","закрой","закрыть","закрывай",
            "включи","включить","выключи","выключить","запусти","запустить",
            "останови","остановить","покажи","скажи","узнай","какая","какое","какие",
            "сколько","температура","температур","градус","градуса","через","спустя",
            "пожалуйста","марфа","там","здесь","у","в","на","для","где","около","возле","рядом","и","а","то","же")
        val dictionary = emptyMap<String, String>()
        return normalizedValue.lowercase(Locale("ru","RU")).replace('ё','е')
            .replace(Regex("[^a-zа-я0-9]+"), " ").trim().split(Regex("\\s+"))
            .map { dictionary[it] ?: it }
            .map { stem(it) }.filter { it.length >= 3 && it !in stop && !it.all(Char::isDigit) }.toSet()
    }

    private fun commandControlValue(command: String): String? {
        val text = command.lowercase(Locale("ru", "RU")).replace('ё', 'е')
        return when {
            Regex("""\b(откры|открой|открывай|распах|распахни|подним|подними)""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "1"
            Regex("""\b(закры|закрой|закрывай|опуст|опусти|запечат|запечатай)""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "0"
            Regex("""\b(включ|включи|включить|запусти|запустить|зажг|зажги)""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "1"
            Regex("""\b(выключ|выключи|выключить|останов|останови|погаси|погаси)""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "0"
            else -> null
        }
    }

    private fun stem(word: String): String {
        val endings = listOf("иями","ами","ого","ему","ому","ыми","ими","ая","яя","ое","ее","ые","ие",
            "ать","ить","еть","ять","ой","ый","ий","ов","ев","ам","ям","ах","ях","ы","и","а","я","о","е")
        for (ending in endings) {
            if (word.length > ending.length + 2 && word.endsWith(ending)) return word.removeSuffix(ending)
        }
        return word
    }
    suspend fun diagnoseCommand(command: String, devices: List<Device>): Result<GemmaChainDiagnostic> = withContext(Dispatchers.IO) {
        try {
            val totalStarted = System.currentTimeMillis()
            val timings = mutableListOf<String>()
            fun mark(name: String, started: Long) {
                timings.add(name + "=" + (System.currentTimeMillis() - started) + " мс")
            }

            var stage = "подготовка каталога"
            val catalogStarted = System.currentTimeMillis()
            val catalog = try { buildCatalog(devices, command) } catch (e: Throwable) {
                throw Exception("ЭТАП " + stage + ": " + (e.message ?: e.javaClass.simpleName), e)
            }
            val resultDeferred = CompletableDeferred<Result<String>>()
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    val error = resultData?.getString("error")
                    if (resultCode == 2) {
                        // GemmaInferenceService sends stage updates with code 2.
                        // They are informational and must not complete the diagnostic request.
                        return
                    }
                    if (resultCode == 0 && error.isNullOrBlank()) {
                        resultDeferred.complete(Result.success(resultData?.getString("text").orEmpty()))
                    } else {
                        val keys = resultData?.keySet()?.joinToString(",").orEmpty()
                        val detail = error ?: "error отсутствует; resultCode=" + resultCode + "; keys=[" + keys + "]"
                        resultDeferred.complete(Result.failure(Exception("Gemma service: " + detail)))
                    }
                }
            }
            stage = "запуск GemmaInferenceService"
            val serviceStartStarted = System.currentTimeMillis()
            try {
                appContext.startService(Intent(appContext, GemmaInferenceService::class.java)
                    .putExtra(GemmaInferenceService.EXTRA_COMMAND, command)
                    .putExtra(GemmaInferenceService.EXTRA_CATALOG, catalog)
                    .putExtra(GemmaInferenceService.EXTRA_RESULT, receiver))
            } catch (e: Throwable) {
                throw Exception("ЭТАП " + stage + ": " + (e.message ?: e.javaClass.simpleName), e)
            }
            mark("serviceStart", serviceStartStarted)
            stage = "ожидание ответа Gemma"
            val inferenceStarted = System.currentTimeMillis()
            val rawResult = try {
                kotlinx.coroutines.withTimeout(REQUEST_TIMEOUT_MS) { resultDeferred.await() }
            } catch (e: Throwable) {
                throw Exception("ЭТАП " + stage + ": " + (e.message ?: e.javaClass.simpleName), e)
            }
            mark("gemmaWait", inferenceStarted)
            val raw = rawResult.getOrElse { return@withContext Result.failure(it) }
            val parseStarted = System.currentTimeMillis()
            val jsonText = extractJson(raw) ?: return@withContext Result.failure(Exception("Gemma вернула не JSON"))
            val json = runCatching { JSONObject(jsonText) }.getOrElse {
                return@withContext Result.failure(Exception("Gemma вернула некорректный JSON"))
            }
            mark("jsonParse", parseStarted)
            val kind = json.optString("kind").lowercase(Locale.ROOT)
            val candidateIndex = json.optInt("candidateIndex", -1)
            val modelValue = json.optString("value").trim()
            val modelReply = json.optString("reply").trim()
            val pairStarted = System.currentTimeMillis()
            val pair = if (candidateIndex >= 0) buildCandidatePairs(devices, command).getOrNull(candidateIndex) else {
                val widgetId = json.optString("widgetId").trim()
                devices.asSequence().flatMap { device -> device.widgets.asSequence().map { device to it } }
                    .firstOrNull { it.second.id == widgetId }
            }
            mark("candidateMapping", pairStarted)
            var analyticalText = ""
            var analyticalCandidates = emptyList<String>()
            var analyticalClarification: String? = null
            var scenarioTarget = ""
            if (kind == "control" && pair != null) {
                val analyticalStarted = System.currentTimeMillis()
                val analyticalValue = commandControlValue(command) ?: normalizeControlValue(modelValue, command, pair.second)
                val analytical = MarfaAnalyticalEngine().resolveControl(command, devices, analyticalValue)
                analyticalCandidates = analytical.candidates.map { describeCandidate(it.device.id, it.widget) }
                analyticalClarification = analytical.clarification
                mark("analytical", analyticalStarted)
                analytical.candidate?.let { analyticalText = describeCandidate(it.device.id, it.widget) }

                /*
                 * Gemma has already resolved the semantic object from the full
                 * catalog. The deterministic engine may intentionally return no
                 * candidate for symbol/emoji-only page context. In that case the
                 * Gemma candidate is the semantic seed; do not discard it.
                 */
                val selectedDevice = pair.first
                val selectedWidget = pair.second
                if (analytical.candidate != null) {
                    analyticalText = describeCandidate(
                        analytical.candidate.device.id,
                        analytical.candidate.widget
                    )
                } else if (selectedWidget.type == WidgetState.Type.TOGGLE ||
                    selectedWidget.type == WidgetState.Type.BUTTON ||
                    selectedWidget.type == WidgetState.Type.INPUT) {
                    analyticalText = describeCandidate(selectedDevice.id, selectedWidget)
                }

                /*
                 * resolveLogicalTargetFromActuator() is a reverse resolver:
                 * actuator -> logical control. A Gemma-selected logical control
                 * such as vbtn78 must NOT be passed through it as if it were a
                 * relay. Scenario planning is performed by DeviceScenarioManager.
                 */
                val scenarioStarted = System.currentTimeMillis()
                val finalSeed = LocalCommandResult(
                    action = LocalCommandAction.CONTROL,
                    deviceId = selectedDevice.id,
                    reply = "",
                    widgetId = selectedWidget.id,
                    value = analyticalValue,
                    actionItems = listOf(
                        LocalCommandActionItem(
                            selectedDevice.id,
                            selectedWidget.id,
                            analyticalValue
                        )
                    )
                )
                val plan = AppRuntime.get(appContext).deviceScenarioManager
                    .planCommand(finalSeed, devices)
                mark("scenarioPlan", scenarioStarted)
                scenarioTarget = buildString {
                    if (plan.prerequisites.isNotEmpty()) {
                        append("ПРЕДУСЛОВИЯ: ")
                        append(plan.prerequisites.joinToString(", ") {
                            it.widgetId + "=" + it.value
                        })
                        append("; ")
                    }
                    append("ДЕЙСТВИЯ: ")
                    append(
                        plan.actions.joinToString(", ") {
                            it.widgetId + "=" + it.value
                        }
                    )
                    plan.blockedReason?.let {
                        append("; БЛОК: ")
                        append(it)
                    }
                }.trim()
            }
            val finalStarted = System.currentTimeMillis()
            val final = parseAndValidate(raw, command, devices)
            mark("finalValidation", finalStarted)
            mark("TOTAL", totalStarted)
            Result.success(GemmaChainDiagnostic(
                catalog = catalog, raw = raw, kind = kind, candidateIndex = candidateIndex,
                modelValue = modelValue, modelReply = modelReply,
                gemmaDeviceId = pair?.first?.id.orEmpty(), gemmaWidgetId = pair?.second?.id.orEmpty(),
                gemmaWidgetTitle = pair?.second?.title.orEmpty(), gemmaPage = pair?.second?.page.orEmpty(),
                analyticalCandidate = analyticalText, analyticalCandidates = analyticalCandidates,
                analyticalClarification = analyticalClarification, scenarioTarget = scenarioTarget,
                finalResult = final.getOrNull(), finalError = final.exceptionOrNull()?.message,
                timings = timings.toList()
            ))
        } catch (e: Throwable) {
            Result.failure(Exception(e.message ?: "Ошибка сквозной диагностики Gemma", e))
        }
    }

    private fun describeCandidate(deviceId: String, widget: WidgetState): String =
        "device=$deviceId, widget=${widget.id}, page=${widget.page}, title=${widget.title}, type=${widget.type.name}"

    fun validateRawResult(rawText: String, originalCommand: String, devices: List<Device>): Result<LocalCommandResult> = parseAndValidate(rawText, originalCommand, devices)

    private fun parseAndValidate(rawText: String, originalCommand: String, devices: List<Device>): Result<LocalCommandResult> {
        val jsonText = extractJson(rawText) ?: return Result.failure(Exception("Gemma вернула не JSON"))
        val json = runCatching { JSONObject(jsonText) }
            .getOrElse { return Result.failure(Exception("Gemma вернула некорректный JSON")) }
        val kind = json.optString("kind").lowercase(Locale.ROOT)
        val widgetId = json.optString("widgetId").trim()
        val candidateIndex = json.optInt("candidateIndex", -1)
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

        val pair = if (candidateIndex >= 0) {
            buildCandidatePairs(devices, originalCommand).getOrNull(candidateIndex)
        } else {
            devices.asSequence()
                .flatMap { device -> device.widgets.asSequence().map { device to it } }
                .firstOrNull { it.second.id == widgetId }
        } ?: return Result.failure(Exception(
            if (candidateIndex >= 0) "Gemma выбрала недопустимый candidateIndex: $candidateIndex"
            else if (kind == "control") "Gemma не выбрала candidateIndex"
            else "Gemma выбрала отсутствующий widgetId: $widgetId"
        ))

        var device = pair.first
        var widget = pair.second

        return when (kind) {
            "control" -> {
                val commandValue = commandControlValue(originalCommand)
                // Gemma provides semantic intent/candidate, but the deterministic
                // resolver remains authoritative for the final IoTManager target.
                // This prevents a similarly named actuator (for example btn178)
                // from replacing the logical state element (for example vbtn78).
                val analyticalValue = commandValue ?: normalizeControlValue(modelValue, originalCommand, widget)
                val analytical = MarfaAnalyticalEngine().resolveControl(
                    originalCommand,
                    devices,
                    analyticalValue
                )
                if (analytical.candidate != null) {
                    device = analytical.candidate.device
                    widget = analytical.candidate.widget
                }

                val scenarioTarget = AppRuntime.get(appContext).deviceScenarioManager
                    .resolveLogicalTarget(widget.id, analyticalValue, devices)
                if (scenarioTarget != null) {
                    val matches = devices.flatMap { candidateDevice ->
                        candidateDevice.widgets
                            .filter { it.id == scenarioTarget.first }
                            .map { candidateDevice to it }
                    }
                    if (matches.size == 1) {
                        device = matches.single().first
                        widget = matches.single().second
                    }
                }

                if (widget.type != WidgetState.Type.TOGGLE &&
                    widget.type != WidgetState.Type.BUTTON &&
                    widget.type != WidgetState.Type.INPUT) {
                    Result.failure(Exception("Недоступный для управления виджет: " + widget.id))
                } else {
                    val value = commandValue ?: normalizeControlValue(modelValue, originalCommand, widget)
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

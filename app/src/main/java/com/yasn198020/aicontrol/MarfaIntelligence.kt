package com.yasn198020.aicontrol

import android.content.Context
import android.net.Uri
import com.yasn198020.aicontrol.core.Device
import java.util.Locale

class MarfaIntelligence private constructor(context: Context) {
    companion object {
        @Volatile private var instance: MarfaIntelligence? = null
        fun get(context: Context): MarfaIntelligence =
            instance ?: synchronized(this) {
                instance ?: MarfaIntelligence(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val gemma = GemmaLocalEngine.get(appContext)
    private val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val smartRuleParser = LocalCommandManager()
    private val trainedMatcher = TrainedCommandMatcher(
        TrainedCommandStore(context.getSharedPreferences("settings", Context.MODE_PRIVATE))
    )

    fun gemmaStatus(): String = gemma.statusText()
    fun isGemmaEnabled(): Boolean = prefs.getBoolean("gemma_enabled", true)
    suspend fun importGemmaModel(uri: Uri): Result<String> = gemma.importModel(uri)

    suspend fun interpret(command: String, devices: List<Device>): LocalCommandResult {
        val text = command.trim()
        if (text.isBlank()) return LocalCommandResult(
            LocalCommandAction.NOT_FOUND, reply = "Я не услышала команду"
        )

        if (looksLikeSmartRule(text)) return smartRuleParser.interpret(text, devices)

        // Saved user-trained phrases remain outside the semantic LLM path.
        if (trainedMatcher.matchAll(text).isNotEmpty()) {
            return LocalCommandResult(LocalCommandAction.NOT_FOUND, reply = "")
        }

        // FAST PATH: ordinary commands are handled immediately by the existing
        // deterministic engine. Gemma is not invoked for every sentence.
        val fast = smartRuleParser.interpret(text, devices)

        if (!isGemmaEnabled()) return applyScenarioPlan(fast, devices)
        if (!gemma.isModelInstalled()) return applyScenarioPlan(fast, devices)
        if (!shouldUseGemma(text, fast, devices)) return applyScenarioPlan(fast, devices)

        val semanticResult = gemma.interpret(text, devices).getOrElse {
            // Gemma is optional. Any model/JNI/runtime error falls back to the
            // already working local command engine.
            fast
        }.let { gemmaResult ->
            if (gemmaResult.action != LocalCommandAction.CONTROL) {
                gemmaResult
            } else {
                // The verb/value remains deterministic in Android. Gemma only
                // supplies the logical element ID.
                val authoritativeValue = fast.value.ifBlank { commandValue(text) }
                if (authoritativeValue.isBlank()) gemmaResult else gemmaResult.copy(
                    value = authoritativeValue,
                    actionItems = gemmaResult.actionItems.map { it.copy(value = authoritativeValue) }
                        .ifEmpty {
                            listOf(LocalCommandActionItem(
                                gemmaResult.deviceId,
                                gemmaResult.widgetId,
                                authoritativeValue
                            ))
                        }
                )
            }
        }

        // FINAL = ScenarioCommandPlan. There is deliberately no post-plan
        // Gemma/finalValidation pass. Scenario Engine is the only authority
        // for prerequisites, mode gates and physical actuators.
        return applyScenarioPlan(semanticResult, devices)
    }

    private fun applyScenarioPlan(
        result: LocalCommandResult,
        devices: List<Device>
    ): LocalCommandResult {
        if (result.action != LocalCommandAction.CONTROL || result.widgetId.isBlank()) return result
        val plan = AppRuntime.get(appContext).deviceScenarioManager.planCommand(result, devices)
        return result.copy(scenarioPlan = plan)
    }

    suspend fun diagnoseCommand(command: String, devices: List<Device>): Result<String> {
        val text = command.trim()
        if (text.isBlank()) return Result.success("КОМАНДА ПУСТАЯ")
        val normalized = normalize(text)
        val semantic = semanticTokens(text).joinToString(", ")
        val fast = smartRuleParser.interpret(text, devices)
        val out = StringBuilder()
        out.appendLine("=== СКВОЗНОЙ ТЕСТ MARFA ===")
        out.appendLine("Команда: $text")
        out.appendLine("Нормализация: $normalized")
        out.appendLine("Смысловые токены: ${semantic.ifBlank { "нет" }}")
        out.appendLine()
        out.appendLine("--- 1. БЫСТРЫЙ ПАРСЕР ---")
        out.appendLine("action=${fast.action}")
        out.appendLine("deviceId=${fast.deviceId}")
        out.appendLine("widgetId=${fast.widgetId}")
        out.appendLine("value=${fast.value}")
        out.appendLine("delayMs=${fast.delayMs}")
        out.appendLine("reply=${fast.reply}")
        val trained = trainedMatcher.matchAll(text).isNotEmpty()
        out.appendLine("trainedMatch=$trained")
        if (looksLikeSmartRule(text)) {
            out.appendLine("smartRule=true")
            out.appendLine("Следующая стадия: Gemma не вызывается.")
            return Result.success(out.toString())
        }
        val gemmaEnabled = isGemmaEnabled()
        val modelInstalled = gemma.isModelInstalled()
        val useGemma = gemmaEnabled && modelInstalled && shouldUseGemma(text, fast, devices)
        out.appendLine()
        out.appendLine("--- 2. РЕШЕНИЕ MARFA INTELLIGENCE ---")
        out.appendLine("gemmaEnabled=$gemmaEnabled")
        out.appendLine("gemmaInstalled=$modelInstalled")
        out.appendLine("shouldUseGemma=$useGemma")
        val explained = buildSet {
            addAll(semanticTokens(fast.widgetId))
            devices.firstOrNull { it.id == fast.deviceId }?.let { device ->
                addAll(semanticTokens(device.name))
                device.widgets.firstOrNull { it.id == fast.widgetId }?.let { widget ->
                    addAll(semanticTokens(widget.title))
                    addAll(semanticTokens(widget.page))
                    addAll(semanticTokens(widget.definitionName))
                }
            }
        }
        val unknown = semanticTokens(text).filter { it !in explained }
        out.appendLine("explained=${explained.joinToString(", ").ifBlank { "нет" }}")
        out.appendLine("unknown/context=${unknown.joinToString(", ").ifBlank { "нет" }}")
        if (!gemmaEnabled || !modelInstalled || !useGemma) {
            out.appendLine(
                when {
                    !gemmaEnabled -> "Gemma выключена в настройках — фактический путь завершится FAST PATH."
                    !modelInstalled -> "Gemma не установлена — фактический путь завершится FAST PATH."
                    else -> "Gemma по реальной логике приложения не запускается."
                }
            )
            out.appendLine("ФИНАЛ: action=${fast.action}, deviceId=${fast.deviceId}, widgetId=${fast.widgetId}, value=${fast.value}")

            // FAST PATH bypasses only Gemma, never scenario resolution.
            if (fast.action == LocalCommandAction.CONTROL) {
                val scenarioStarted = System.currentTimeMillis()
                val executionPlan = AppRuntime.get(appContext).deviceScenarioManager
                    .planCommand(fast, devices)
                out.appendLine()
                out.appendLine("--- 7. SCENARIO TARGET / FAST PATH ---")
                out.appendLine("ДЕЙСТВИЯ: " + executionPlan.actions.joinToString(", ") {
                    it.widgetId + "=" + it.value
                }.ifBlank { "нет" })
                out.appendLine("ПРЕДУСЛОВИЯ: " + executionPlan.prerequisites.joinToString(", ") {
                    it.widgetId + "=" + it.value
                }.ifBlank { "нет" })
                executionPlan.blockedReason?.let {
                    out.appendLine("БЛОК: $it")
                } ?: out.appendLine("БЛОК: нет")
                out.appendLine("scenarioPlan=" + (System.currentTimeMillis() - scenarioStarted) + " мс")
                out.appendLine("MQTT: не отправляется в диагностическом тесте")
            }
            return Result.success(out.toString())
        }
        val trace: GemmaChainDiagnostic = gemma.diagnoseCommand(text, devices).getOrElse { return Result.failure(it) }
        out.appendLine()
        out.appendLine("--- 3. CATALOG → GEMMA ---")
        val catalogArray = org.json.JSONArray(trace.catalog)
        out.appendLine("Всего кандидатов перед compactCatalog: ${catalogArray.length()}")
        for (i in 0 until catalogArray.length()) {
            val c = catalogArray.optJSONObject(i) ?: continue
            out.appendLine("[${c.optInt("index", i)}] id=${c.optString("id")}; device=${c.optString("device")}; page=${c.optString("page")}; title=${c.optString("title")}; titleSearch=${c.optString("titleSearch")}; type=${c.optString("type")}")
        }
        out.appendLine()
        out.appendLine("--- 4. RAW GEMMA ---")
        out.appendLine("kind=${trace.kind}")
        out.appendLine("candidateIndex=${trace.candidateIndex}")
        out.appendLine("value=${trace.modelValue}")
        out.appendLine("reply=${trace.modelReply}")
        out.appendLine("raw=${trace.raw}")
        out.appendLine()
        out.appendLine("--- 5. ТОЧНОЕ СОПОСТАВЛЕНИЕ candidateIndex ---")
        out.appendLine(if (trace.gemmaWidgetId.isBlank()) "Кандидат не найден" else "device=${trace.gemmaDeviceId}; widget=${trace.gemmaWidgetId}; page=${trace.gemmaPage}; title=${trace.gemmaWidgetTitle}")
        out.appendLine()
        out.appendLine("--- 6. MARFA ANALYTICAL ENGINE ---")
        out.appendLine("candidate=${trace.analyticalCandidate.ifBlank { "нет" }}")
        trace.analyticalCandidates.forEachIndexed { index, candidate -> out.appendLine("  ${index + 1}. $candidate") }
        out.appendLine("clarification=${trace.analyticalClarification ?: "нет"}")
        val replaced = trace.analyticalCandidate.isNotBlank() && trace.gemmaWidgetId.isNotBlank() && !trace.analyticalCandidate.contains("widget=${trace.gemmaWidgetId}")
        out.appendLine("replacementByAnalytical=$replaced")
        out.appendLine()
        out.appendLine("--- 7. SCENARIO TARGET ---")
        out.appendLine(trace.scenarioTarget.ifBlank { "логическая цель сценария не найдена" })
        out.appendLine()
        out.appendLine("--- ВРЕМЯ ПО ЭТАПАМ ---")
        trace.timings.forEach { out.appendLine(it) }
        out.appendLine()
        out.appendLine("--- 8. ФИНАЛЬНЫЙ ScenarioCommandPlan ---")
        trace.finalResult?.let {
            out.appendLine("ФИНАЛ = ScenarioCommandPlan")
            out.appendLine("action=${it.action}")
            out.appendLine("deviceId=${it.deviceId}")
            out.appendLine("widgetId=${it.widgetId}")
            out.appendLine("value=${it.value}")
            out.appendLine("delayMs=${it.delayMs}")
            out.appendLine("reply=${it.reply}")
            out.appendLine("needsConfirmation=${it.needsConfirmation}")
            out.appendLine("actionItems=" + it.actionItems.joinToString(", ") {
                it.widgetId + "=" + it.value
            })

            val executionPlan = if (it.action == LocalCommandAction.CONTROL) {
                AppRuntime.get(appContext).deviceScenarioManager.planCommand(
                    it,
                    devices
                )
            } else null

            if (executionPlan != null) {
                out.appendLine()
                out.appendLine("--- 9. ФИНАЛЬНЫЙ ПЛАН ИСПОЛНЕНИЯ ---")
                out.appendLine(
                    "ДЕЙСТВИЯ: " + executionPlan.actions.joinToString(", ") {
                        it.widgetId + "=" + it.value
                    }
                )
                out.appendLine(
                    "ПРЕДУСЛОВИЯ: " + executionPlan.prerequisites.joinToString(", ") {
                        it.widgetId + "=" + it.value
                    }.ifBlank { "нет" }
                )
                executionPlan.blockedReason?.let {
                    out.appendLine("БЛОК: $it")
                } ?: out.appendLine("БЛОК: нет")
                out.appendLine("MQTT: не отправляется в диагностическом тесте")
            }
        } ?: out.appendLine("ERROR=${trace.finalError ?: "неизвестно"}")
        out.appendLine()
        out.appendLine("=== СКВОЗНОЙ ТЕСТ ЗАВЕРШЕН ===")
        out.appendLine("Ни MQTT, ни действие устройства этим тестом не выполняются.")
        return Result.success(out.toString())
    }

    /** Lets the voice UI acknowledge a deep semantic lookup before Gemma starts. */
    fun isLikelyContextual(command: String): Boolean {
        val n = normalize(command)
        if (n.isBlank()) return false
        val padded = " $n "
        return listOf(
            " там ", " здесь ", " где ", " у ", " около ", " возле ",
            " рядом ", " для ", " внутри ", " снаружи ", " в ", " на "
        ).any { padded.contains(it) } || n.split(" ").size >= 5
    }

    private fun commandValue(text: String): String {
        val normalized = normalize(text)
        return when {
            Regex("""\b(?:открой|открыть|открывай|подними|поднять|распахни|раскрой)\b""").containsMatchIn(normalized) -> "1"
            Regex("""\b(?:закрой|закрыть|закрывай|опусти|опустить|запечатай)\b""").containsMatchIn(normalized) -> "0"
            Regex("""\b(?:включи|включить|включай|запусти|запустить|зажги)\b""").containsMatchIn(normalized) -> "1"
            Regex("""\b(?:выключи|выключить|выключай|останови|остановить|погаси)\b""").containsMatchIn(normalized) -> "0"
            else -> ""
        }
    }

    private fun shouldUseGemma(
        text: String,
        fast: LocalCommandResult,
        devices: List<Device>
    ): Boolean {
        if (fast.action == LocalCommandAction.CLARIFY ||
            fast.action == LocalCommandAction.NOT_FOUND) return true

        // A valid deterministic control is authoritative. Do not send it to
        // Gemma merely because the sentence contains an extra/context word.
        // Gemma is a fallback for unresolved or genuinely ambiguous commands,
        // not a second pass over an already resolved control.
        if (fast.action == LocalCommandAction.CONTROL &&
            fast.widgetId.isNotBlank() &&
            fast.deviceId.isNotBlank() &&
            fast.value.isNotBlank()) {
            return false
        }

        if (fast.action != LocalCommandAction.CONTROL &&
            fast.action != LocalCommandAction.READ_VALUE) return false

        val commandTokens = semanticTokens(text)
        if (commandTokens.isEmpty()) return false

        val explained = mutableSetOf<String>()
        explained.addAll(semanticTokens(fast.widgetId))

        devices.firstOrNull { it.id == fast.deviceId }?.let { device ->
            explained.addAll(semanticTokens(device.name))
            device.widgets.firstOrNull { it.id == fast.widgetId }?.let { widget ->
                explained.addAll(semanticTokens(widget.title))
                explained.addAll(semanticTokens(widget.page))
                explained.addAll(semanticTokens(widget.definitionName))
            }
        }

        // Unknown meaningful words are context clues. This is intentionally
        // universal: no finite list such as "помидор/огурец/..." is required.
        return commandTokens.any { it !in explained }
    }

    private fun semanticTokens(value: String): Set<String> {
        val stop = setOf(
            "открой", "открыть", "открывай", "закрой", "закрыть", "закрывай",
            "включи", "включить", "выключи", "выключить", "запусти", "запустить",
            "останови", "остановить", "покажи", "скажи", "узнай", "какая", "какое",
            "какие", "сколько", "температура", "температур", "градус", "градуса",
            "через", "спустя", "пожалуйста", "марфа", "там", "здесь", "у", "в",
            "на", "для", "где", "около", "возле", "рядом", "и", "а", "то", "же"
        )
        return EmojiSemanticText.normalize(value)
            .split(" ")
            .map { stem(it) }
            .filter { it.length >= 3 && it !in stop && !it.all(Char::isDigit) }
            .toSet()
    }

    private fun stem(word: String): String {
        val endings = listOf(
            "иями", "ами", "ого", "ему", "ому", "ыми", "ими", "ая", "яя",
            "ое", "ее", "ые", "ие", "ать", "ить", "еть", "ять", "ой", "ый",
            "ий", "ов", "ев", "ам", "ям", "ах", "ях", "ы", "и", "а", "я", "о", "е"
        )
        for (ending in endings) {
            if (word.length > ending.length + 2 && word.endsWith(ending)) {
                return word.removeSuffix(ending)
            }
        }
        return word
    }

    private fun looksLikeSmartRule(text: String): Boolean {
        val n = text.lowercase(Locale.ROOT).replace('ё', 'е')
        val trigger = listOf("если ", "когда ", "при температур", "как только", "при условии")
            .any { n.contains(it) }
        return trigger && (
            n.contains("температур") ||
            n.contains("градус") ||
            n.contains("жарко") ||
            n.contains("холодно")
        )
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale("ru", "RU"))
            .replace('ё', 'е')
            .replace(Regex("[^a-zа-я0-9:,.]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replace(Regex("\\b(?:пожалуйста|прошу|марф|марфа|марфу|марфе|марфой)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}

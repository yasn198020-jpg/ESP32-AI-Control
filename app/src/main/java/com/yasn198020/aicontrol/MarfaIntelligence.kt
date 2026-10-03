package com.yasn198020.aicontrol

import android.content.Context
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
    private val scenarioGraph = ScenarioGraphCommandResolver()
    private val smartRuleParser = LocalCommandManager(
        scenarioPlanResolver = { result, devices ->
            AppRuntime.get(appContext).deviceScenarioManager.planCommand(result, devices)
        },
        scenarioGraphResolver = { candidates, desiredValue, devices ->
            val models = AppRuntime.get(appContext).deviceScenarioManager.graphModels()
            scenarioGraph.resolve(candidates, desiredValue, devices, models)
        }
    )
    private val trainedMatcher = TrainedCommandMatcher(
        TrainedCommandStore(context.getSharedPreferences("settings", Context.MODE_PRIVATE))
    )

    suspend fun interpret(command: String, devices: List<Device>): LocalCommandResult {
        val text = command.trim()
        if (text.isBlank()) {
            return LocalCommandResult(
                LocalCommandAction.NOT_FOUND,
                reply = "Я не услышала команду"
            )
        }

        if (looksLikeSmartRule(text)) {
            return smartRuleParser.interpret(text, devices)
        }

        // Saved user-trained phrases remain outside the semantic resolver.
        if (trainedMatcher.matchAll(text).isNotEmpty()) {
            return LocalCommandResult(LocalCommandAction.NOT_FOUND, reply = "")
        }

        /*
         * Single local command router:
         * CONTROL    -> deterministic target resolution -> ScenarioCommandPlan
         * READ_VALUE -> deterministic sensor/value resolution
         * SMART_RULE -> deterministic rule parser
         * CLARIFY    -> ask the user; never guess
         *
         * AI не используется в этом пути.
         */
        val local = smartRuleParser.interpret(text, devices)
        return if (local.action == LocalCommandAction.CONTROL) {
            applyScenarioPlan(local, devices)
        } else {
            local
        }
    }
    private fun applyScenarioPlan(
        result: LocalCommandResult,
        devices: List<Device>
    ): LocalCommandResult {
        if (result.action != LocalCommandAction.CONTROL || result.widgetId.isBlank()) return result
        val plan = AppRuntime.get(appContext).deviceScenarioManager.planCommand(result, devices)
        val graphPlan = result.scenarioPlan

        if (graphPlan == null) {
            return result.copy(scenarioPlan = plan)
        }

        // Keep the authoritative scenario planner as the execution plan, but
        // preserve prerequisites discovered by the graph layer if the lower
        // planner did not expose them itself.
        val mergedPrerequisites = (plan.prerequisites + graphPlan.prerequisites)
            .distinctBy { it.deviceId + "/" + it.widgetId + "/" + it.value }

        return result.copy(
            scenarioPlan = plan.copy(
                prerequisites = mergedPrerequisites,
                blockedReason = plan.blockedReason ?: graphPlan.blockedReason,
                resolvedByScenario = plan.resolvedByScenario || graphPlan.resolvedByScenario
            )
        )
    }

    suspend fun diagnoseCommand(command: String, devices: List<Device>): Result<String> {
        val text = command.trim()
        if (text.isBlank()) return Result.success("КОМАНДА ПУСТАЯ")

        val normalized = normalize(text)
        val fast = smartRuleParser.interpret(text, devices)
        val trained = trainedMatcher.matchAll(text).isNotEmpty()
        val out = StringBuilder()

        out.appendLine("=== СКВОЗНОЙ ТЕСТ MARFA ===")
        out.appendLine("Команда: $text")
        out.appendLine("Нормализация: $normalized")
        out.appendLine()
        out.appendLine("--- 1. БЫСТРЫЙ ПАРСЕР ---")
        out.appendLine("action=${fast.action}")
        out.appendLine("deviceId=${fast.deviceId}")
        out.appendLine("widgetId=${fast.widgetId}")
        out.appendLine("value=${fast.value}")
        out.appendLine("delayMs=${fast.delayMs}")
        out.appendLine("reply=${fast.reply}")
        out.appendLine("trainedMatch=$trained")
        out.appendLine()
        out.appendLine("--- 2. ЛОКАЛЬНЫЙ МАРШРУТИЗАТОР (БЕЗ GEMMA) ---")

        when (fast.action) {
            LocalCommandAction.CONTROL -> {
                out.appendLine("НАЙДЕНО: device=${fast.deviceId}; widget=${fast.widgetId}; value=${fast.value}")
                out.appendLine("reply=${fast.reply}")
                val started = System.currentTimeMillis()
                val plan = AppRuntime.get(appContext).deviceScenarioManager.planCommand(fast, devices)
                out.appendLine("SCENARIO: " + plan.actions.joinToString(", ") { it.widgetId + "=" + it.value }.ifBlank { "нет" })
                out.appendLine("ПРЕДУСЛОВИЯ: " + plan.prerequisites.joinToString(", ") { it.widgetId + "=" + it.value }.ifBlank { "нет" })
                plan.blockedReason?.let { out.appendLine("БЛОК: $it") } ?: out.appendLine("БЛОК: нет")
                out.appendLine("scenarioPlan=${System.currentTimeMillis() - started} мс")
            }
            LocalCommandAction.READ_VALUE -> {
                out.appendLine("НАЙДЕНО: device=${fast.deviceId}; widget=${fast.widgetId}; value=${fast.value}")
                out.appendLine("reply=${fast.reply}")
                out.appendLine("READ: значение читается локально, сценарий управления не используется")
            }
            LocalCommandAction.SMART_RULE -> {
                out.appendLine("SMART_RULE: ${fast.reply}")
                out.appendLine("condition=${fast.conditionWidgetId} ${fast.conditionOperator} ${fast.conditionThreshold}")
                out.appendLine("action=${fast.actionWidgetId}=${fast.actionValue}")
            }
            LocalCommandAction.CLARIFY -> {
                out.appendLine("ОДНОЗНАЧНАЯ ЦЕЛЬ НЕ НАЙДЕНА")
                out.appendLine("reply=${fast.reply}")
            }
            LocalCommandAction.NOT_FOUND -> {
                out.appendLine("КОМАНДА НЕ РАСПОЗНАНА ЛОКАЛЬНО")
                out.appendLine("reply=${fast.reply}")
            }
        }

        out.appendLine()
        out.appendLine("AI: не используется в командном контуре")
        out.appendLine("MQTT: не отправляется в диагностическом тесте")
        out.appendLine()
        out.appendLine("=== СКВОЗНОЙ ТЕСТ ЗАВЕРШЕН ===")
        out.appendLine("Ни MQTT, ни действие устройства этим тестом не выполняются.")
        return Result.success(out.toString())
    }
    /** Lets the voice UI acknowledge a context-rich phrase before local semantic resolution. */
    fun isLikelyContextual(command: String): Boolean {
        val n = normalize(command)
        if (n.isBlank()) return false
        val padded = " $n "
        return listOf(
            " там ", " здесь ", " где ", " у ", " около ", " возле ",
            " рядом ", " для ", " внутри ", " снаружи ", " в ", " на "
        ).any { padded.contains(it) } || n.split(" ").size >= 5
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

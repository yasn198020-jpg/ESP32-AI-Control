package com.yasn198020.aicontrol

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ScenarioCondition(
    val deviceId: String,
    val widgetId: String,
    val operator: String = ">",
    val threshold: Double,
    val connector: String = "AND"
)

data class ScenarioAction(
    val deviceId: String,
    val widgetId: String,
    val value: String = "1"
)

data class Scenario(
    val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val widgetId: String,
    val title: String,
    val operator: String = ">",
    val threshold: Double,
    val message: String,
    val enabled: Boolean = true,
    val armed: Boolean = true,
    val actionType: String = "NOTIFICATION",
    val actionDeviceId: String = "",
    val actionWidgetId: String = "",
    val actionValue: String = "1",
    val actions: List<ScenarioAction> = emptyList(),
    val notificationEnabled: Boolean = true,
    val verifyEnabled: Boolean = false,
    val verifyTimeoutSec: Int = 30,
    val verifyDeviceId: String = "",
    val verifyWidgetId: String = "",
    val verifyOperator: String = "=",
    val verifyValue: Double = 1.0,
    val verifySuccessMessage: String = "Подтверждение получено: {value}",
    val verifyFailureMessage: String = "Подтверждение не получено",
    val conditions: List<ScenarioCondition> = listOf(
        ScenarioCondition(deviceId, widgetId, operator, threshold)
    )
)

class ScenarioStore(private val prefs: android.content.SharedPreferences) {
    companion object { private const val KEY = "scenarios_v1" }

    private var cached: List<Scenario>? = null

    @Synchronized
    fun load(): List<Scenario> {
        cached?.let { return it }
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val scenario = runCatching {
                        val o = array.optJSONObject(i) ?: return@runCatching null
                        val oldDevice = o.optString("deviceId")
                        val oldWidget = o.optString("widgetId")
                        val oldOperator = normalizeOperator(o.optString("operator", ">"))
                        val oldThreshold = o.optDouble("threshold", Double.NaN)
                        val conditions = mutableListOf<ScenarioCondition>()
                        o.optJSONArray("conditions")?.let { conditionArray ->
                            for (j in 0 until conditionArray.length()) {
                                val item = conditionArray.optJSONObject(j) ?: continue
                                val threshold = item.optDouble("threshold", Double.NaN)
                                val deviceId = item.optString("deviceId").trim()
                                val widgetId = item.optString("widgetId").trim()
                                if (deviceId.isNotBlank() && widgetId.isNotBlank() && threshold.isFinite()) {
                                    conditions += ScenarioCondition(
                                        deviceId,
                                        widgetId,
                                        normalizeOperator(item.optString("operator", ">")),
                                        threshold,
                                        if (j == 0) "AND" else normalizeConnector(item.optString("connector", "AND"))
                                    )
                                }
                            }
                        }
                        if (conditions.isEmpty() && oldDevice.isNotBlank() && oldWidget.isNotBlank() && oldThreshold.isFinite()) {
                            conditions += ScenarioCondition(oldDevice, oldWidget, oldOperator, oldThreshold)
                        }
                        if (conditions.isEmpty()) return@runCatching null

                        val actionType = if (o.optString("actionType", "NOTIFICATION") == "MQTT_CONTROL") "MQTT_CONTROL" else "NOTIFICATION"
                        val actions = buildList {
                            val aa = o.optJSONArray("actions")
                            if (aa != null) {
                                for (k in 0 until aa.length()) {
                                    val a = aa.optJSONObject(k) ?: continue
                                    val d = a.optString("deviceId").trim()
                                    val w = a.optString("widgetId").trim()
                                    if (d.isNotBlank() && w.isNotBlank()) add(ScenarioAction(d, w, a.optString("value", "1")))
                                }
                            } else if (actionType == "MQTT_CONTROL" &&
                                o.optString("actionDeviceId").isNotBlank() &&
                                o.optString("actionWidgetId").isNotBlank()) {
                                add(ScenarioAction(o.optString("actionDeviceId"), o.optString("actionWidgetId"), o.optString("actionValue", "1")))
                            }
                        }

                        Scenario(
                            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                            deviceId = conditions.first().deviceId,
                            widgetId = conditions.first().widgetId,
                            title = o.optString("title").trim().ifBlank { "Сценарий" },
                            operator = conditions.first().operator,
                            threshold = conditions.first().threshold,
                            message = o.optString("message").trim().ifBlank { "Условие выполнено: {value}" },
                            enabled = o.optBoolean("enabled", true),
                            armed = o.optBoolean("armed", true),
                            actionType = actionType,
                            actionDeviceId = o.optString("actionDeviceId", ""),
                            actionWidgetId = o.optString("actionWidgetId", ""),
                            actionValue = o.optString("actionValue", "1"),
                            actions = actions,
                            notificationEnabled = o.optBoolean("notificationEnabled", true),
                            verifyEnabled = o.optBoolean("verifyEnabled", false),
                            verifyTimeoutSec = o.optInt("verifyTimeoutSec", 30).coerceIn(1, 300),
                            verifyDeviceId = o.optString("verifyDeviceId", ""),
                            verifyWidgetId = o.optString("verifyWidgetId", ""),
                            verifyOperator = normalizeOperator(o.optString("verifyOperator", "=")),
                            verifyValue = o.optDouble("verifyValue", 1.0).takeIf { it.isFinite() } ?: 1.0,
                            verifySuccessMessage = o.optString("verifySuccessMessage", "Подтверждение получено: {value}"),
                            verifyFailureMessage = o.optString("verifyFailureMessage", "Подтверждение не получено"),
                            conditions = conditions
                        )
                    }.getOrNull()
                    if (scenario != null) add(scenario)
                }
            }
        }.getOrDefault(emptyList()).also { cached = it }
    }

    @Synchronized
    fun invalidate() {
        cached = null
    }

    private fun normalizeOperator(value: String): String = when (value.trim()) {
        ">", ">=", "<", "<=", "=" -> value.trim()
        else -> ">"
    }

    private fun normalizeConnector(value: String): String =
        if (value.trim().equals("OR", ignoreCase = true)) "OR" else "AND"

    @Synchronized
    fun save(items: List<Scenario>) {
        val array = JSONArray()
        items.forEach { s ->
            array.put(JSONObject().apply {
                put("id", s.id)
                put("deviceId", s.deviceId)
                put("widgetId", s.widgetId)
                put("title", s.title)
                put("operator", s.operator)
                put("threshold", s.threshold)
                put("message", s.message)
                put("enabled", s.enabled)
                put("armed", s.armed)
                put("actionType", s.actionType)
                put("actionDeviceId", s.actionDeviceId)
                put("actionWidgetId", s.actionWidgetId)
                put("actionValue", s.actionValue)
                val aa = JSONArray()
                s.actions.forEach { a ->
                    aa.put(JSONObject().apply {
                        put("deviceId", a.deviceId)
                        put("widgetId", a.widgetId)
                        put("value", a.value)
                    })
                }
                put("actions", aa)
                put("notificationEnabled", s.notificationEnabled)
                put("verifyEnabled", s.verifyEnabled)
                put("verifyTimeoutSec", s.verifyTimeoutSec)
                put("verifyDeviceId", s.verifyDeviceId)
                put("verifyWidgetId", s.verifyWidgetId)
                put("verifyOperator", s.verifyOperator)
                put("verifyValue", s.verifyValue)
                put("verifySuccessMessage", s.verifySuccessMessage)
                put("verifyFailureMessage", s.verifyFailureMessage)
                val ca = JSONArray()
                s.conditions.forEachIndexed { index, c ->
                    ca.put(JSONObject().apply {
                        put("deviceId", c.deviceId)
                        put("widgetId", c.widgetId)
                        put("operator", c.operator)
                        put("threshold", c.threshold)
                        put("connector", if (index == 0) "AND" else c.connector)
                    })
                }
                put("conditions", ca)
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
        cached = items
    }

    @Synchronized fun add(scenario: Scenario) = save(load() + scenario)
    @Synchronized fun update(scenario: Scenario) = save(load().map { if (it.id == scenario.id) scenario else it })
    @Synchronized fun delete(id: String) = save(load().filterNot { it.id == id })
    @Synchronized fun clear() {
        prefs.edit().remove(KEY).apply()
        cached = emptyList()
    }
}

class ScenarioEngine(
    private val store: ScenarioStore,
    private val onTrigger: (Scenario, String, Double) -> Unit,
    private val onVerificationResult: (Scenario, Boolean, String) -> Unit
) {
    private val values = mutableMapOf<String, Double>()
    private val verificationTasks = mutableMapOf<String, java.util.concurrent.ScheduledFuture<*>>()
    private val verificationGenerations = mutableMapOf<String, Long>()
    private val verificationTraceIds = mutableMapOf<String, Long?>()

    // Runtime edge state. It is deliberately NOT persisted in ScenarioStore.
    // This prevents a saved armed=false flag from surviving app restarts.
    private val conditionStates = mutableMapOf<String, Boolean>()

    private val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private var shutdown = false
    private var runtimeActive = true

    private fun key(deviceId: String, widgetId: String) = "$deviceId/$widgetId"

    private fun conditionMatches(condition: ScenarioCondition): Boolean? {
        val value = values[key(condition.deviceId, condition.widgetId)] ?: return null
        return when (condition.operator) {
            ">" -> value > condition.threshold
            ">=" -> value >= condition.threshold
            "<" -> value < condition.threshold
            "<=" -> value <= condition.threshold
            "=" -> kotlin.math.abs(value - condition.threshold) < 0.000001
            else -> false
        }
    }

    private fun expressionMatches(conditions: List<ScenarioCondition>): Boolean? {
        if (conditions.isEmpty()) return false
        var result = conditionMatches(conditions.first()) ?: return null
        for (i in 1 until conditions.size) {
            val current = conditionMatches(conditions[i]) ?: return null
            result = if (conditions[i].connector == "OR") result || current else result && current
        }
        return result
    }

    private fun verificationMatches(scenario: Scenario, value: Double): Boolean {
        return when (scenario.verifyOperator) {
            ">" -> value > scenario.verifyValue
            ">=" -> value >= scenario.verifyValue
            "<" -> value < scenario.verifyValue
            "<=" -> value <= scenario.verifyValue
            else -> kotlin.math.abs(value - scenario.verifyValue) < 0.000001
        }
    }

    @Synchronized
    private fun startVerification(scenario: Scenario) {
        if (shutdown || !scenario.verifyEnabled || scenario.verifyDeviceId.isBlank() || scenario.verifyWidgetId.isBlank()) {
            DiagnosticTrace.step("VERIFY", "SKIP invalid verification config scenario=${scenario.id}")
            return
        }
        if (scheduler.isShutdown || scheduler.isTerminated) {
            DiagnosticTrace.step("VERIFY", "SKIP scheduler unavailable scenario=${scenario.id}")
            return
        }

        verificationTasks.remove(scenario.id)?.cancel(false)
        val generation = (verificationGenerations[scenario.id] ?: 0L) + 1L
        verificationGenerations[scenario.id] = generation
        verificationTraceIds[scenario.id] = DiagnosticTrace.currentEventId()
        DiagnosticTrace.step(
            "VERIFY",
            "START scenario=${scenario.id} timeout=${scenario.verifyTimeoutSec}s target=${scenario.verifyDeviceId}/${scenario.verifyWidgetId} expected=${scenario.verifyValue}"
        )

        val task = try {
            scheduler.schedule({
                val current: Scenario?
                val stillCurrent: Boolean
                synchronized(this) {
                    stillCurrent = !shutdown && verificationGenerations[scenario.id] == generation
                    if (stillCurrent) verificationTasks.remove(scenario.id)
                    current = if (stillCurrent) store.load().firstOrNull { it.id == scenario.id } else null
                }
                val traceId = synchronized(this) { verificationTraceIds.remove(scenario.id) }
                if (stillCurrent && current?.enabled == true && current.verifyEnabled) {
                    DiagnosticTrace.stepForEvent(traceId, "VERIFY", "TIMEOUT scenario=${scenario.id}")
                    onVerificationResult(current, false, "")
                }
            }, scenario.verifyTimeoutSec.coerceIn(1, 300).toLong(), java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            return
        }
        verificationTasks[scenario.id] = task
    }

    @Synchronized
    fun cancelScenario(scenarioId: String) {
        verificationTasks.remove(scenarioId)?.cancel(false)
        verificationGenerations[scenarioId] = (verificationGenerations[scenarioId] ?: 0L) + 1L
        verificationTraceIds.remove(scenarioId)
        DiagnosticTrace.system("Scenario cancelled id=$scenarioId")
        // Re-editing/toggling a scenario starts a fresh condition edge.
        conditionStates.remove(scenarioId)
    }

    @Synchronized
    fun setRuntimeActive(active: Boolean) {
        if (shutdown) return
        DiagnosticTrace.system("ScenarioEngine runtimeActive=$active")
        if (!active) {
            verificationTasks.values.forEach { it.cancel(false) }
            verificationTasks.clear()
            verificationGenerations.keys.toList().forEach { id ->
                verificationGenerations[id] = (verificationGenerations[id] ?: 0L) + 1L
            }
            conditionStates.clear()
        }
        runtimeActive = active
    }

    @Synchronized
    fun shutdown() {
        if (shutdown) return
        shutdown = true
        verificationTasks.values.forEach { it.cancel(false) }
        verificationTasks.clear()
        verificationGenerations.clear()
        conditionStates.clear()
        values.clear()
        scheduler.shutdownNow()
    }

    @Synchronized
    fun restoreValue(deviceId: String, widgetId: String, value: Double) {
        if (shutdown || !value.isFinite()) return
        values[key(deviceId, widgetId)] = value
    }

    @Synchronized
    fun reload() {
        store.invalidate()
        store.load()
    }

    @Synchronized
    fun primeFromStoredValues() {
        if (shutdown) return
        store.load().forEach { scenario ->
            if (!scenario.enabled) {
                conditionStates.remove(scenario.id)
                return@forEach
            }

            val conditions = scenario.conditions.ifEmpty {
                listOf(
                    ScenarioCondition(
                        scenario.deviceId,
                        scenario.widgetId,
                        scenario.operator,
                        scenario.threshold
                    )
                )
            }

            expressionMatches(conditions)?.let { matched ->
                if (matched) {
                    // Stored TRUE is only a baseline. A live MQTT value must
                    // still be able to enter TRUE while the app runs in background.
                    conditionStates.remove(scenario.id)
                    DiagnosticTrace.system(
                        "Scenario prime TRUE baseline cleared id=" + scenario.id
                    )
                } else {
                    conditionStates[scenario.id] = false
                }
            } ?: conditionStates.remove(scenario.id)
        }
    }

    @Synchronized
    fun onValue(deviceId: String, widgetId: String, rawValue: String) {
        val eventId = DiagnosticTrace.currentEventId()
        if (shutdown) {
            DiagnosticTrace.stepForEvent(eventId, "SCENARIO", "SKIP engine is shutdown")
            return
        }
        if (!runtimeActive) {
            DiagnosticTrace.stepForEvent(eventId, "SCENARIO", "SKIP runtimeActive=false")
            return
        }

        val value = rawValue.trim().replace(',', '.').toDoubleOrNull()
        if (value == null) {
            DiagnosticTrace.stepForEvent(eventId, "SCENARIO", "SKIP non-numeric value=$rawValue")
            return
        }
        values[key(deviceId, widgetId)] = value

        val scenarios = store.load()
        DiagnosticTrace.stepForEvent(eventId, "SCENARIO", "loaded=${scenarios.size} current=$deviceId/$widgetId=$value")

        val activeScenarioIds = scenarios.filter { it.enabled && it.verifyEnabled }.mapTo(mutableSetOf()) { it.id }
        val staleIds = verificationTasks.keys.filter { it !in activeScenarioIds }
        staleIds.forEach { id ->
            verificationTasks.remove(id)?.cancel(false)
            verificationGenerations[id] = (verificationGenerations[id] ?: 0L) + 1L
        }

        scenarios.forEach { scenario ->
            if (!scenario.enabled) {
                DiagnosticTrace.stepForEvent(eventId, "SCENARIO", "SKIP id=${scenario.id} disabled")
                conditionStates.remove(scenario.id)
                return@forEach
            }

            if (scenario.verifyEnabled &&
                scenario.verifyDeviceId == deviceId &&
                scenario.verifyWidgetId == widgetId &&
                verificationMatches(scenario, value)
            ) {
                val task = verificationTasks.remove(scenario.id)
                if (task != null) {
                    task.cancel(false)
                    verificationGenerations[scenario.id] = (verificationGenerations[scenario.id] ?: 0L) + 1L
                    val current = store.load().firstOrNull { it.id == scenario.id }
                    if (current?.enabled == true && current.verifyEnabled) {
                        DiagnosticTrace.stepForEvent(eventId, "VERIFY", "SUCCESS matched scenario=${scenario.id} value=$rawValue")
                        val traceId = verificationTraceIds.remove(scenario.id)
                        DiagnosticTrace.stepForEvent(traceId, "VERIFY", "triggered verification completed by current event")
                        onVerificationResult(current, true, rawValue)
                    } else {
                        DiagnosticTrace.stepForEvent(eventId, "VERIFY", "success candidate ignored scenario=${scenario.id}")
                    }
                } else {
                    DiagnosticTrace.stepForEvent(eventId, "VERIFY", "response matched but no pending task scenario=${scenario.id}")
                }
            }

            val conditions = scenario.conditions.ifEmpty {
                listOf(ScenarioCondition(scenario.deviceId, scenario.widgetId, scenario.operator, scenario.threshold))
            }
            if (conditions.none { it.deviceId == deviceId && it.widgetId == widgetId }) {
                DiagnosticTrace.stepForEvent(eventId, "SCENARIO", "SKIP id=${scenario.id} widget not in conditions")
                return@forEach
            }

            val matched = expressionMatches(conditions)
            if (matched == null) {
                DiagnosticTrace.stepForEvent(eventId, "CONDITION", "UNKNOWN id=${scenario.id} waiting for other condition value(s)")
                return@forEach
            }

            DiagnosticTrace.stepForEvent(eventId, "CONDITION", "id=${scenario.id} result=$matched conditions=${conditions.size}")
            if (conditions.any { it.deviceId == deviceId && it.widgetId == "vbtn90" }) {
                DiagnosticTrace.stepForEvent(eventId, "CONDITION", "VBTN90 CONDITION value=$value result=$matched scenario=${scenario.id}")
            }

            if (!matched) {
                conditionStates[scenario.id] = false
                DiagnosticTrace.stepForEvent(eventId, "EDGE", "id=${scenario.id} false -> reset")
                if (conditions.any { it.deviceId == deviceId && it.widgetId == "vbtn90" }) {
                    DiagnosticTrace.stepForEvent(eventId, "EDGE", "VBTN90 EDGE RESET value=$value scenario=${scenario.id}")
                }
                return@forEach
            }

            if (conditionStates[scenario.id] == true) {
                DiagnosticTrace.stepForEvent(eventId, "EDGE", "id=${scenario.id} true -> true, ignored")
                if (conditions.any { it.deviceId == deviceId && it.widgetId == "vbtn90" }) {
                    DiagnosticTrace.stepForEvent(eventId, "EDGE", "VBTN90 EDGE IGNORED value=$value scenario=${scenario.id}")
                }
                return@forEach
            }

            conditionStates[scenario.id] = true
            DiagnosticTrace.stepForEvent(eventId, "EDGE", "id=${scenario.id} false -> true, TRIGGER")
            if (conditions.any { it.deviceId == deviceId && it.widgetId == "vbtn90" }) {
                DiagnosticTrace.stepForEvent(eventId, "EDGE", "VBTN90 EDGE TRIGGER value=$value scenario=${scenario.id}")
            }

            if (scenario.verifyEnabled) startVerification(scenario)

            onTrigger(scenario.copy(armed = true), rawValue, value)
        }
    }
}

class ScenarioActionExecutor {
    @Volatile var mqtt: MqttManager? = null

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ScenarioActionExecutor").apply {
            isDaemon = true
        }
    }

    fun execute(scenario: Scenario) {
        val eventId = DiagnosticTrace.currentEventId()
        DiagnosticTrace.stepForEvent(
            eventId,
            "ACTION",
            "DISPATCH scenario=${scenario.id} type=${scenario.actionType}"
        )

        executor.execute {
            executeNow(scenario, eventId)
        }
    }

    private fun executeNow(scenario: Scenario, eventId: Long?) {
        if (scenario.actionType != "MQTT_CONTROL") {
            DiagnosticTrace.stepForEvent(
                eventId,
                "ACTION",
                "SKIP scenario=${scenario.id} type=${scenario.actionType}"
            )
            return
        }

        val manager = mqtt
        if (manager == null) {
            DiagnosticTrace.stepForEvent(
                eventId,
                "ERROR",
                "ACTION MQTT manager is null scenario=${scenario.id}"
            )
            return
        }

        val actions = scenario.actions.ifEmpty {
            if (scenario.actionDeviceId.isNotBlank() && scenario.actionWidgetId.isNotBlank()) {
                listOf(
                    ScenarioAction(
                        scenario.actionDeviceId,
                        scenario.actionWidgetId,
                        scenario.actionValue.ifBlank { "1" }
                    )
                )
            } else {
                emptyList()
            }
        }.toList()

        DiagnosticTrace.stepForEvent(
            eventId,
            "ACTION",
            "START scenario=${scenario.id} type=MQTT_CONTROL actionCount=${actions.size}"
        )

        if (actions.isEmpty()) {
            DiagnosticTrace.stepForEvent(
                eventId,
                "ACTION",
                "NO ACTIONS scenario=${scenario.id}"
            )
            return
        }

        actions.forEachIndexed { index, action ->
            val number = index + 1

            if (action.deviceId.isBlank() || action.widgetId.isBlank()) {
                DiagnosticTrace.stepForEvent(
                    eventId,
                    "ACTION",
                    "#$number SKIP blank target device=${action.deviceId} widget=${action.widgetId}"
                )
                return@forEachIndexed
            }

            val actionValue = action.value.ifBlank { "1" }

            DiagnosticTrace.stepForEvent(
                eventId,
                "ACTION",
                "#$number SEND target=${action.deviceId}/${action.widgetId} value=$actionValue"
            )

            try {
                val result = manager.publishControl(
                    action.deviceId,
                    action.widgetId,
                    actionValue,
                    eventId
                )

                DiagnosticTrace.stepForEvent(
                    eventId,
                    "ACTION",
                    "#$number RESULT target=${action.deviceId}/${action.widgetId} value=$actionValue result=$result"
                )
            } catch (e: Exception) {
                DiagnosticTrace.stepForEvent(
                    eventId,
                    "ERROR",
                    "ACTION #$number EXCEPTION target=${action.deviceId}/${action.widgetId} error=${e.message ?: e.javaClass.simpleName}"
                )
            }
        }

        DiagnosticTrace.stepForEvent(
            eventId,
            "ACTION",
            "COMPLETE scenario=${scenario.id} actionCount=${actions.size}"
        )
    }
}

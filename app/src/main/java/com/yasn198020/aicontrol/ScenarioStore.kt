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

    @Synchronized
    fun load(): List<Scenario> {
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
                                        deviceId = deviceId,
                                        widgetId = widgetId,
                                        operator = normalizeOperator(item.optString("operator", ">")),
                                        threshold = threshold,
                                        connector = if (j == 0) "AND" else normalizeConnector(item.optString("connector", "AND"))
                                    )
                                }
                            }
                        }
                        if (conditions.isEmpty() && oldDevice.isNotBlank() && oldWidget.isNotBlank() && oldThreshold.isFinite()) {
                            conditions += ScenarioCondition(oldDevice, oldWidget, oldOperator, oldThreshold)
                        }
                        if (conditions.isEmpty()) return@runCatching null

                        val actionType = if (o.optString("actionType", "NOTIFICATION") == "MQTT_CONTROL") {
                            "MQTT_CONTROL"
                        } else {
                            "NOTIFICATION"
                        }
                        val actions = buildList {
                            val aa = o.optJSONArray("actions")
                            if (aa != null) {
                                for (k in 0 until aa.length()) {
                                    val a = aa.optJSONObject(k) ?: continue
                                    val d = a.optString("deviceId").trim()
                                    val w = a.optString("widgetId").trim()
                                    if (d.isNotBlank() && w.isNotBlank()) {
                                        add(ScenarioAction(d, w, a.optString("value", "1")))
                                    }
                                }
                            } else if (actionType == "MQTT_CONTROL" &&
                                o.optString("actionDeviceId").isNotBlank() &&
                                o.optString("actionWidgetId").isNotBlank()
                            ) {
                                add(
                                    ScenarioAction(
                                        o.optString("actionDeviceId"),
                                        o.optString("actionWidgetId"),
                                        o.optString("actionValue", "1")
                                    )
                                )
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
        }.getOrDefault(emptyList())
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
    }

    @Synchronized
    fun add(scenario: Scenario) = save(load() + scenario)
    @Synchronized
    fun update(scenario: Scenario) = save(load().map { if (it.id == scenario.id) scenario else it })
    @Synchronized
    fun delete(id: String) = save(load().filterNot { it.id == id })
    @Synchronized
    fun clear() = prefs.edit().remove(KEY).apply()
}

class ScenarioEngine(
    private val store: ScenarioStore,
    private val onTrigger: (Scenario, String, Double) -> Unit,
    private val onVerificationResult: (Scenario, Boolean, String) -> Unit
) {
    private val values = mutableMapOf<String, Double>()
    private val verificationTasks = mutableMapOf<String, java.util.concurrent.ScheduledFuture<*>>()
    private val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private var shutdown = false

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
        if (shutdown) return
        if (!scenario.verifyEnabled || scenario.verifyDeviceId.isBlank() || scenario.verifyWidgetId.isBlank()) return
        if (scheduler.isShutdown || scheduler.isTerminated) return

        verificationTasks.remove(scenario.id)?.cancel(false)
        val task = try {
            scheduler.schedule({
                val current: Scenario?
                synchronized(this) {
                    verificationTasks.remove(scenario.id)
                    current = store.load().firstOrNull { it.id == scenario.id }
                }
                if (current?.enabled == true && current.verifyEnabled && !shutdown) {
                    onVerificationResult(current, false, "")
                }
            }, scenario.verifyTimeoutSec.coerceIn(1, 300).toLong(), java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            return
        }
        verificationTasks[scenario.id] = task
    }

    @Synchronized
    fun shutdown() {
        if (shutdown) return
        shutdown = true
        verificationTasks.values.forEach { it.cancel(false) }
        verificationTasks.clear()
        values.clear()
        scheduler.shutdownNow()
    }

    @Synchronized
    fun onValue(deviceId: String, widgetId: String, rawValue: String) {
        if (shutdown) return
        val value = rawValue.trim().replace(',', '.').toDoubleOrNull() ?: return
        values[key(deviceId, widgetId)] = value

        store.load().forEach { scenario ->
            if (!scenario.enabled) return@forEach

            if (scenario.verifyEnabled &&
                scenario.verifyDeviceId == deviceId &&
                scenario.verifyWidgetId == widgetId &&
                verificationMatches(scenario, value)
            ) {
                val task = verificationTasks.remove(scenario.id)
                if (task != null) {
                    task.cancel(false)
                    onVerificationResult(scenario, true, rawValue)
                }
            }

            val conditions = scenario.conditions.ifEmpty {
                listOf(ScenarioCondition(scenario.deviceId, scenario.widgetId, scenario.operator, scenario.threshold))
            }
            if (conditions.none { it.deviceId == deviceId && it.widgetId == widgetId }) return@forEach

            val matched = expressionMatches(conditions) ?: return@forEach
            if (matched && scenario.armed) {
                // Commit the disarmed state before executing the action.
                // Some MQTT clients can deliver the device response synchronously
                // from publishControl(). Updating the state first prevents that
                // re-entrant status callback from triggering the scenario twice.
                if (scenario.verifyEnabled) startVerification(scenario)
                val disarmed = scenario.copy(armed = false)
                store.update(disarmed)
                onTrigger(disarmed, rawValue, value)
            } else if (!matched && !scenario.armed) {
                store.update(scenario.copy(armed = true))
            }
        }
    }
}

class ScenarioActionExecutor {
    @Volatile var mqtt: MqttManager? = null

    fun execute(scenario: Scenario) {
        if (scenario.actionType != "MQTT_CONTROL") return
        val manager = mqtt ?: return
        if (!manager.isConnected()) return

        val actions = scenario.actions.ifEmpty {
            if (scenario.actionDeviceId.isNotBlank() && scenario.actionWidgetId.isNotBlank()) {
                listOf(ScenarioAction(scenario.actionDeviceId, scenario.actionWidgetId, scenario.actionValue.ifBlank { "1" }))
            } else emptyList()
        }
        actions.forEach { action ->
            if (action.deviceId.isNotBlank() && action.widgetId.isNotBlank()) {
                manager.publishControl(action.deviceId, action.widgetId, action.value.ifBlank { "1" })
            }
        }
    }
}

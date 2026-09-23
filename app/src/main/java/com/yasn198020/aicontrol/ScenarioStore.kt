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

    fun load(): List<Scenario> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val oldDevice = o.optString("deviceId")
                    val oldWidget = o.optString("widgetId")
                    val oldOperator = o.optString("operator", ">")
                    val oldThreshold = o.optDouble("threshold", Double.NaN)
                    val conditions = mutableListOf<ScenarioCondition>()
                    val conditionArray = o.optJSONArray("conditions")
                    if (conditionArray != null) {
                        for (j in 0 until conditionArray.length()) {
                            val c = conditionArray.optJSONObject(j) ?: continue
                            val threshold = c.optDouble("threshold", Double.NaN)
                            if (c.optString("deviceId").isNotBlank() &&
                                c.optString("widgetId").isNotBlank() &&
                                threshold.isFinite()
                            ) {
                                conditions += ScenarioCondition(
                                    deviceId = c.optString("deviceId"),
                                    widgetId = c.optString("widgetId"),
                                    operator = c.optString("operator", ">"),
                                    threshold = threshold,
                                    connector = if (j == 0) "AND" else c.optString("connector", "AND")
                                )
                            }
                        }
                    }
                    if (conditions.isEmpty() && oldDevice.isNotBlank() && oldWidget.isNotBlank() && oldThreshold.isFinite()) {
                        conditions += ScenarioCondition(oldDevice, oldWidget, oldOperator, oldThreshold)
                    }
                    if (conditions.isNotEmpty()) {
                        add(Scenario(
                            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                            deviceId = conditions.first().deviceId,
                            widgetId = conditions.first().widgetId,
                            title = o.optString("title"),
                            operator = conditions.first().operator,
                            threshold = conditions.first().threshold,
                            message = o.optString("message"),
                            enabled = o.optBoolean("enabled", true),
                            armed = o.optBoolean("armed", true),
                            actionType = o.optString("actionType", "NOTIFICATION"),
                            actionDeviceId = o.optString("actionDeviceId", ""),
                            actionWidgetId = o.optString("actionWidgetId", ""),
                            actionValue = o.optString("actionValue", "1"),
                            verifyEnabled = o.optBoolean("verifyEnabled", false),
                            verifyTimeoutSec = o.optInt("verifyTimeoutSec", 30).coerceIn(1, 300),
                            verifyDeviceId = o.optString("verifyDeviceId", ""),
                            verifyWidgetId = o.optString("verifyWidgetId", ""),
                            verifyOperator = o.optString("verifyOperator", "="),
                            verifyValue = o.optDouble("verifyValue", 1.0),
                            verifySuccessMessage = o.optString("verifySuccessMessage", "Подтверждение получено: {value}"),
                            verifyFailureMessage = o.optString("verifyFailureMessage", "Подтверждение не получено"),
                            conditions = conditions
                        ))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

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

    fun add(scenario: Scenario) = save(load() + scenario)
    fun update(scenario: Scenario) = save(load().map { if (it.id == scenario.id) scenario else it })
    fun delete(id: String) = save(load().filterNot { it.id == id })
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

    private fun startVerification(scenario: Scenario, triggerRawValue: String) {
        if (!scenario.verifyEnabled ||
            scenario.verifyDeviceId.isBlank() ||
            scenario.verifyWidgetId.isBlank()
        ) return

        verificationTasks.remove(scenario.id)?.cancel(false)

        verificationTasks[scenario.id] = scheduler.schedule({
            synchronized(this) {
                verificationTasks.remove(scenario.id)
            }
            onVerificationResult(scenario, false, "")
        }, scenario.verifyTimeoutSec.coerceIn(1, 300).toLong(), java.util.concurrent.TimeUnit.SECONDS)
    }

    @Synchronized
    fun onValue(deviceId: String, widgetId: String, rawValue: String) {
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
                onTrigger(scenario, rawValue, value)
                store.update(scenario.copy(armed = false))
                startVerification(scenario, rawValue)
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
        val deviceId = scenario.actionDeviceId
        val widgetId = scenario.actionWidgetId
        if (deviceId.isBlank() || widgetId.isBlank()) return
        val manager = mqtt ?: return
        if (!manager.isConnected()) return
        manager.publishControl(deviceId, widgetId, scenario.actionValue.ifBlank { "1" })
    }
}

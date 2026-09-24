package com.yasn198020.aicontrol.scenarios

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
                            actions = run {
                                val aa = o.optJSONArray("actions")
                                if (aa != null) buildList {
                                    for (k in 0 until aa.length()) {
                                        val a = aa.optJSONObject(k) ?: continue
                                        val d = a.optString("deviceId")
                                        val w = a.optString("widgetId")
                                        if (d.isNotBlank() && w.isNotBlank()) add(ScenarioAction(d, w, a.optString("value", "1")))
                                    }
                                } else if (o.optString("actionType", "NOTIFICATION") == "MQTT_CONTROL" && o.optString("actionDeviceId").isNotBlank() && o.optString("actionWidgetId").isNotBlank()) {
                                    listOf(ScenarioAction(o.optString("actionDeviceId"), o.optString("actionWidgetId"), o.optString("actionValue", "1")))
                                } else emptyList()
                            },
                            notificationEnabled = o.optBoolean("notificationEnabled", true),
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

    fun add(scenario: Scenario) = save(load() + scenario)
    fun update(scenario: Scenario) = save(load().map { if (it.id == scenario.id) scenario else it })
    fun delete(id: String) = save(load().filterNot { it.id == id })
    fun clear() = prefs.edit().remove(KEY).apply()
}

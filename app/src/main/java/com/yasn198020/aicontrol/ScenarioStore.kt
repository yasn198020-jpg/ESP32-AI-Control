package com.yasn198020.aicontrol

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Scenario(
    val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val widgetId: String,
    val title: String,
    val operator: String = ">",
    val threshold: Double,
    val message: String,
    val enabled: Boolean = true,
    val armed: Boolean = true
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
                    add(Scenario(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        deviceId = o.optString("deviceId"),
                        widgetId = o.optString("widgetId"),
                        title = o.optString("title"),
                        operator = o.optString("operator", ">"),
                        threshold = o.optDouble("threshold", Double.NaN),
                        message = o.optString("message"),
                        enabled = o.optBoolean("enabled", true),
                        armed = o.optBoolean("armed", true)
                    ))
                }
            }.filter { it.deviceId.isNotBlank() && it.widgetId.isNotBlank() && it.threshold.isFinite() }
        }.getOrDefault(emptyList())
    }

    fun save(items: List<Scenario>) {
        val array = JSONArray()
        items.forEach { s ->
            array.put(JSONObject().apply {
                put("id", s.id); put("deviceId", s.deviceId); put("widgetId", s.widgetId)
                put("title", s.title); put("operator", s.operator); put("threshold", s.threshold)
                put("message", s.message); put("enabled", s.enabled); put("armed", s.armed)
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
    private val onTrigger: (Scenario, String, Double) -> Unit
) {
    @Synchronized
    fun onValue(deviceId: String, widgetId: String, rawValue: String) {
        val value = rawValue.trim().replace(',', '.').toDoubleOrNull() ?: return
        store.load().forEach { scenario ->
            if (!scenario.enabled || scenario.deviceId != deviceId || scenario.widgetId != widgetId) return@forEach
            val matched = when (scenario.operator) {
                ">" -> value > scenario.threshold
                ">=" -> value >= scenario.threshold
                "<" -> value < scenario.threshold
                "<=" -> value <= scenario.threshold
                "=" -> kotlin.math.abs(value - scenario.threshold) < 0.000001
                else -> false
            }
            if (matched && scenario.armed) {
                onTrigger(scenario, rawValue, value)
                store.update(scenario.copy(armed = false))
            } else if (!matched && !scenario.armed) {
                store.update(scenario.copy(armed = true))
            }
        }
    }
}

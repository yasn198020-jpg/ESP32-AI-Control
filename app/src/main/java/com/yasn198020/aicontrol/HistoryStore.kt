package com.yasn198020.aicontrol

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class HistoryPoint(val timestamp: Long, val deviceId: String, val widgetId: String, val value: Double)

class HistoryStore(private val prefs: SharedPreferences) {
    companion object { private const val KEY = "telemetry_history_v1"; private const val MAX_POINTS = 5000; private const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L }
    @Synchronized fun add(deviceId: String, widgetId: String, rawValue: String, timestamp: Long = System.currentTimeMillis()) {
        val value = rawValue.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: return
        val all = load().toMutableList(); all.add(HistoryPoint(timestamp, deviceId, widgetId, value))
        val kept = all.filter { it.timestamp >= timestamp - MAX_AGE_MS }.takeLast(MAX_POINTS)
        val json = JSONArray(); kept.forEach { p -> json.put(JSONObject().apply { put("t", p.timestamp); put("d", p.deviceId); put("w", p.widgetId); put("v", p.value) }) }
        prefs.edit().putString(KEY, json.toString()).apply()
    }
    @Synchronized
    fun latestValues(): Map<String, Double> {
        val latest = LinkedHashMap<String, HistoryPoint>()
        load().forEach { point ->
            latest["${point.deviceId}/${point.widgetId}"] = point
        }
        return latest.mapValues { it.value.value }
    }

    fun load(): List<HistoryPoint> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try { val json = JSONArray(raw); buildList(json.length()) { for (i in 0 until json.length()) { val o=json.getJSONObject(i); add(HistoryPoint(o.optLong("t"),o.optString("d"),o.optString("w"),o.optDouble("v",Double.NaN))) } }.filter { it.value.isFinite() } } catch (_: Exception) { emptyList() }
    }
    fun clear() { prefs.edit().remove(KEY).apply() }
}
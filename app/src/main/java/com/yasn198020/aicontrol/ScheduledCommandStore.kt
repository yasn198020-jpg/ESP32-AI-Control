package com.yasn198020.aicontrol

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ScheduledCommand(
    val id: Long,
    val deviceId: String,
    val widgetId: String,
    val value: String,
    val title: String,
    val sourceText: String,
    val executeAtMillis: Long,
    val createdAtMillis: Long,
    val status: String = STATUS_WAITING,
    val attempts: Int = 0
) {
    companion object {
        const val STATUS_WAITING = "WAITING"
        const val STATUS_DONE = "DONE"
        const val STATUS_ERROR = "ERROR"
        const val STATUS_CANCELLED = "CANCELLED"
    }
}

object ScheduledCommandStore {
    private const val PREFS = "scheduled_commands_v1"
    private const val KEY = "items"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(context: Context): List<ScheduledCommand> {
        val raw = prefs(context).getString(KEY, "[]") ?: "[]"
        val json = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                add(
                    ScheduledCommand(
                        id = item.optLong("id"),
                        deviceId = item.optString("deviceId"),
                        widgetId = item.optString("widgetId"),
                        value = item.optString("value"),
                        title = item.optString("title"),
                        sourceText = item.optString("sourceText"),
                        executeAtMillis = item.optLong("executeAtMillis"),
                        createdAtMillis = item.optLong("createdAtMillis"),
                        status = item.optString("status", ScheduledCommand.STATUS_WAITING),
                        attempts = item.optInt("attempts", 0)
                    )
                )
            }
        }
    }

    @Synchronized
    fun add(
        context: Context,
        deviceId: String,
        widgetId: String,
        value: String,
        title: String,
        sourceText: String,
        executeAtMillis: Long
    ): ScheduledCommand {
        val now = System.currentTimeMillis()
        val items = all(context).toMutableList()
        val id = (items.maxOfOrNull { it.id } ?: now).coerceAtLeast(now) + 1L
        val item = ScheduledCommand(
            id = id,
            deviceId = deviceId,
            widgetId = widgetId,
            value = value,
            title = title,
            sourceText = sourceText,
            executeAtMillis = executeAtMillis,
            createdAtMillis = now
        )
        items.add(item)
        save(context, items)
        return item
    }

    @Synchronized
    fun find(context: Context, id: Long): ScheduledCommand? =
        all(context).firstOrNull { it.id == id }

    @Synchronized
    fun update(context: Context, item: ScheduledCommand) {
        val items = all(context).map { if (it.id == item.id) item else it }
        save(context, items)
    }

    @Synchronized
    fun remove(context: Context, id: Long) {
        save(context, all(context).filterNot { it.id == id })
    }

    @Synchronized
    fun pending(context: Context): List<ScheduledCommand> =
        all(context).filter { it.status == ScheduledCommand.STATUS_WAITING }

    private fun save(context: Context, items: List<ScheduledCommand>) {
        val json = JSONArray()
        items.forEach { item ->
            json.put(
                JSONObject()
                    .put("id", item.id)
                    .put("deviceId", item.deviceId)
                    .put("widgetId", item.widgetId)
                    .put("value", item.value)
                    .put("title", item.title)
                    .put("sourceText", item.sourceText)
                    .put("executeAtMillis", item.executeAtMillis)
                    .put("createdAtMillis", item.createdAtMillis)
                    .put("status", item.status)
                    .put("attempts", item.attempts)
            )
        }
        prefs(context).edit().putString(KEY, json.toString()).apply()
    }
}

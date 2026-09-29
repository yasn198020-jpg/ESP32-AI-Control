package com.yasn198020.aicontrol

import org.json.JSONObject

data class IoTManagerJsonImportResult(
    val title: String,
    val source: String,
    val sensorIds: List<String>,
    val configCount: Int
)

object IoTManagerJsonImporter {
    fun parse(fileName: String, raw: String): IoTManagerJsonImportResult {
        val marker = Regex("(?m)^\\s*scenario=>").find(raw)
            ?: throw IllegalArgumentException("В JSON-файле не найден блок scenario=>.")
        val jsonText = raw.substring(0, marker.range.first).trim()
        val scenario = raw.substring(marker.range.first).trim()

        val root = runCatching { JSONObject(jsonText) }.getOrElse {
            throw IllegalArgumentException("Не удалось прочитать JSON-конфигурацию: " + (it.message ?: "неизвестная ошибка"))
        }
        val config = root.optJSONArray("config")
            ?: throw IllegalArgumentException("В JSON-файле отсутствует массив config.")

        val ids = buildList {
            for (i in 0 until config.length()) {
                val item = config.optJSONObject(i) ?: continue
                item.optString("id").trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }.distinct()

        if (ids.isEmpty()) {
            throw IllegalArgumentException("В config не найдено ни одного ID датчика/виджета.")
        }

        val title = fileName.substringBeforeLast('.', fileName)
            .ifBlank { "Сценарий IoTManager" }

        return IoTManagerJsonImportResult(
            title = title,
            source = scenario,
            sensorIds = ids,
            configCount = config.length()
        )
    }
}

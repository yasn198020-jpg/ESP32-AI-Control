package com.yasn198020.aicontrol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AiCommandResult(
    val action: String,
    val deviceId: String,
    val widgetId: String,
    val value: String,
    val reply: String
)

class AiCommandManager {
    suspend fun interpret(
        endpoint: String,
        apiKey: String,
        model: String,
        command: String,
        devices: List<Device>
    ): Result<AiCommandResult> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) return@withContext Result.failure(Exception("Не задан OpenAI API key"))
            if (endpoint.isBlank()) return@withContext Result.failure(Exception("Не задан AI endpoint"))

            val catalog = JSONArray()
            devices.flatMap { device ->
                device.widgets.map { widget ->
                    JSONObject().apply {
                        put("deviceId", device.id)
                        put("page", widget.page)
                        put("widgetId", widget.id)
                        put("title", widget.title)
                        put("type", widget.type.name)
                        put("value", widget.value)
                    }
                }
            }.forEach { catalog.put(it) }

            val instructions = """
                Ты интерпретатор голосовых команд для приложения управления ESP32 по MQTT.
                Пользователь говорит по-русски.
                Тебе разрешено выбирать только из CATALOG. Никогда не придумывай deviceId или widgetId.
                Управлять можно только виджетами типов TOGGLE или BUTTON.
                "открыть", "открой", "поднять", "включить", "включи" обычно означают value="1".
                "закрыть", "закрой", "опустить", "выключить", "выключи" обычно означают value="0".
                Сначала определи объект и устройство по page/title и смыслу команды.
                Если конкретный управляемый виджет не определяется однозначно, action="clarify" и задай короткий вопрос.
                Не выбирай виджет только потому, что он единственный: смысл названия должен соответствовать команде.
                Для успешной команды action="control", deviceId/widgetId/value должны точно существовать в CATALOG.
                reply — короткий русский ответ пользователю.
            """.trimIndent()

            val input = JSONObject().apply {
                put("model", model.ifBlank { "gpt-5.6-luna" })
                put("store", false)
                put("instructions", instructions)
                put("input", "КОМАНДА: $command\nCATALOG: ${catalog}")
                put("text", JSONObject().apply {
                    put("format", JSONObject().apply {
                        put("type", "json_schema")
                        put("name", "mqtt_voice_command")
                        put("strict", true)
                        put("schema", JSONObject().apply {
                            put("type", "object")
                            put("additionalProperties", false)
                            put("properties", JSONObject().apply {
                                put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("control", "clarify"))))
                                put("deviceId", JSONObject().put("type", "string"))
                                put("widgetId", JSONObject().put("type", "string"))
                                put("value", JSONObject().put("type", "string").put("enum", JSONArray(listOf("0", "1"))))
                                put("reply", JSONObject().put("type", "string"))
                            })
                            put("required", JSONArray(listOf("action", "deviceId", "widgetId", "value", "reply")))
                        })
                    })
                })
            }

            val connection = (URL(endpoint.trim()).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
            }

            connection.outputStream.use { it.write(input.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val responseText = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                return@withContext Result.failure(Exception("AI HTTP $code: $responseText"))
            }

            val response = JSONObject(responseText)
            val output = response.optJSONArray("output")
                ?: return@withContext Result.failure(Exception("AI: отсутствует output"))

            var jsonText: String? = null
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                val contentArray = item.optJSONArray("content") ?: continue
                for (j in 0 until contentArray.length()) {
                    val part = contentArray.optJSONObject(j) ?: continue
                    if (part.optString("type") == "output_text") {
                        jsonText = part.optString("text")
                        break
                    }
                }
                if (!jsonText.isNullOrBlank()) break
            }

            if (jsonText.isNullOrBlank()) {
                return@withContext Result.failure(Exception("AI: пустой ответ"))
            }

            val result = JSONObject(jsonText)
            Result.success(
                AiCommandResult(
                    action = result.optString("action"),
                    deviceId = result.optString("deviceId"),
                    widgetId = result.optString("widgetId"),
                    value = result.optString("value"),
                    reply = result.optString("reply")
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

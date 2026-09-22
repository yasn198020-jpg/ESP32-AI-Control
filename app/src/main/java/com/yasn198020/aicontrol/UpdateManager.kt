package com.yasn198020.aicontrol

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateResult(val message: String, val url: String? = null)

object UpdateManager {
    private const val RELEASES_API = "https://api.github.com/repos/yasn198020-jpg/ESP32-AI-Control/releases/latest"

    fun checkLatest(currentVersion: String, callback: (UpdateResult) -> Unit) {
        Thread {
            val result = try {
                val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "ESP32-AI-Control")
                }
                try {
                    if (connection.responseCode !in 200..299) {
                        UpdateResult("Не удалось проверить обновление. Код GitHub: ${connection.responseCode}")
                    } else {
                        val body = connection.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(body)
                        val tag = json.optString("tag_name").trim()
                        val htmlUrl = json.optString("html_url").trim()
                        if (tag.isBlank()) {
                            UpdateResult("GitHub не сообщил номер последней версии.")
                        } else {
                            val remote = versionNumber(tag)
                            val local = versionNumber(currentVersion)
                            if (remote != null && local != null && remote <= local) {
                                UpdateResult("У вас установлена актуальная версия: $currentVersion")
                            } else {
                                UpdateResult("Доступна новая версия: $tag\\nТекущая версия: $currentVersion", htmlUrl.ifBlank { null })
                            }
                        }
                    }
                } finally { connection.disconnect() }
            } catch (e: Exception) {
                UpdateResult("Не удалось проверить обновление: ${e.message ?: "ошибка сети"}")
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { callback(result) }
        }.start()
    }

    private fun versionNumber(value: String): Int? = Regex("(\\d+)").find(value)?.value?.toIntOrNull()
}
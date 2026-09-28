package com.yasn198020.aicontrol

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateResult(
    val message: String,
    val url: String? = null,
    val apkUrl: String? = null
)

object UpdateManager {
    private const val RELEASES_API = "https://api.github.com/repos/yasn198020-jpg/ESP32-AI-Control/releases/latest"

    fun checkLatest(currentVersionCode: Long, currentVersionName: String, callback: (UpdateResult) -> Unit) {
        Thread {
            val result = try {
                val apiUrl = RELEASES_API + "?t=" + System.currentTimeMillis()
                val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    useCaches = false
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Pragma", "no-cache")
                    setRequestProperty("User-Agent", "ESP32-AI-Control-Updater")
                }
                try {
                    if (connection.responseCode !in 200..299) {
                        UpdateResult("Не удалось проверить обновление. Код GitHub: " + connection.responseCode)
                    } else {
                        val body = connection.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(body)
                        val tag = json.optString("tag_name").trim()
                        val htmlUrl = json.optString("html_url").trim()
                        val assets = json.optJSONArray("assets")
                        var apkUrl: String? = null
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val asset = assets.optJSONObject(i) ?: continue
                                val name = asset.optString("name").trim()
                                val downloadUrl = asset.optString("browser_download_url").trim()
                                if (name == "ESP32-AI-Control-latest.apk" && downloadUrl.isNotBlank()) {
                                    apkUrl = downloadUrl
                                    break
                                }
                            }
                        }
                        val remoteVersionCode = parseVersionCode(tag)
                        when {
                            tag.isBlank() -> UpdateResult("GitHub не сообщил номер последней версии.")
                            remoteVersionCode == null -> UpdateResult("GitHub сообщил некорректную версию: " + tag)
                            remoteVersionCode <= currentVersionCode -> UpdateResult(
                                "Установлена актуальная версия.\n" +
                                    "Установлено: " + currentVersionName + " (code " + currentVersionCode + ")\n" +
                                    "GitHub: " + tag + " (code " + remoteVersionCode + ")"
                            )
                            apkUrl == null -> UpdateResult(
                                "Доступна новая версия: " + tag + ", но APK-файл пока не найден.",
                                htmlUrl.ifBlank { null }
                            )
                            else -> UpdateResult(
                                "Доступна новая версия: " + tag + "\n" +
                                    "Установлено: " + currentVersionName + " (code " + currentVersionCode + ")",
                                htmlUrl.ifBlank { null },
                                apkUrl
                            )
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                UpdateResult("Не удалось проверить обновление: " + (e.message ?: "ошибка сети"))
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { callback(result) }
        }.start()
    }

    fun downloadAndInstall(context: Context, apkUrl: String, callback: (String) -> Unit) {
        Thread {
            val message = try {
                val file = File(context.cacheDir, "updates/ESP32-AI-Control-update.apk")
                file.parentFile?.mkdirs()
                val connection = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Pragma", "no-cache")
                    setRequestProperty("User-Agent", "ESP32-AI-Control-Updater")
                }
                try {
                    if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP " + connection.responseCode)
                    connection.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output, 32 * 1024) } }
                } finally { connection.disconnect() }
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(installIntent)
                "APK скачан. Открываю установку…"
            } catch (e: Exception) {
                "Не удалось установить обновление: " + (e.message ?: "ошибка")
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { callback(message) }
        }.start()
    }

    private fun parseVersionCode(tag: String): Long? {
        val cleaned = tag.trim().removePrefix("v")
        return cleaned.substringAfterLast('.', "").toLongOrNull()
    }
}
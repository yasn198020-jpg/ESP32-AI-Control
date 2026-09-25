package com.yasn198020.aicontrol

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PermissionAuditItem(
    val id: String,
    val title: String,
    val detail: String,
    val granted: Boolean,
    val available: Boolean = true
)

object PermissionAudit {
    private const val TRACE_FILE = "permissions_trace.log"
    private const val MAX_LINES = 400
    private const val MAX_BYTES = 180 * 1024L

    @Volatile
    private var lastSignature: String? = null

    @Synchronized
    fun snapshot(context: Context): List<PermissionAuditItem> {
        val app = context.applicationContext
        val items = buildList {
            val micGranted =
                ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            add(
                PermissionAuditItem(
                    id = "microphone",
                    title = "Микрофон",
                    detail = if (micGranted) "RECORD_AUDIO выдано" else "RECORD_AUDIO не выдано",
                    granted = micGranted
                )
            )

            if (Build.VERSION.SDK_INT >= 33) {
                val notificationsGranted =
                    ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                add(
                    PermissionAuditItem(
                        id = "notifications",
                        title = "Уведомления",
                        detail = if (notificationsGranted) {
                            "POST_NOTIFICATIONS выдано"
                        } else {
                            "POST_NOTIFICATIONS не выдано"
                        },
                        granted = notificationsGranted
                    )
                )
            } else {
                add(
                    PermissionAuditItem(
                        id = "notifications",
                        title = "Уведомления",
                        detail = "Отдельное runtime-разрешение не требуется на этой версии Android",
                        granted = true
                    )
                )
            }

            val powerManager = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val batteryGranted =
                powerManager.isIgnoringBatteryOptimizations(app.packageName)
            add(
                PermissionAuditItem(
                    id = "battery",
                    title = "Исключение из энергосбережения",
                    detail = if (batteryGranted) {
                        "Ограничения Doze для приложения сняты"
                    } else {
                        "Приложение ещё ограничивается энергосбережением"
                    },
                    granted = batteryGranted
                )
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val installGranted = app.packageManager.canRequestPackageInstalls()
                add(
                    PermissionAuditItem(
                        id = "install_unknown",
                        title = "Установка APK из этого источника",
                        detail = if (installGranted) {
                            "Разрешено устанавливать APK"
                        } else {
                            "Разрешение на установку APK не выдано"
                        },
                        granted = installGranted
                    )
                )
            } else {
                add(
                    PermissionAuditItem(
                        id = "install_unknown",
                        title = "Установка APK из этого источника",
                        detail = "Отдельная настройка не требуется на этой версии Android",
                        granted = true
                    )
                )
            }
        }

        val signature = items.joinToString("|") { "${it.id}=${it.granted}" }
        if (signature != lastSignature) {
            val old = lastSignature
            lastSignature = signature
            appendTrace(
                app,
                if (old == null) {
                    "SNAPSHOT ${items.joinToString { "${it.id}=${state(it)}" }}"
                } else {
                    "CHANGE ${items.joinToString { "${it.id}=${state(it)}" }}"
                }
            )
        } else {
            appendTrace(
                app,
                "CHECK ${items.joinToString { "${it.id}=${state(it)}" }}"
            )
        }

        return items
    }

    fun logRuntimeResult(context: Context, micGranted: Boolean, notificationsGranted: Boolean) {
        appendTrace(
            context.applicationContext,
            "RUNTIME_RESULT microphone=${if (micGranted) "GRANTED" else "DENIED"} " +
                "notifications=${if (notificationsGranted) "GRANTED" else "DENIED"}"
        )
        lastSignature = null
    }

    fun openSettings(context: Context, itemId: String) {
        val app = context.applicationContext
        val intent = when (itemId) {
            "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${app.packageName}")
            }
            "install_unknown" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${app.packageName}")
                    }
                } else {
                    return
                }
            }
            else -> return
        }

        try {
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            appendTrace(app, "OPEN_SETTINGS item=$itemId")
        } catch (e: Exception) {
            appendTrace(
                app,
                "OPEN_SETTINGS_FAILED item=$itemId error=${e.javaClass.simpleName}"
            )
            if (itemId == "battery") {
                try {
                    app.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun state(item: PermissionAuditItem): String =
        when {
            !item.available -> "N/A"
            item.granted -> "GRANTED"
            else -> "DENIED"
        }

    private fun appendTrace(context: Context, message: String) {
        try {
            val file = java.io.File(context.filesDir, TRACE_FILE)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(Date())
            val existing = if (file.exists()) file.readLines() else emptyList()
            val next = (existing + "$stamp $message").takeLast(MAX_LINES)
            file.writeText(next.joinToString("\n") + "\n")
            if (file.length() > MAX_BYTES) {
                val trimmed = file.readLines().takeLast(MAX_LINES / 2)
                file.writeText(trimmed.joinToString("\n") + "\n")
            }
        } catch (_: Exception) {
        }
    }
}

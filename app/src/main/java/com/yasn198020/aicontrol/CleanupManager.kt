package com.yasn198020.aicontrol

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import java.io.File

data class CleanupReport(
    val cacheBytesBefore: Long,
    val cacheFilesDeleted: Int,
    val staleWidgetKeysDeleted: Int,
    val historyPointsRemoved: Int
) {
    val cacheBytesDeleted: Long get() = cacheBytesBefore
}

object CleanupManager {
    private const val WIDGET_PREFS = "live_data_widget_preferences"
    private const val CLEANUP_PREFS = "cleanup_state"
    private const val KEY_LAST_AUTO_CLEANUP = "last_auto_cleanup"
    private const val AUTO_CLEANUP_INTERVAL_MS = 24L * 60L * 60L * 1000L

    private val widgetKeyPrefixes = listOf(
        "device_", "widget_", "thresholds_", "below_color_",
        "low_", "high_", "color_low_", "color_mid_", "color_high_"
    )

    fun autoCleanup(context: Context, historyStore: HistoryStore? = null) {
        val app = context.applicationContext
        val state = app.getSharedPreferences(CLEANUP_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = state.getLong(KEY_LAST_AUTO_CLEANUP, 0L)
        if (now - last < AUTO_CLEANUP_INTERVAL_MS) return

        runCatching {
            cleanup(
                context = app,
                historyStore = historyStore,
                clearCache = true,
                clearLogs = false
            )
        }
        state.edit().putLong(KEY_LAST_AUTO_CLEANUP, now).apply()
    }

    fun cleanup(
        context: Context,
        historyStore: HistoryStore? = null,
        clearCache: Boolean = true,
        clearLogs: Boolean = false
    ): CleanupReport {
        val app = context.applicationContext

        val cacheBytes = if (clearCache) directorySize(app.cacheDir) else 0L
        val cacheFiles = if (clearCache) {
            app.cacheDir.listFiles()?.sumOf { if (deleteRecursively(it)) 1 else 0 } ?: 0
        } else 0

        val staleWidgetKeys = cleanupStaleWidgetPreferences(app)
        val historyRemoved = historyStore?.pruneExpired() ?: 0

        if (clearLogs) {
            DiagnosticTrace.clear()
            BackgroundTrace.clear()
            PermissionAudit.clearTrace(app)
        }

        return CleanupReport(
            cacheBytesBefore = cacheBytes,
            cacheFilesDeleted = cacheFiles,
            staleWidgetKeysDeleted = staleWidgetKeys,
            historyPointsRemoved = historyRemoved
        )
    }

    private fun cleanupStaleWidgetPreferences(context: Context): Int {
        val prefs = context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
        val activeIds = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(
                ComponentName(context, LiveDataWidgetProvider::class.java)
            )
            .mapTo(HashSet()) { it.toString() }

        val editor = prefs.edit()
        var removed = 0

        prefs.all.keys.forEach { key ->
            val prefix = widgetKeyPrefixes.firstOrNull { key.startsWith(it) } ?: return@forEach
            val suffix = key.removePrefix(prefix)
            if (suffix.isNotBlank() && suffix.all { it.isDigit() } && suffix !in activeIds) {
                editor.remove(key)
                removed++
            }
        }

        if (removed > 0) editor.apply()
        return removed
    }

    private fun directorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    private fun deleteRecursively(file: File): Boolean {
        return runCatching {
            if (file.isDirectory) {
                file.listFiles()?.forEach { deleteRecursively(it) }
            }
            file.delete()
        }.getOrDefault(false)
    }
}

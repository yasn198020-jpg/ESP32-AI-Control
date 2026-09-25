package com.yasn198020.aicontrol

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LiveDataWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateWidgets(context, appWidgetIds)
    }

    override fun onEnabled(context: Context) {
        updateAll(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { LiveDataWidgetStore.clear(context, it) }
        super.onDeleted(context, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        updateOne(context, appWidgetId, newOptions)
    }

    companion object {
        fun updateAll(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val component = ComponentName(appContext, LiveDataWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) updateWidgets(appContext, ids)
        }

        fun updateOne(context: Context, appWidgetId: Int) {
            if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
            val manager = AppWidgetManager.getInstance(context.applicationContext)
            val options = manager.getAppWidgetOptions(appWidgetId)
            manager.updateAppWidget(
                appWidgetId,
                buildViews(context.applicationContext, appWidgetId, options)
            )
        }

        private fun updateOne(
            context: Context,
            appWidgetId: Int,
            options: android.os.Bundle
        ) {
            if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
            val manager = AppWidgetManager.getInstance(context.applicationContext)
            manager.updateAppWidget(
                appWidgetId,
                buildViews(context.applicationContext, appWidgetId, options)
            )
        }

        private fun updateWidgets(context: Context, appWidgetIds: IntArray) {
            val manager = AppWidgetManager.getInstance(context.applicationContext)
            appWidgetIds.forEach { appWidgetId ->
                manager.updateAppWidget(
                    appWidgetId,
                    buildViews(
                        context.applicationContext,
                        appWidgetId,
                        manager.getAppWidgetOptions(appWidgetId)
                    )
                )
            }
        }

        private fun buildViews(
            context: Context,
            appWidgetId: Int,
            options: android.os.Bundle
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.live_data_widget)
            val runtime = AppRuntime.get(context)
            val selection = LiveDataWidgetStore.get(context, appWidgetId)

            val device = selection?.let { selected ->
                runtime.deviceRepository.snapshot().firstOrNull { it.id == selected.deviceId }
            }
            val widget = selection?.let { selected ->
                device?.widgets?.firstOrNull { it.id == selected.widgetId }
            }

            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 90)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 50)
            val compact = width < 110 || height < 65
            val medium = width < 170 || height < 90

            views.setTextViewTextSize(
                R.id.live_widget_updated,
                android.util.TypedValue.COMPLEX_UNIT_SP,
                if (compact) 9f else if (medium) 10f else 11f
            )
            views.setTextViewTextSize(
                R.id.live_widget_title,
                android.util.TypedValue.COMPLEX_UNIT_SP,
                if (compact) 9f else if (medium) 10f else 11f
            )
            views.setTextViewTextSize(
                R.id.live_widget_value,
                android.util.TypedValue.COMPLEX_UNIT_SP,
                when {
                    compact -> 20f
                    medium -> 26f
                    else -> 30f
                }
            )

            views.setTextViewText(
                R.id.live_widget_updated,
                "Обновлено: " + formatLastUpdated(widget?.lastUpdated)
            )
            views.setTextViewText(
                R.id.live_widget_title,
                when {
                    selection == null -> "Настройте виджет"
                    widget == null -> "Выбранный параметр недоступен"
                    device == null -> "ESP32 недоступен"
                    else -> widget.title
                }
            )
            views.setTextViewText(
                R.id.live_widget_value,
                when {
                    selection == null -> "Нажмите для настройки"
                    widget == null -> "—"
                    device?.online != true -> "—"
                    widget.value.isBlank() -> "Ожидание…"
                    else -> widget.value + widget.unit
                }
            )
            val value = widget?.value?.trim()?.replace(',', '.')?.toFloatOrNull()
            val background = if (value == null || selection == null) {
                Color.rgb(30, 30, 30)
            } else {
                selection.colorFor(value)
            }
            views.setInt(R.id.live_data_widget_root, "setBackgroundColor", background)

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                10000 + appWidgetId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.live_data_widget_root, pendingIntent)
            return views
        }

        private fun formatLastUpdated(timestamp: Long): String {
            if (timestamp <= 0L) return "—"
            return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
        }

    }
}

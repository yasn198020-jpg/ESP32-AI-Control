package com.yasn198020.aicontrol

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.yasn198020.aicontrol.core.Device

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
            updateWidgets(context.applicationContext, intArrayOf(appWidgetId))
        }

        private fun updateWidgets(context: Context, appWidgetIds: IntArray) {
            val manager = AppWidgetManager.getInstance(context.applicationContext)
            appWidgetIds.forEach { appWidgetId ->
                manager.updateAppWidget(
                    appWidgetId,
                    buildViews(context.applicationContext, appWidgetId)
                )
            }
        }

        private fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.live_data_widget)
            val runtime = AppRuntime.get(context)
            val selection = LiveDataWidgetStore.get(context, appWidgetId)

            val device = selection?.let { selected ->
                runtime.deviceRepository.snapshot().firstOrNull { it.id == selected.deviceId }
            }
            val widget = selection?.let { selected ->
                device?.widgets?.firstOrNull { it.id == selected.widgetId }
            }

            views.setTextViewText(
                R.id.live_widget_connection,
                if (runtime.mqtt.isConnected()) "● MQTT подключен" else "○ MQTT нет соединения"
            )
            views.setTextViewText(
                R.id.live_widget_title,
                when {
                    selection == null -> "Настройте виджет"
                    widget == null -> "Выбранный параметр недоступен"
                    device == null -> "ESP32 недоступен"
                    else -> deviceLabel(device) + " • " + widget.title
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
            views.setTextViewText(
                R.id.live_widget_subtitle,
                when {
                    selection == null -> "Добавьте виджет и выберите устройство"
                    widget == null -> "Проверьте настройки виджета"
                    else -> "Обновляется из MQTT в реальном времени"
                }
            )

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

        private fun deviceLabel(device: Device): String = device.name.ifBlank { device.id }
    }
}

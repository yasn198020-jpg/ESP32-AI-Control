package com.yasn198020.aicontrol

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen widget with live MQTT values.
 *
 * The widget does not create its own MQTT client. AppRuntime/MqttBackgroundService
 * remains the single MQTT owner and pushes a widget refresh whenever a status arrives.
 */
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

    companion object {
        private const val MAX_ROWS = 3

        fun updateAll(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val component = ComponentName(appContext, LiveDataWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) {
                updateWidgets(appContext, ids)
            }
        }

        private fun updateWidgets(context: Context, appWidgetIds: IntArray) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)

            appWidgetIds.forEach { appWidgetId ->
                manager.updateAppWidget(
                    appWidgetId,
                    buildViews(appContext)
                )
            }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(
                context.packageName,
                R.layout.live_data_widget
            )

            val runtime = AppRuntime.get(context)
            val displayItems = runtime.deviceRepository
                .snapshot()
                .flatMap { device ->
                    device.widgets
                        .filter { widget ->
                            widget.type == com.yasn198020.aicontrol.core.WidgetState.Type.VALUE &&
                                widget.value.isNotBlank()
                        }
                        .map { widget ->
                            Triple(device.name.ifBlank { device.id }, widget, device.online)
                        }
                }
                .take(MAX_ROWS)

            views.setTextViewText(
                R.id.live_widget_connection,
                if (runtime.mqtt.isConnected()) "● MQTT подключен" else "○ MQTT нет соединения"
            )

            for (index in 0 until MAX_ROWS) {
                val rowId = when (index) {
                    0 -> R.id.live_widget_row_1
                    1 -> R.id.live_widget_row_2
                    else -> R.id.live_widget_row_3
                }
                val titleId = when (index) {
                    0 -> R.id.live_widget_title_1
                    1 -> R.id.live_widget_title_2
                    else -> R.id.live_widget_title_3
                }
                val valueId = when (index) {
                    0 -> R.id.live_widget_value_1
                    1 -> R.id.live_widget_value_2
                    else -> R.id.live_widget_value_3
                }

                val item = displayItems.getOrNull(index)
                if (item == null) {
                    views.setViewVisibility(rowId, android.view.View.GONE)
                } else {
                    val (deviceName, widget, online) = item
                    views.setViewVisibility(rowId, android.view.View.VISIBLE)
                    views.setTextViewText(
                        titleId,
                        if (deviceName.isBlank()) widget.title else deviceName + " • " + widget.title
                    )
                    val value = widget.value + widget.unit
                    views.setTextViewText(
                        valueId,
                        if (online) value else "—"
                    )
                }
            }

            val emptyId = R.id.live_widget_empty
            if (displayItems.isEmpty()) {
                views.setViewVisibility(emptyId, android.view.View.VISIBLE)
                views.setTextViewText(
                    emptyId,
                    if (runtime.mqtt.isConnected()) {
                        "Ожидание данных MQTT…"
                    } else {
                        "Откройте приложение для подключения"
                    }
                )
            } else {
                views.setViewVisibility(emptyId, android.view.View.GONE)
            }

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                1301,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.live_data_widget_root, pendingIntent)

            return views
        }
    }
}

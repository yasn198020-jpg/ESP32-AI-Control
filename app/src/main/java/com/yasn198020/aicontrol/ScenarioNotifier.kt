package com.yasn198020.aicontrol

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object ScenarioNotifier {
    private const val CHANNEL_ID = "scenario_alerts"

    fun notify(context: Context, scenario: Scenario, rawValue: String) {
        createChannel(context)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val text = scenario.message
            .replace("{value}", rawValue)
            .replace("{threshold}", scenario.threshold.toString().removeSuffix(".0"))
            .replace("{device}", scenario.deviceId)
            .replace("{widget}", scenario.widgetId)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle(scenario.title.ifBlank { "Сценарий" })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(
            scenario.id.hashCode() and 0x7fffffff,
            notification
        )
    }

    private fun createChannel(context: Context) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(NotificationChannel(
                    CHANNEL_ID, "Сценарии", NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Уведомления от сценариев автоматизации" })
            }
        }
    }
}

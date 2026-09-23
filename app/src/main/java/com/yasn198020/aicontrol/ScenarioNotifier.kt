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
    
    fun test(context: Context) {
        val testScenario = Scenario(
            title = "Тест уведомления",
            deviceId = "test",
            widgetId = "notification",
            threshold = 25.0,
            message = "Уведомления работают. Тестовое значение: {value}°C"
        )
        notify(context, testScenario, "25.0")
    }
    private const val TAG = "SCENARIO_NOTIFY"

    fun notify(context: Context, scenario: Scenario, rawValue: String) {
        android.util.Log.d(TAG, "TRIGGER id=${scenario.id} value=$rawValue threshold=${scenario.threshold}")
        createChannel(context)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.w(TAG, "POST_NOTIFICATIONS permission missing")
            return
        }

        val text = scenario.message
            .replace("{value}", rawValue)
            .replace("{threshold}", scenario.threshold.toString().removeSuffix(".0"))
            .replace("{device}", scenario.deviceId)
            .replace("{widget}", scenario.widgetId)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(scenario.title.ifBlank { "Сценарий" })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        android.util.Log.d(TAG, "SHOW notification text=$text")
        NotificationManagerCompat.from(context).notify(
            scenario.id.hashCode() and 0x7fffffff,
            notification
        )
    }

    fun notifyVerification(context: Context, scenario: Scenario, success: Boolean, rawValue: String) {
        createChannel(context)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val template = if (success) scenario.verifySuccessMessage else scenario.verifyFailureMessage
        val text = template
            .replace("{value}", rawValue)
            .replace("{threshold}", scenario.verifyValue.toString().removeSuffix(".0"))
            .replace("{device}", scenario.verifyDeviceId)
            .replace("{widget}", scenario.verifyWidgetId)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (success) "Подтверждение: ${scenario.title}" else "Не подтверждено: ${scenario.title}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(
            (scenario.id.hashCode() + if (success) 1001 else 1002) and 0x7fffffff,
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

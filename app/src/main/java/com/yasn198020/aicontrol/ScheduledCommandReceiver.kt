package com.yasn198020.aicontrol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import androidx.core.app.NotificationCompat

class ScheduledCommandReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "scheduled_commands"
        private const val MAX_ATTEMPTS = 20
        private const val RETRY_DELAY_MILLIS = 30_000L
    }

    override fun onReceive(context: Context, intent: android.content.Intent?) {
        if (intent?.action != ScheduledCommandScheduler.ACTION_EXECUTE) return

        val id = intent.getLongExtra(ScheduledCommandScheduler.EXTRA_ID, -1L)
        if (id <= 0L) return

        val appContext = context.applicationContext
        val item = ScheduledCommandStore.find(appContext, id) ?: return
        if (item.status != ScheduledCommand.STATUS_WAITING) return

        if (item.executeAtMillis > System.currentTimeMillis() + 1000L) {
            ScheduledCommandScheduler.arm(appContext, item)
            return
        }

        val runtime = AppRuntime.get(appContext)
        val mqtt = runtime.mqtt

        if (!mqtt.isConnected()) {
            runtime.ensureConnected()
            retry(appContext, item, "MQTT disconnected")
            return
        }

        val ok = runCatching {
            mqtt.publishControl(item.deviceId, item.widgetId, item.value)
        }.getOrDefault(false)

        if (ok) {
            ScheduledCommandStore.update(
                appContext,
                item.copy(status = ScheduledCommand.STATUS_DONE)
            )
            ScheduledCommandScheduler.cancelAlarm(appContext, item.id)
            DiagnosticTrace.step(
                "SCHEDULE",
                "executed id=${item.id} device=${item.deviceId} widget=${item.widgetId} value=${item.value}"
            )
            notifyExecution(appContext, item)
        } else {
            retry(appContext, item, "publishControl returned false")
        }
    }

    private fun retry(context: Context, item: ScheduledCommand, reason: String) {
        val nextAttempts = item.attempts + 1
        if (nextAttempts > MAX_ATTEMPTS) {
            ScheduledCommandStore.update(
                context,
                item.copy(
                    status = ScheduledCommand.STATUS_ERROR,
                    attempts = nextAttempts
                )
            )
            ScheduledCommandScheduler.cancelAlarm(context, item.id)
            DiagnosticTrace.error(
                "SCHEDULE failed id=${item.id} attempts=${nextAttempts} reason=${reason}"
            )
            notifyExecution(context, item, failed = true)
            return
        }

        val retryItem = item.copy(
            attempts = nextAttempts,
            executeAtMillis = System.currentTimeMillis() + RETRY_DELAY_MILLIS
        )
        ScheduledCommandStore.update(context, retryItem)
        ScheduledCommandScheduler.arm(context, retryItem)
        DiagnosticTrace.step(
            "SCHEDULE",
            "retry id=${item.id} attempt=${nextAttempts} reason=${reason}"
        )
    }

    private fun notifyExecution(
        context: Context,
        item: ScheduledCommand,
        failed: Boolean = false
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Запланированные команды",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }

        val text = if (failed) {
            "Не удалось выполнить: ${item.title}"
        } else {
            "Выполнено: ${item.title}"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle("Марфа")
            .setContentText(text)
            .setAutoCancel(true)
            .build()

        manager.notify((item.id and 0x7fffffff).toInt(), notification)
    }
}

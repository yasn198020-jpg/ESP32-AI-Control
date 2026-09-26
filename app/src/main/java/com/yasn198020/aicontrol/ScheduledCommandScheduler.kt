package com.yasn198020.aicontrol

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object ScheduledCommandScheduler {
    const val ACTION_EXECUTE = "com.yasn198020.aicontrol.action.EXECUTE_SCHEDULED_COMMAND"
    const val EXTRA_ID = "scheduled_command_id"

    fun schedule(
        context: Context,
        deviceId: String,
        widgetId: String,
        value: String,
        title: String,
        sourceText: String,
        executeAtMillis: Long
    ): ScheduledCommand {
        val item = ScheduledCommandStore.add(
            context = context,
            deviceId = deviceId,
            widgetId = widgetId,
            value = value,
            title = title,
            sourceText = sourceText,
            executeAtMillis = executeAtMillis
        )
        arm(context, item)
        DiagnosticTrace.step("SCHEDULE", "created id=${item.id} at=${item.executeAtMillis} device=${item.deviceId} widget=${item.widgetId} value=${item.value}")
        return item
    }

    fun restoreAll(context: Context) {
        ScheduledCommandStore.pending(context).forEach { arm(context, it) }
    }

    fun cancel(context: Context, id: Long) {
        val item = ScheduledCommandStore.find(context, id) ?: return
        cancelAlarm(context, id)
        ScheduledCommandStore.update(context, item.copy(status = ScheduledCommand.STATUS_CANCELLED))
        DiagnosticTrace.step("SCHEDULE", "cancelled id=$id")
    }

    internal fun arm(context: Context, item: ScheduledCommand) {
        val triggerAt = maxOf(item.executeAtMillis, System.currentTimeMillis() + 1000L)
        val intent = Intent(context, ScheduledCommandReceiver::class.java).apply {
            action = ACTION_EXECUTE
            putExtra(EXTRA_ID, item.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(item.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(AlarmManager::class.java)

        if (alarmManager != null) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent
            )
        }
    }

    internal fun cancelAlarm(context: Context, id: Long) {
        val intent = Intent(context, ScheduledCommandReceiver::class.java).apply {
            action = ACTION_EXECUTE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun requestCode(id: Long): Int =
        (id xor (id ushr 32)).toInt() and 0x7fffffff
}

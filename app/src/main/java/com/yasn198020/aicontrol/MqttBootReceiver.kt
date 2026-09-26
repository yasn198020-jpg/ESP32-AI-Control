package com.yasn198020.aicontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the MQTT foreground service after boot, app update, or a service self-restart alarm. */
class MqttBootReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_RESTART = "com.yasn198020.aicontrol.action.RESTART_MQTT_SERVICE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_RESTART -> {
                try {
                    ScheduledCommandScheduler.restoreAll(context.applicationContext)
                    MqttBackgroundService.start(context.applicationContext)
                } catch (e: Exception) {
                    android.util.Log.e("MQTT_BACKGROUND", "receiver failed", e)
                }
            }
        }
    }
}

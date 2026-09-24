package com.yasn198020.aicontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Single owner of the always-on MQTT background lifetime.
 *
 * It never creates its own MQTT client. AppRuntime owns the single MqttManager.
 * The service only keeps that runtime alive and asks it to reconnect when needed.
 */
class MqttBackgroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "mqtt_background"
        private const val NOTIFICATION_ID = 1301
        private const val CHECK_INTERVAL_MS = 10_000L

        fun start(context: Context) {
            val intent = Intent(context, MqttBackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                AppRuntime.get(applicationContext).ensureConnected()
            } catch (e: Exception) {
                android.util.Log.e("MQTT_BACKGROUND", "ensureConnected failed", e)
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        running = true

        // Create/keep the single process-wide runtime alive immediately.
        AppRuntime.get(applicationContext).ensureConnected()
        handler.post(checkRunnable)

        android.util.Log.d("MQTT_BACKGROUND", "background MQTT runtime started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY lets Android recreate this service after a process kill.
        AppRuntime.get(applicationContext).ensureConnected()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)

        // IMPORTANT: do not disconnect MQTT here.
        // The runtime owns the MQTT client; Android may recreate this service.
        android.util.Log.d("MQTT_BACKGROUND", "service destroyed, MQTT runtime kept alive")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the service independent from the Activity task.
        AppRuntime.get(applicationContext).ensureConnected()
        super.onTaskRemoved(rootIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "MQTT фон",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Постоянное MQTT-соединение, сценарии и телеметрия"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun createNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle("ESP32 AI Control")
            .setContentText("MQTT работает в фоне")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null
}

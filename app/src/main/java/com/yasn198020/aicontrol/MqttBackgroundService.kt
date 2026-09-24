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
 * Keeps the process alive for background MQTT work.
 *
 * MQTT itself is NOT owned by this Service. AppRuntime owns one MqttManager
 * for the whole process, and both foreground UI and this service use it.
 */
class MqttBackgroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "mqtt_background"
        private const val NOTIFICATION_ID = 1201
        private const val CHECK_MS = 20_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var runtime: AppRuntime

    private val reconnectTask = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("mqtt_background_enabled", false)) {
                handler.removeCallbacks(this)
                stopSelf()
                return
            }

            runtime.ensureConnected()
            handler.postDelayed(this, CHECK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("mqtt_background_enabled", false)) {
            stopSelf()
            return
        }

        runtime = AppRuntime.get(applicationContext)

        createNotificationChannel()
        startAsForeground()

        android.util.Log.d(
            "MQTT_BG",
            "Background service started; using shared AppRuntime/MqttManager"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        if (!prefs.getBoolean("mqtt_background_enabled", false)) {
            handler.removeCallbacksAndMessages(null)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        runtime.ensureConnected()

        handler.removeCallbacks(reconnectTask)
        handler.post(reconnectTask)
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MQTT connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Persistent MQTT connection while ESP32 AI Control is in the background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun startAsForeground() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.yasn198020.aicontrol.R.drawable.app_icon)
            .setContentTitle("ESP32 AI Control")
            .setContentText("MQTT connection active in background")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        // Important: do NOT disconnect MQTT here. The shared runtime may still
        // be used by the foreground Activity, and it owns the only MQTT client.
        handler.removeCallbacksAndMessages(null)
        android.util.Log.d(
            "MQTT_BG",
            "Background service stopped; shared MQTT runtime remains intact"
        )
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

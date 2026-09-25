package com.yasn198020.aicontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.app.AlarmManager
import android.app.PendingIntent
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
    private var wakeLock: PowerManager.WakeLock? = null

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val runtime = AppRuntime.get(applicationContext)
                val staleRecovered = runtime.mqtt.reconnectIfStale(90_000L)
                DiagnosticTrace.system(
                    "SERVICE watchdog connected=" + runtime.mqtt.isConnected() +
                        " connecting=" + runtime.mqtt.isConnecting() +
                        " staleRecovered=" + staleRecovered
                )
                if (!staleRecovered) runtime.ensureConnected()
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
        acquireBackgroundWakeLock()
        requestBatteryOptimizationExemptionOnce()
        running = true

        // Create/keep the single process-wide runtime alive immediately.
        // Scenarios must remain available while the Activity is stopped.
        val runtime = AppRuntime.get(applicationContext)
        runtime.ensureConnected()
        handler.post(checkRunnable)

        DiagnosticTrace.system("SERVICE created: foreground MQTT service started")
        android.util.Log.d("MQTT_BACKGROUND", "background MQTT runtime started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY lets Android recreate this service after a process kill.
        val runtime = AppRuntime.get(applicationContext)
        DiagnosticTrace.system("SERVICE onStartCommand")
        runtime.scenarioEngine.setRuntimeActive(true)
        runtime.ensureConnected()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        // IMPORTANT: do not disconnect MQTT here.
        // The runtime owns the MQTT client; Android may recreate this service.
        DiagnosticTrace.system("SERVICE destroyed: MQTT runtime kept alive")
        android.util.Log.d("MQTT_BACKGROUND", "service destroyed, MQTT runtime kept alive")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        DiagnosticTrace.system("SERVICE task removed: scheduling restart")
        scheduleServiceRestart()
        // Keep the service independent from the Activity task.
        val runtime = AppRuntime.get(applicationContext)
        runtime.scenarioEngine.setRuntimeActive(true)
        runtime.ensureConnected()
        super.onTaskRemoved(rootIntent)
    }


    private fun scheduleServiceRestart() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, MqttBootReceiver::class.java)
                .setAction(MqttBootReceiver.ACTION_RESTART)
            val pending = PendingIntent.getBroadcast(
                this,
                7301,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 5_000L,
                pending
            )
        } catch (e: Exception) {
            android.util.Log.e("MQTT_BACKGROUND", "restart alarm failed", e)
        }
    }

    private fun acquireBackgroundWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ESP32AIControl:MQTTBackground"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            android.util.Log.d("MQTT_BACKGROUND", "partial wake lock acquired")
        } catch (e: Exception) {
            android.util.Log.e("MQTT_BACKGROUND", "wake lock failed", e)
        }
    }

    private fun requestBatteryOptimizationExemptionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("battery_optimization_prompted", false)) return

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = packageName
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        } catch (e: Exception) {
            android.util.Log.e("MQTT_BACKGROUND", "battery optimization request failed", e)
        } finally {
            prefs.edit().putBoolean("battery_optimization_prompted", true).apply()
        }
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
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null
}

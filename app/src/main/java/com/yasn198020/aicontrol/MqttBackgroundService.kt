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
        private const val CHECK_INTERVAL_MS = 30_000L

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
    private var backgroundCheckCount = 0L

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!running) return

            backgroundCheckCount++

            try {
                val runtime = AppRuntime.get(applicationContext)

                val beforeConnected = runtime.mqtt.isConnected()
                val beforeConnecting = runtime.mqtt.isConnecting()

                DiagnosticTrace.system(
                    "BACKGROUND CHECK #" + backgroundCheckCount +
                        " START connected=" + beforeConnected +
                        " connecting=" + beforeConnecting +
                        " thread=" + Thread.currentThread().name
                )

                // Do not trust isConnected() alone: a dead TCP session can remain
                // visible as connected until Paho detects the failure.
                val staleReconnect = runtime.mqtt.reconnectIfStale()
                if (staleReconnect) {
                    DiagnosticTrace.system(
                        "BACKGROUND CHECK #" + backgroundCheckCount +
                            " MQTT watchdog forced reconnect"
                    )
                }

                runtime.ensureConnected()

                val afterConnected = runtime.mqtt.isConnected()
                val afterConnecting = runtime.mqtt.isConnecting()

                DiagnosticTrace.system(
                    "BACKGROUND CHECK #" + backgroundCheckCount +
                        " MQTT afterEnsure connected=" + afterConnected +
                        " connecting=" + afterConnecting +
                        " changed=" + (beforeConnected != afterConnected)
                )

                // Flush telemetry/history independently from MQTT packet reception.
                runtime.historyStore.flushNow()

                DiagnosticTrace.system(
                    "BACKGROUND CHECK #" + backgroundCheckCount +
                        " HISTORY flushed diagnostics=" + runtime.historyStore.diagnostics()
                )

                DiagnosticTrace.system(
                    "BACKGROUND CHECK #" + backgroundCheckCount +
                        " END mqtt=" + runtime.mqtt.diagnostics()
                )

                android.util.Log.d(
                    "MQTT_BACKGROUND",
                    "CHECK #" + backgroundCheckCount +
                        " beforeConnected=" + beforeConnected +
                        " beforeConnecting=" + beforeConnecting +
                        " staleReconnect=" + staleReconnect +
                        " afterConnected=" + afterConnected +
                        " afterConnecting=" + afterConnecting
                )
            } catch (e: Exception) {
                DiagnosticTrace.system(
                    "BACKGROUND CHECK #" + backgroundCheckCount +
                        " ERROR " + (e.message ?: e.javaClass.simpleName)
                )
                android.util.Log.e("MQTT_BACKGROUND", "background check failed", e)
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
        // Scenarios must remain available while the Activity is stopped.
        val runtime = AppRuntime.get(applicationContext)
        DiagnosticTrace.system(
            "BACKGROUND SERVICE onCreate runtimeBefore=" + runtime.mqtt.diagnostics()
        )
        runtime.ensureConnected()
        DiagnosticTrace.system(
            "BACKGROUND SERVICE onCreate runtimeAfter=" + runtime.mqtt.diagnostics()
        )

        // First diagnostic pass immediately, then every 30 seconds.
        handler.post(checkRunnable)

        DiagnosticTrace.system("SERVICE created: foreground MQTT service started")
        android.util.Log.d("MQTT_BACKGROUND", "background MQTT runtime started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY lets Android recreate this service after a process kill.
        val runtime = AppRuntime.get(applicationContext)
        DiagnosticTrace.system(
            "BACKGROUND SERVICE onStartCommand startId=" + startId +
                " flags=" + flags +
                " mqtt=" + runtime.mqtt.diagnostics()
        )
        runtime.scenarioEngine.setRuntimeActive(true)
        runtime.ensureConnected()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)

        // IMPORTANT: do not disconnect MQTT here.
        // The runtime owns the MQTT client; Android may recreate this service.
        DiagnosticTrace.system(
            "BACKGROUND SERVICE onDestroy checks=" + backgroundCheckCount +
                " mqtt=" + AppRuntime.get(applicationContext).mqtt.diagnostics()
        )
        DiagnosticTrace.system("SERVICE destroyed: MQTT runtime kept alive")
        android.util.Log.d("MQTT_BACKGROUND", "service destroyed, MQTT runtime kept alive")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        DiagnosticTrace.system(
            "BACKGROUND SERVICE onTaskRemoved checks=" + backgroundCheckCount
        )
        scheduleServiceRestart()

        // Keep the service independent from the Activity task.
        val runtime = AppRuntime.get(applicationContext)
        runtime.scenarioEngine.setRuntimeActive(true)
        runtime.ensureConnected()

        DiagnosticTrace.system(
            "BACKGROUND SERVICE onTaskRemoved ensureConnected done mqtt=" +
                runtime.mqtt.diagnostics()
        )
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
            DiagnosticTrace.system("BACKGROUND SERVICE restart alarm scheduled +5000ms")
        } catch (e: Exception) {
            DiagnosticTrace.system(
                "BACKGROUND SERVICE restart alarm failed " +
                    (e.message ?: e.javaClass.simpleName)
            )
            android.util.Log.e("MQTT_BACKGROUND", "restart alarm failed", e)
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

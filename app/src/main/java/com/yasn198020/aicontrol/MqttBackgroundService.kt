package com.yasn198020.aicontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.app.AlarmManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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
        private const val CHECK_INTERVAL_MS = 15_000L
        private const val STUCK_CONNECT_MS = 25_000L
        private const val STUCK_RECONNECT_MS = 45_000L

        fun start(context: Context) {
            val intent = Intent(context, MqttBackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val supervisor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "MQTT-BackgroundSupervisor").apply {
                isDaemon = true
            }
        }

    // Dedicated diagnostic timer. It does not perform MQTT work.
    private val heartbeat: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "MQTT-BackgroundHeartbeat").apply {
                isDaemon = true
            }
        }

    @Volatile
    private var heartbeatCount = 0L

    @Volatile
    private var lastHeartbeatElapsedRealtime = 0L

    @Volatile
    private var running = false

    @Volatile
    private var backgroundCheckCount = 0L

    @Volatile
    private var lastCheckElapsedRealtime = 0L

    private var supervisorFuture: ScheduledFuture<*>? = null

    private fun runBackgroundCheck(reason: String) {
        if (!running) return

        val now = SystemClock.elapsedRealtime()
        val previous = lastCheckElapsedRealtime
        val gapMs = if (previous == 0L) -1L else now - previous
        lastCheckElapsedRealtime = now

        val checkNumber = ++backgroundCheckCount

        try {
            val runtime = AppRuntime.get(applicationContext)

            val beforeConnected = runtime.mqtt.isConnected()
            val beforeConnecting = runtime.mqtt.isConnecting()

            DiagnosticTrace.system(
                "BACKGROUND CHECK #" + checkNumber +
                    " START reason=" + reason +
                    " gapMs=" + gapMs +
                    " connected=" + beforeConnected +
                    " connecting=" + beforeConnecting +
                    " thread=" + Thread.currentThread().name
            )

            if (gapMs > CHECK_INTERVAL_MS * 2) {
                DiagnosticTrace.system(
                    "BACKGROUND CHECK #" + checkNumber +
                        " DELAYED gapMs=" + gapMs +
                        " expectedMs=" + CHECK_INTERVAL_MS +
                        " note=supervisor scheduling can be delayed while device CPU sleeps"
                )
            }

            val stuckInitialConnect =
                runtime.mqtt.reconnectIfConnectingTooLong(STUCK_CONNECT_MS)

            if (stuckInitialConnect) {
                DiagnosticTrace.system(
                    "BACKGROUND CHECK #" + checkNumber +
                        " MQTT replaced stuck initial CONNECTING client"
                )
            }

            val stuckAutomaticReconnect =
                runtime.mqtt.reconnectIfAutomaticReconnectStuck(STUCK_RECONNECT_MS)

            if (stuckAutomaticReconnect) {
                DiagnosticTrace.system(
                    "BACKGROUND CHECK #" + checkNumber +
                        " MQTT replaced stuck automatic-reconnect client"
                )
            }

            // If there is no connection attempt at all, ask AppRuntime to start
            // one. If Paho is reconnecting, isConnecting() stays true and no
            // second MQTT client can be created.
            runtime.ensureConnected()

            val afterConnected = runtime.mqtt.isConnected()
            val afterConnecting = runtime.mqtt.isConnecting()

            DiagnosticTrace.system(
                "BACKGROUND CHECK #" + checkNumber +
                    " MQTT afterEnsure connected=" + afterConnected +
                    " connecting=" + afterConnecting +
                    " changed=" + (beforeConnected != afterConnected) +
                    " initialConnectReset=" + stuckInitialConnect +
                    " reconnectReset=" + stuckAutomaticReconnect
            )

            if (DiagnosticTrace.isForeground()) {
                runtime.historyStore.flushNow()
                DiagnosticTrace.system(
                    "BACKGROUND CHECK #" + checkNumber +
                        " HISTORY flushed foreground=true diagnostics=" + runtime.historyStore.diagnostics()
                )
            } else {
                DiagnosticTrace.system(
                    "BACKGROUND CHECK #" + checkNumber +
                        " HISTORY skipped background graph recording disabled diagnostics=" + runtime.historyStore.diagnostics()
                )
            }

            DiagnosticTrace.system(
                "BACKGROUND CHECK #" + checkNumber +
                    " END durationMs=" + (SystemClock.elapsedRealtime() - now) +
                    " mqtt=" + runtime.mqtt.diagnostics()
            )

            android.util.Log.d(
                "MQTT_BACKGROUND",
                "CHECK #" + checkNumber +
                    " reason=" + reason +
                    " gapMs=" + gapMs +
                    " beforeConnected=" + beforeConnected +
                    " beforeConnecting=" + beforeConnecting +
                    " initialConnectReset=" + stuckInitialConnect +
                    " reconnectReset=" + stuckAutomaticReconnect +
                    " afterConnected=" + afterConnected +
                    " afterConnecting=" + afterConnecting
            )
        } catch (e: Throwable) {
            // Never let an unchecked failure escape the scheduled task.
            // ScheduledExecutorService cancels a repeating task when its
            // runnable throws, which would silently stop all future background
            // checks while the Android service itself is still alive.
            DiagnosticTrace.system(
                "BACKGROUND CHECK #" + checkNumber +
                    " ERROR " + (e.message ?: e.javaClass.simpleName) +
                    " thread=" + Thread.currentThread().name
            )
            android.util.Log.e("MQTT_BACKGROUND", "background check failed", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        running = true


        // The MQTT supervisor runs off the main looper so UI/main-thread stalls
        // cannot stop background reconnects or history persistence.
        val runtime = AppRuntime.get(applicationContext)
        DiagnosticTrace.system(
            "BACKGROUND SERVICE onCreate runtimeBefore=" + runtime.mqtt.diagnostics()
        )

        supervisorFuture = supervisor.scheduleWithFixedDelay(
            { runBackgroundCheck("scheduled") },
            0L,
            CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )

        heartbeat.scheduleAtFixedRate(
            {
                if (!running) return@scheduleAtFixedRate

                val now = SystemClock.elapsedRealtime()
                val previous = lastHeartbeatElapsedRealtime
                val deltaMs = if (previous == 0L) -1L else now - previous
                lastHeartbeatElapsedRealtime = now
                val count = ++heartbeatCount

                DiagnosticTrace.system(
                    "HEARTBEAT[MqttBackgroundService] #" + count +
                        " deltaMs=" + deltaMs +
                        " expectedMs=" + CHECK_INTERVAL_MS +
                        " thread=" + Thread.currentThread().name +
                        " uptimeMs=" + now
                )
            },
            0L,
            CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )

        DiagnosticTrace.system(
            "BACKGROUND SERVICE onCreate supervisorScheduled intervalMs=" +
                CHECK_INTERVAL_MS +
                " thread=MQTT-BackgroundSupervisor"
        )
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
        runCatching {
            supervisor.execute { runBackgroundCheck("onStartCommand") }
        }.onFailure {
            DiagnosticTrace.system(
                "BACKGROUND SERVICE onStartCommand supervisor execute failed " +
                    (it.message ?: it.javaClass.simpleName)
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        supervisorFuture?.cancel(false)
        supervisorFuture = null
        supervisor.shutdownNow()
        heartbeat.shutdownNow()

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
        runCatching {
            supervisor.execute { runBackgroundCheck("onTaskRemoved") }
        }.onFailure {
            DiagnosticTrace.system(
                "BACKGROUND SERVICE onTaskRemoved supervisor execute failed " +
                    (it.message ?: it.javaClass.simpleName)
            )
        }

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

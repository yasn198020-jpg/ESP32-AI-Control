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

class MqttBackgroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "mqtt_background"
        private const val NOTIFICATION_ID = 1201
        private const val CHECK_MS = 20_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var mqtt: MqttManager? = null
    private lateinit var historyStore: HistoryStore
    private var scenarioEngine: ScenarioEngine? = null
    private var scenarioActionExecutor: ScenarioActionExecutor? = null

    private val reconnectTask = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("mqtt_background_enabled", false)) {
                handler.removeCallbacks(this)
                stopSelf()
                return
            }

            val manager = mqtt
            if (manager != null && !manager.isConnected() && !manager.isConnecting()) {
                connectFromSavedSettings(manager)
            }
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
        // Once this service is started, it is the background MQTT/scenario owner.
        // Do not rely on a stale foreground-owner flag left by a previous activity instance.
        prefs.edit().putBoolean("mqtt_foreground_owner", false).apply()
        createNotificationChannel()
        startAsForeground()
        historyStore = HistoryStore(prefs)
        val scenarioStore = ScenarioStore(prefs)
        scenarioActionExecutor = ScenarioActionExecutor()
        scenarioEngine = ScenarioEngine(
            scenarioStore,
            onTrigger = { scenario, rawValue, _ ->
                if (scenario.notificationEnabled) {
                    ScenarioNotifier.notify(this, scenario, rawValue)
                }
                scenarioActionExecutor?.execute(scenario)
            },
            onVerificationResult = { scenario, success, rawValue ->
                if (scenario.notificationEnabled) {
                    ScenarioNotifier.notifyVerification(this, scenario, success, rawValue)
                }
            }
        )
        mqtt = MqttManager(
            onLog = { message -> android.util.Log.d("MQTT_BG", message) },
            onConnected = { connected ->
                android.util.Log.d("MQTT_BG", "connected=$connected")
            },
            onStatus = { deviceId, widgetId, value ->
                android.util.Log.d("MQTT_BG", "status $deviceId/$widgetId=$value")
                historyStore.add(deviceId, widgetId, value)
                scenarioEngine?.onValue(deviceId, widgetId, value)
            },
            onConfig = { deviceId, widgetId, label, type, page, topic, order, raw ->
                android.util.Log.d("MQTT_BG", "config $deviceId/$widgetId type=$type topic=$topic")
            }
        )
        // Restore the latest known telemetry before live MQTT callbacks arrive.
        // This is important for multi-condition scenarios: the background service
        // can start with an empty in-memory value set while the device does not
        // immediately resend every widget state.
        historyStore.latestValues().forEach { (key, value) ->
            val parts = key.split("/", limit = 2)
            if (parts.size == 2) {
                scenarioEngine?.restoreValue(parts[0], parts[1], value)
            }
        }

        scenarioActionExecutor?.mqtt = mqtt
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        if (!prefs.getBoolean("mqtt_background_enabled", false)) {
            handler.removeCallbacksAndMessages(null)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Foreground handoff is controlled by MainActivity.stopService().
        // A stale foreground-owner flag must never prevent this explicitly
        // started background service from running scenarios.
        mqtt?.let { manager ->
            if (!manager.isConnected() && !manager.isConnecting()) {
                connectFromSavedSettings(manager)
            }
        }
        handler.removeCallbacks(reconnectTask)
        handler.postDelayed(reconnectTask, CHECK_MS)
        return START_STICKY
    }

    private fun connectFromSavedSettings(manager: MqttManager) {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("mqtt_background_enabled", false) ||
            prefs.getBoolean("mqtt_foreground_owner", false)) {
            handler.removeCallbacks(reconnectTask)
            return
        }

        val host = prefs.getString("mqtt_host", "m4.wqtt.ru") ?: "m4.wqtt.ru"
        val port = prefs.getString("mqtt_port", "1883")?.toIntOrNull() ?: 1883
        val tls = prefs.getBoolean("mqtt_tls", false)
        val prefix = prefs.getString("mqtt_prefix", "IoTManager") ?: "IoTManager"
        val username = prefs.getString("mqtt_user", "") ?: ""
        val password = prefs.getString("mqtt_pass", "") ?: ""

        manager.connect(host, port, prefix, username, password, tls)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MQTT connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent MQTT connection while ESP32 AI Control is in the background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
        handler.removeCallbacksAndMessages(null)
        scenarioEngine?.shutdown()
        scenarioEngine = null
        scenarioActionExecutor?.mqtt = null
        scenarioActionExecutor = null
        mqtt?.disconnect()
        mqtt = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

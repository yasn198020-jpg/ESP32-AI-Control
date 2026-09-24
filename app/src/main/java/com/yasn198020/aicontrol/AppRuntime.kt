package com.yasn198020.aicontrol

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Single process-wide owner of MQTT, telemetry history and scenario execution.
 *
 * The foreground Activity and MqttBackgroundService both use this same runtime.
 * This prevents two MQTT clients and two ScenarioEngine instances from competing
 * during foreground/background transitions.
 */
class AppRuntime private constructor(private val appContext: Context) {

    interface UiListener {
        fun onLog(message: String)
        fun onConnected(value: Boolean)
        fun onStatus(deviceId: String, widgetId: String, value: String)
        fun onConfig(
            deviceId: String,
            widgetId: String,
            label: String,
            widgetType: String,
            page: String,
            topic: String,
            order: Int,
            raw: String
        )
    }

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val uiListeners = CopyOnWriteArraySet<UiListener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    val historyStore: HistoryStore = HistoryStore(prefs)
    val scenarioStore: ScenarioStore = ScenarioStore(prefs)
    val scenarioActionExecutor: ScenarioActionExecutor = ScenarioActionExecutor()

    private var currentTriggeredScenario: ((Scenario, String) -> Unit)? = null

    val scenarioEngine: ScenarioEngine = ScenarioEngine(
        onTrigger = { scenario, rawValue, _ ->
            // This callback executes the action synchronously. The MQTT event
            // handler captures the scenario/raw value and sends the notification
            // after runtimeActive is returned to false.
            scenarioActionExecutor.execute(scenario)
            currentTriggeredScenario?.invoke(scenario, rawValue)
        },
        onVerificationResult = { scenario, success, rawValue ->
            if (scenario.notificationEnabled) {
                ScenarioNotifier.notifyVerification(
                    appContext,
                    scenario,
                    success,
                    rawValue
                )
            }
        }
    )

    val mqtt: MqttManager = MqttManager(
        onLog = { message ->
            uiListeners.forEach { it.onLog(message) }
            android.util.Log.d("MQTT_RUNTIME", message)
        },
        onConnected = { connected ->
            uiListeners.forEach { it.onConnected(connected) }
        },
        onStatus = { deviceId, widgetId, value ->
            // Paho delivers MQTT messages on its own callback thread, which is
            // already independent of Activity/main UI. Process scenarios directly
            // here so background execution cannot depend on an Activity-owned
            // executor or its lifecycle.
            var triggeredScenario: Scenario? = null
            var triggeredRawValue: String? = null
            val originalEngine = scenarioEngine
            currentTriggeredScenario = { scenario, raw -> 
                triggeredScenario = scenario
                triggeredRawValue = raw
            }

            try {
                historyStore.add(deviceId, widgetId, value)

                // Exact diagnostic sequence for every MQTT event:
                // false -> true -> execute action -> false -> notification.
                originalEngine.setRuntimeActive(false)
                originalEngine.setRuntimeActive(true)
                try {
                    // Capture the scenario through the trigger callback.
                    // The action itself is executed synchronously by onTrigger.
                    val previous = triggeredScenario
                    originalEngine.onValue(deviceId, widgetId, value)
                    if (previous == null && triggeredScenario == null) {
                        // No trigger on this MQTT event.
                    }
                } finally {
                    originalEngine.setRuntimeActive(false)
                }

                // Notification is controlled by the scenario's saved flag and
                // is deliberately delivered after runtimeActive=false.
                if (triggeredScenario?.notificationEnabled == true) {
                    ScenarioNotifier.notify(
                        appContext,
                        triggeredScenario!!,
                        triggeredRawValue ?: value
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("MQTT_RUNTIME", "Background status processing failed", e)
            } finally {
                currentTriggeredScenario = null
            }

            // Only UI rendering is marshalled to the main thread.
            mainHandler.post {
                uiListeners.forEach { it.onStatus(deviceId, widgetId, value) }
            }
        },
        onConfig = { deviceId, widgetId, label, widgetType, page, topic, order, raw ->
            uiListeners.forEach {
                it.onConfig(
                    deviceId,
                    widgetId,
                    label,
                    widgetType,
                    page,
                    topic,
                    order,
                    raw
                )
            }
        }
    )

    init {
        scenarioActionExecutor.mqtt = mqtt

        // Restore the last known values once, so multi-condition scenarios can
        // continue working immediately after process/service recreation.
        historyStore.latestValues().forEach { (key, value) ->
            val parts = key.split("/", limit = 2)
            if (parts.size == 2) {
                scenarioEngine.restoreValue(parts[0], parts[1], value)
            }
        }
    }

    fun addUiListener(listener: UiListener) {
        uiListeners.add(listener)
        listener.onConnected(mqtt.isConnected())
    }

    fun removeUiListener(listener: UiListener) {
        uiListeners.remove(listener)
    }

    fun ensureConnected() {
        if (mqtt.isConnected() || mqtt.isConnecting()) return

        val host = prefs.getString("mqtt_host", "m4.wqtt.ru") ?: "m4.wqtt.ru"
        val port = prefs.getString("mqtt_port", "1883")?.toIntOrNull() ?: 1883
        val tls = prefs.getBoolean("mqtt_tls", false)
        val prefix = prefs.getString("mqtt_prefix", "IoTManager") ?: "IoTManager"
        val username = prefs.getString("mqtt_user", "") ?: ""
        val password = prefs.getString("mqtt_pass", "") ?: ""

        mqtt.connect(host, port, prefix, username, password, tls)
    }



    fun reconnect() {
        mqtt.disconnect()
        ensureConnected()
    }

    companion object {
        @Volatile
        private var instance: AppRuntime? = null

        fun get(context: Context): AppRuntime {
            return instance ?: synchronized(this) {
                instance ?: AppRuntime(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}

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

    private val currentTriggeredScenario = ThreadLocal<((Scenario, String) -> Unit)?>()

    val scenarioEngine: ScenarioEngine = ScenarioEngine(
        scenarioStore,
        onTrigger = { scenario, rawValue, _ ->
            DiagnosticTrace.step(
                "TRIGGER",
                "scenario=${scenario.id} title=${scenario.title} value=$rawValue"
            )
            DiagnosticTrace.step(
                "ACTION",
                "type=${scenario.actionType} actions=${scenario.actions.size}"
            )
            scenarioActionExecutor.execute(scenario)
            currentTriggeredScenario.get()?.invoke(scenario, rawValue)
        },
        onVerificationResult = { scenario, success, rawValue ->
            DiagnosticTrace.step(
                "VERIFY",
                "result=${if (success) "SUCCESS" else "TIMEOUT/FAILURE"} scenario=${scenario.id} value=$rawValue"
            )
            if (scenario.notificationEnabled) {
                ScenarioNotifier.notifyVerification(
                    appContext,
                    scenario,
                    success,
                    rawValue
                )
                DiagnosticTrace.step(
                    "NOTIFY",
                    "verification notification requested scenario=${scenario.id}"
                )
            } else {
                DiagnosticTrace.step(
                    "NOTIFY",
                    "verification notification skipped: disabled scenario=${scenario.id}"
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
            var triggeredScenario: Scenario? = null
            var triggeredRawValue: String? = null
            val originalEngine = scenarioEngine
            val eventId = DiagnosticTrace.currentEventId()
            currentTriggeredScenario.set { scenario, raw ->
                triggeredScenario = scenario
                triggeredRawValue = raw
            }

            try {
                DiagnosticTrace.stepForEvent(
                    eventId,
                    "SCENARIO",
                    "INPUT device=$deviceId widget=$widgetId value=$value"
                )
                historyStore.add(deviceId, widgetId, value)
                DiagnosticTrace.stepForEvent(
                    eventId,
                    "HISTORY",
                    "stored device=$deviceId widget=$widgetId value=$value"
                )
                if (widgetId == "vbtn90") {
                    DiagnosticTrace.stepForEvent(
                        eventId,
                        "VBTN90",
                        "CALLBACK BEFORE ScenarioEngine device=" + deviceId + " value=" + value
                    )
                }
                originalEngine.onValue(deviceId, widgetId, value)
                if (widgetId == "vbtn90") {
                    DiagnosticTrace.stepForEvent(
                        eventId,
                        "VBTN90",
                        "CALLBACK AFTER ScenarioEngine device=" + deviceId + " value=" + value
                    )
                }

                if (triggeredScenario?.notificationEnabled == true) {
                    ScenarioNotifier.notify(
                        appContext,
                        triggeredScenario!!,
                        triggeredRawValue ?: value
                    )
                    DiagnosticTrace.stepForEvent(
                        eventId,
                        "NOTIFY",
                        "trigger notification requested scenario=${triggeredScenario!!.id}"
                    )
                } else if (triggeredScenario != null) {
                    DiagnosticTrace.stepForEvent(
                        eventId,
                        "NOTIFY",
                        "trigger notification skipped: disabled scenario=${triggeredScenario!!.id}"
                    )
                } else {
                    DiagnosticTrace.stepForEvent(eventId, "SCENARIO", "no trigger for this event")
                }
            } catch (e: Exception) {
                DiagnosticTrace.stepForEvent(
                    eventId,
                    "ERROR",
                    "background status processing failed: " + (e.message ?: e.javaClass.simpleName)
                )
                android.util.Log.e("MQTT_RUNTIME", "Background status processing failed", e)
            } finally {
                currentTriggeredScenario.remove()
            }

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
        DiagnosticTrace.init(appContext)
        DiagnosticTrace.system("AppRuntime initialized")
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

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
    val deviceRepository: DeviceRepository = DeviceRepository()
    val scenarioStore: ScenarioStore = ScenarioStore(prefs)
    val scenarioActionExecutor: ScenarioActionExecutor = ScenarioActionExecutor()

    val scenarioEngine: ScenarioEngine = ScenarioEngine(
        scenarioStore,
        onTrigger = { scenario, rawValue, _ ->
            DiagnosticTrace.step(
                "TRIGGER",
                "scenario=${scenario.id} title=${scenario.title} value=${rawValue}"
            )
            DiagnosticTrace.step(
                "ACTION",
                "type=${scenario.actionType} actions=${scenario.actions.size}"
            )

            runCatching {
                scenarioActionExecutor.execute(scenario)
            }.onFailure {
                DiagnosticTrace.error(
                    "ACTION dispatch failed scenario=${scenario.id} error=${it.message ?: it.javaClass.simpleName}"
                )
            }

            if (scenario.notificationEnabled) {
                runCatching {
                    ScenarioNotifier.notify(
                        appContext,
                        scenario,
                        rawValue
                    )
                    DiagnosticTrace.step(
                        "NOTIFY",
                        "trigger notification requested scenario=${scenario.id}"
                    )
                }.onFailure {
                    DiagnosticTrace.error(
                        "NOTIFY dispatch failed scenario=${scenario.id} error=${it.message ?: it.javaClass.simpleName}"
                    )
                }
            } else {
                DiagnosticTrace.step(
                    "NOTIFY",
                    "trigger notification skipped: disabled scenario=${scenario.id}"
                )
            }
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
            mainHandler.post {
                LiveDataWidgetProvider.updateAll(appContext)
            }
        },
        onStatus = { deviceId, widgetId, value ->
            val originalEngine = scenarioEngine
            val eventId = DiagnosticTrace.currentEventId()

            try {
                deviceRepository.onStatus(deviceId, widgetId, value)

                DiagnosticTrace.stepForEvent(
                    eventId,
                    "SCENARIO",
                    "INPUT device=$deviceId widget=$widgetId value=$value"
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

                // MQTT only updates the current numeric value.
                // HistoryStore samples that value on its own measurement timer,
                // so graph points are independent from MQTT message frequency.
                val historyResult = historyStore.updateLatest(deviceId, widgetId, value)
                if (historyResult.accepted) {
                    DiagnosticTrace.stepForEvent(
                        eventId,
                        "HISTORY",
                        "CURRENT_UPDATED state=" +
                            (if (DiagnosticTrace.isForeground()) "FOREGROUND" else "BACKGROUND") +
                            " device=" + deviceId +
                            " widget=" + widgetId +
                            " value=" + value +
                            " samplePeriodMs=" + historyStore.samplePeriodMs()
                    )
                } else {
                    DiagnosticTrace.stepForEvent(
                        eventId,
                        "HISTORY",
                        "CURRENT_SKIPPED state=" +
                            (if (DiagnosticTrace.isForeground()) "FOREGROUND" else "BACKGROUND") +
                            " device=" + deviceId +
                            " widget=" + widgetId +
                            " value=" + value +
                            " reason=" + historyResult.reason
                    )
                }
            } catch (e: Exception) {
                DiagnosticTrace.stepForEvent(
                    eventId,
                    "ERROR",
                    "background status processing failed: " + (e.message ?: e.javaClass.simpleName)
                )
                android.util.Log.e("MQTT_RUNTIME", "Background status processing failed", e)
            } finally {
                // Scenario actions and notifications are dispatched by onTrigger.
            }

            mainHandler.post {
                LiveDataWidgetProvider.updateAll(appContext)
                uiListeners.forEach { it.onStatus(deviceId, widgetId, value) }
            }
        },
        onConfig = { deviceId, widgetId, label, widgetType, page, topic, order, raw ->
            deviceRepository.onConfig(
                deviceId,
                widgetId,
                label,
                widgetType,
                page,
                topic,
                order,
                raw
            )
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
            mainHandler.post {
                LiveDataWidgetProvider.updateAll(appContext)
            }
        },
        onReconnectRequested = {
            DiagnosticTrace.system("MQTT RECONNECT requested directly by Paho callback")
            ensureConnected()
        }
    )

    init {
        DiagnosticTrace.init(appContext)
        DiagnosticTrace.system("AppRuntime initialized")
        DiagnosticTrace.system("HISTORY startup " + historyStore.diagnostics())
        scenarioActionExecutor.mqtt = mqtt

        // Restore the last known values once, so multi-condition scenarios can
        // continue working immediately after process/service recreation.
        historyStore.latestValuesByWidget().forEach { (widgetId, value) ->
            // Scenario variables are global by widgetId; deviceId is intentionally absent here.
            scenarioEngine.restoreValue("", widgetId, value)
        }
        scenarioEngine.primeFromStoredValues()
    }

    fun addUiListener(listener: UiListener) {
        uiListeners.add(listener)
        listener.onConnected(mqtt.isConnected())
    }

    fun removeUiListener(listener: UiListener) {
        uiListeners.remove(listener)
    }

    @Synchronized
    fun ensureConnected() {
        if (mqtt.isConnected() || mqtt.isConnecting()) return

        val host = prefs.getString("mqtt_host", "") ?: ""
        val port = prefs.getString("mqtt_port", "1883")?.toIntOrNull() ?: 1883
        val tls = prefs.getBoolean("mqtt_tls", false)
        val prefix = prefs.getString("mqtt_prefix", "IoTManager") ?: "IoTManager"
        val username = prefs.getString("mqtt_user", "") ?: ""
        val password = prefs.getString("mqtt_pass", "") ?: ""

        mqtt.connect(host, port, prefix, username, password, tls)
    }



    @Synchronized
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

package com.yasn198020.aicontrol

import com.yasn198020.aicontrol.core.Device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MarfaVoiceService : Service() {

    companion object {
        const val ACTION_VOICE_RESULT = "com.yasn198020.aicontrol.action.MARFA_VOICE_RESULT"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL_ID = "marfa_voice"
        private const val NOTIFICATION_ID = 1202
    }

    private var voiceManager: VoiceCommandManager? = null
    private var mqtt: MqttManager? = null
    private var runtime: AppRuntime? = null
    private var runtimeListener: AppRuntime.UiListener? = null
    private var tts: TextToSpeech? = null
    private lateinit var prefs: android.content.SharedPreferences
    private val commandExecutor = MarfaCommandExecutor.get()
    private val commandEngine by lazy { MarfaIntelligence.get(applicationContext) }
    private lateinit var dialogueCore: MarfaDialogueCore
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())


    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        dialogueCore = MarfaDialogueCore { command, devices ->
            commandEngine.interpret(command, devices)
        }
        dialogueCore.restoreSmartRule(restorePendingSmartRule())

        createNotificationChannel()
        startAsForeground()

        prefs.edit().putBoolean("marfa_voice_active", true).apply()
        MarfaShortcutInstaller.setActive(this, true)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                tts?.setSpeechRate(prefs.getFloat("voice_rate", 0.92f))
                tts?.setPitch(prefs.getFloat("voice_pitch", 1.05f))
            }
        }

        runtime = AppRuntime.get(applicationContext)
        mqtt = runtime!!.mqtt

        runtimeListener = object : AppRuntime.UiListener {
            override fun onLog(message: String) {
                android.util.Log.d("MARFA_MQTT", message)
            }

            override fun onConnected(connected: Boolean) {
                android.util.Log.d("MARFA_MQTT", "connected=$connected")
            }

            override fun onStatus(deviceId: String, widgetId: String, value: String) {
                // Device state is maintained centrally by AppRuntime.deviceRepository.
            }

            override fun onConfig(
                deviceId: String,
                widgetId: String,
                label: String,
                widgetType: String,
                page: String,
                topic: String,
                order: Int,
                raw: String
            ) {
                // Device state is maintained centrally by AppRuntime.deviceRepository.
            }
        }

        runtime!!.addUiListener(runtimeListener!!)
        runtime!!.ensureConnected()


        voiceManager = VoiceCommandManager(
            context = this,
            onResult = { text -> handleCommand(text) },
            onStatus = { status -> android.util.Log.d("MARFA_VOICE", status) }
        )

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Microphone activation is explicit: only an intent carrying
        // start_listening=true (sent by the microphone shortcut button)
        // may start recognition.
        if (intent?.getBooleanExtra("start_listening", false) == true) {
            voiceManager?.startWakeWord()
        }
        // The microphone shortcut is explicitly user-controlled.
        // Do not let Android resurrect the service after it was stopped or crashed,
        // otherwise the shortcut can remain visually stuck in the ON state.
        return START_NOT_STICKY
    }

    private fun handleCommand(text: String) {
        val command = text.trim()
        if (command.isBlank()) return
        serviceScope.launch {
            handleCommandInternal(command)
        }
    }

    private suspend fun handleCommandInternal(command: String) {
        val devicesSnapshot = synchronizedCopyDevices()

        if (commandEngine.isLikelyContextual(command)) {
            speak(acknowledgementFor(command))
        }

        val turn = try {
            dialogueCore.process(command, devicesSnapshot)
        } catch (e: Throwable) {
            android.util.Log.e("MARFA_ENGINE", "dialogue failed", e)
            speak("Ошибка анализа: " + (e.message ?: "неизвестная ошибка"))
            return
        }

        val result = turn.result
        android.util.Log.d(
            "MARFA_DIALOGUE",
            "command=${command} kind=${turn.kind} action=${result?.action ?: "none"} delayMs=${result?.delayMs ?: 0L} actions=${result?.actionItems?.size ?: 0}"
        )

        when (turn.kind) {
            MarfaDialogueCore.OutcomeKind.CONTINUE -> {
                if (result?.action == LocalCommandAction.SMART_RULE) {
                    persistPendingSmartRule(result)
                }
                speak(turn.reply)
            }

            MarfaDialogueCore.OutcomeKind.CANCEL -> {
                if (result?.action == LocalCommandAction.SMART_RULE) {
                    clearPendingSmartRulePersistence()
                }
                speak(turn.reply)
            }

            MarfaDialogueCore.OutcomeKind.SAVE_SMART_RULE -> {
                val rule = result
                if (rule == null) {
                    speak("Не удалось сохранить правило")
                } else {
                    clearPendingSmartRulePersistence()
                    saveSmartRule(rule)
                }
            }

            MarfaDialogueCore.OutcomeKind.EXECUTE_CONTROL -> {
                val control = result
                if (control == null) {
                    speak("Не удалось определить действие")
                } else {
                    commandExecutor.execute(control, runtime ?: return) { reply ->
                        mainHandler.post { speak(reply) }
                    }
                }
            }

            MarfaDialogueCore.OutcomeKind.ANSWER -> {
                if (result != null &&
                    result.action == LocalCommandAction.NOT_FOUND &&
                    result.reply.isBlank()
                ) {
                    val trained = TrainedCommandMatcher(
                        TrainedCommandStore(prefs)
                    ).matchAll(command)

                    android.util.Log.d(
                        "MARFA_TRAINED",
                        "priority command=${command} matches=${trained.size}"
                    )

                    if (trained.isNotEmpty()) {
                        val readActions = trained.filter { it.value == TRAINED_READ_VALUE }
                        if (readActions.isNotEmpty()) {
                            var answered = false
                            readActions.forEach { action ->
                                val widget = synchronizedCopyDevices()
                                    .firstOrNull { it.id == action.deviceId }
                                    ?.widgets
                                    ?.firstOrNull { it.id == action.widgetId }

                                if (widget != null) {
                                    val raw = widget.value.trim()
                                    val spoken = if (raw.isBlank() || raw == "—") {
                                        widget.title + ": значение пока неизвестно"
                                    } else {
                                        widget.title + ": " +
                                            formatTemperatureForSpeech(raw, widget.unit)
                                    }
                                    speak(spoken)
                                    answered = true
                                }
                            }
                            if (!answered) {
                                speak("Сохранённая команда найдена, но значение датчика пока не получено")
                            }
                        } else {
                            var sent = 0
                            trained.forEach { action ->
                                val widget = synchronizedCopyDevices()
                                    .firstOrNull { it.id == action.deviceId }
                                    ?.widgets
                                    ?.firstOrNull { it.id == action.widgetId }
                                val ok = when (widget?.type) {
                                    com.yasn198020.aicontrol.core.WidgetState.Type.TOGGLE,
                                    com.yasn198020.aicontrol.core.WidgetState.Type.BUTTON ->
                                        mqtt?.publishControl(action.deviceId, action.widgetId, action.value) == true
                                    com.yasn198020.aicontrol.core.WidgetState.Type.INPUT ->
                                        widget.topic.isNotBlank() &&
                                            mqtt?.publishWidget(widget.topic, action.value) == true
                                    else -> false
                                }
                                if (ok) {
                                    runtime?.deviceRepository?.setLocalValue(
                                        action.deviceId,
                                        action.widgetId,
                                        action.value
                                    )
                                    sent++
                                }
                            }

                            if (sent > 0) {
                                speak(if (sent == 1) "Готово" else "Выполнено")
                            } else {
                                speak(
                                    if (mqtt?.isConnected() == true)
                                        "Не удалось отправить команду"
                                    else
                                        "MQTT ещё не подключён"
                                )
                            }
                        }
                    } else {
                        speak("Не поняла команду")
                    }
                } else {
                    result?.reply?.takeIf { it.isNotBlank() }?.let { speak(it) }
                }
            }
        }

        sendBroadcast(
            Intent(ACTION_VOICE_RESULT)
                .setPackage(packageName)
                .putExtra(EXTRA_TEXT, command)
        )

        if (turn.kind != MarfaDialogueCore.OutcomeKind.CONTINUE) {
            voiceManager?.stop()
            prefs.edit().putBoolean("marfa_voice_active", false).apply()
            MarfaShortcutInstaller.setActive(this, false)
        }
    }

    private fun acknowledgementFor(command: String): String {
        val n = command.lowercase(Locale("ru", "RU")).trim()
        return when {
            n.contains("где ") || n.contains(" там") || n.contains("у ") || n.contains("для ") ->
                "Поняла. Уточняю, какой объект вы имеете в виду."
            else -> "Поняла. Уточняю команду."
        }
    }

    private fun restorePendingSmartRule(): LocalCommandResult? {
        if (!prefs.getBoolean("marfa_pending_rule", false)) return null
        val threshold = prefs.getString("marfa_rule_threshold", null)?.toDoubleOrNull() ?: return null
        return LocalCommandResult(
            action = LocalCommandAction.SMART_RULE,
            reply = prefs.getString("marfa_rule_reply", "Поняла правило") ?: "Поняла правило",
            conditionDeviceId = prefs.getString("marfa_rule_condition_device", "") ?: "",
            conditionWidgetId = prefs.getString("marfa_rule_condition_widget", "") ?: "",
            conditionOperator = prefs.getString("marfa_rule_operator", ">") ?: ">",
            conditionThreshold = threshold,
            actionDeviceId = prefs.getString("marfa_rule_action_device", "") ?: "",
            actionWidgetId = prefs.getString("marfa_rule_action_widget", "") ?: "",
            actionValue = prefs.getString("marfa_rule_action_value", "1") ?: "1"
        )
    }

    private fun persistPendingSmartRule(result: LocalCommandResult) {
        prefs.edit()
            .putBoolean("marfa_pending_rule", true)
            .putString("marfa_rule_condition_device", result.conditionDeviceId)
            .putString("marfa_rule_condition_widget", result.conditionWidgetId)
            .putString("marfa_rule_operator", result.conditionOperator)
            .putString("marfa_rule_threshold", result.conditionThreshold.toString())
            .putString("marfa_rule_action_device", result.actionDeviceId)
            .putString("marfa_rule_action_widget", result.actionWidgetId)
            .putString("marfa_rule_action_value", result.actionValue)
            .putString("marfa_rule_reply", result.reply)
            .apply()
    }

    private fun clearPendingSmartRulePersistence() {
        prefs.edit()
            .remove("marfa_pending_rule")
            .remove("marfa_rule_condition_device")
            .remove("marfa_rule_condition_widget")
            .remove("marfa_rule_operator")
            .remove("marfa_rule_threshold")
            .remove("marfa_rule_action_device")
            .remove("marfa_rule_action_widget")
            .remove("marfa_rule_action_value")
            .remove("marfa_rule_reply")
            .apply()
    }

    private fun saveSmartRule(result: LocalCommandResult) {
        if (result.conditionWidgetId.isBlank() || result.actionWidgetId.isBlank()) {
            speak("Не удалось определить условие или действие")
            return
        }

        val scenario = Scenario(
            title = "Марфа: " + result.reply.removePrefix("Поняла правило: "),
            deviceId = result.conditionDeviceId,
            widgetId = result.conditionWidgetId,
            operator = result.conditionOperator,
            threshold = result.conditionThreshold,
            message = result.reply,
            enabled = true,
            armed = true,
            actionType = "MQTT_CONTROL",
            actionDeviceId = result.actionDeviceId,
            actionWidgetId = result.actionWidgetId,
            actionValue = result.actionValue,
            actions = listOf(ScenarioAction(result.actionDeviceId, result.actionWidgetId, result.actionValue)),
            notificationEnabled = true,
            conditions = listOf(ScenarioCondition(result.conditionDeviceId, result.conditionWidgetId, result.conditionOperator, result.conditionThreshold))
        )
        runtime?.scenarioStore?.add(scenario)
        speak(result.reply + ". Правило сохранено.")
        android.util.Log.d("MARFA_AUTOMATION", "saved scenario=" + scenario.id + " condition=" + scenario.widgetId + " " + scenario.operator + " " + scenario.threshold + " action=" + scenario.actionWidgetId + "=" + scenario.actionValue)
    }
    private fun synchronizedCopyDevices(): List<Device> =
        runtime?.deviceRepository?.snapshot() ?: emptyList()

    private fun formatTemperatureForSpeech(raw: String, unit: String = ""): String {
        val normalized = raw.trim().replace(',', '.')
        val number = normalized.toBigDecimalOrNull() ?: return raw
        val value = number.stripTrailingZeros().toPlainString().replace('.', ',')
        val whole = number.abs().toInt()
        val degreeWord = if (number.remainder(java.math.BigDecimal.ONE) == java.math.BigDecimal.ZERO) {
            when {
                whole % 100 in 11..14 -> "градусов"
                whole % 10 == 1 -> "градус"
                whole % 10 in 2..4 -> "градуса"
                else -> "градусов"
            }
        } else "градуса"
        return if (unit.isBlank() || unit.equals("°C", true) || unit.equals("c", true) || unit.equals("с", true)) {
            "$value $degreeWord"
        } else {
            "$value $unit"
        }
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        // Queue multiple answers so all matching saved commands are spoken.
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "marfa-command-" + System.nanoTime())
    }

    override fun onDestroy() {
        serviceScope.cancel()
        voiceManager?.stop()
        voiceManager = null
        runtimeListener?.let { listener ->
            runtime?.removeUiListener(listener)
        }
        runtimeListener = null
        mqtt = null
        runtime = null
        mainHandler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        tts = null

        prefs.edit().putBoolean("marfa_voice_active", false).apply()
        MarfaShortcutInstaller.setActive(this, false)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Марфа",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Голосовая активация Марфы"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startAsForeground() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle("🎙 Марфа")
            .setContentText("Марфа слушает команды")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

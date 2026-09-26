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
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import java.util.Locale

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

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

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

        // Commands recognized by the in-app microphone use the exact same
        // command execution path as the launcher shortcut.
        intent?.getStringExtra("voice_command")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { command ->
                handleCommand(command)
            }

        // The microphone shortcut is explicitly user-controlled.
        // Do not let Android resurrect the service after it was stopped or crashed,
        // otherwise the shortcut can remain visually stuck in the ON state.
        return START_NOT_STICKY
    }

    private fun handleCommand(text: String) {
        val command = text.trim()
        if (command.isBlank()) return

        try {
            val schedule = VoiceScheduleParser.parse(command)
            val effectiveCommand = schedule?.commandText ?: command

            // A command containing a time marker must NEVER fall through to
            // immediate execution. If parsing failed, block immediate MQTT execution.
            if (schedule == null && VoiceScheduleParser.hasScheduleIntent(command)) {
                DiagnosticTrace.error("SCHEDULE parse failed, immediate execution blocked: " + command)
                speak("Я поняла команду, но не смогла определить время выполнения")
                return
            }

            val trained = TrainedCommandMatcher(TrainedCommandStore(prefs)).matchAll(effectiveCommand)
        android.util.Log.d("MARFA_TRAINED", "command=" + command + " matches=" + trained.size)

        // Saved training has absolute priority. A trained read action answers
        // from the exact widget selected during training.
        val readActions = trained.filter { it.value == TRAINED_READ_VALUE }
        if (readActions.isNotEmpty()) {
            if (schedule != null) {
                speak("Чтение по времени пока не поддерживается. Можно запланировать действие.")
                return
            }

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
                        widget.title + ": " + formatTemperatureForSpeech(raw, widget.unit)
                    }
                    android.util.Log.d(
                        "MARFA_TRAINED",
                        "read phrase=" + command + " device=" + action.deviceId +
                            " widget=" + action.widgetId + " value=" + raw
                    )
                    speak(spoken)
                    answered = true
                }
            }

            if (!answered) {
                android.util.Log.w("MARFA_TRAINED", "trained read matched but widget is not loaded")
                speak("Сохранённая команда найдена, но значение датчика пока не получено")
            }
            return
        }

        if (trained.isNotEmpty()) {
            if (schedule != null) {
                var scheduled = 0
                trained.forEach { action ->
                    runCatching {
                        ScheduledCommandScheduler.schedule(
                            context = applicationContext,
                            deviceId = action.deviceId,
                            widgetId = action.widgetId,
                            value = action.value,
                            title = "Команда Марфы",
                            sourceText = command,
                            executeAtMillis = schedule.executeAtMillis
                        )
                        scheduled++
                    }.onFailure {
                        DiagnosticTrace.error("SCHEDULE create failed: " + (it.message ?: it.javaClass.simpleName))
                    }
                }

                if (scheduled > 0) {
                    speak("Запланировано " + schedule.spokenTime)
                    return
                }

                speak("Не удалось создать запланированную команду")
                return
            }

            var sent = 0
            trained.forEach { action ->
                if (mqtt?.publishControl(action.deviceId, action.widgetId, action.value) == true) {
                    sent++
                }
            }

            if (sent > 0) {
                speak(if (sent == 1) "Готово" else "Выполнено")
                return
            }

            if (mqtt?.isConnected() != true) {
                speak("MQTT ещё не подключён")
                return
            }
        }

        val result = LocalCommandManager().interpret(command, synchronizedCopyDevices())
        when (result.action) {
            LocalCommandAction.CONTROL -> {
                if (schedule != null) {
                    val scheduled = runCatching {
                        ScheduledCommandScheduler.schedule(
                            context = applicationContext,
                            deviceId = result.deviceId,
                            widgetId = result.widgetId,
                            value = result.value,
                            title = result.reply,
                            sourceText = command,
                            executeAtMillis = schedule.executeAtMillis
                        )
                    }.getOrNull()

                    if (scheduled != null) {
                        DiagnosticTrace.step(
                            "SCHEDULE",
                            "voice command queued id=" + scheduled.id + " text=" + command
                        )
                        speak("Запланировано " + schedule.spokenTime)
                    } else {
                        speak("Не удалось создать запланированную команду")
                    }
                } else {
                    val ok = mqtt?.publishControl(result.deviceId, result.widgetId, result.value) == true
                    speak(if (ok) result.reply else "Не удалось отправить команду")
                }
            }
            LocalCommandAction.READ_VALUE -> {
                if (schedule != null) {
                    speak("Запланировать чтение пока нельзя. Можно запланировать действие.")
                } else {
                    speak(result.reply)
                }
            }
            LocalCommandAction.CLARIFY,
            LocalCommandAction.NOT_FOUND -> {
                speak(result.reply)
            }
        }

            sendBroadcast(
                Intent(ACTION_VOICE_RESULT)
                    .setPackage(packageName)
                    .putExtra(EXTRA_TEXT, command)
            )
        } finally {
            // The shortcut is a one-command trigger: after executing the
            // command, stop listening and return the shortcut to OFF state.
            voiceManager?.stop()
            prefs.edit().putBoolean("marfa_voice_active", false).apply()
            MarfaShortcutInstaller.setActive(this, false)
        }
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
        voiceManager?.stop()
        voiceManager = null
        runtimeListener?.let { listener ->
            runtime?.removeUiListener(listener)
        }
        runtimeListener = null
        mqtt = null
        runtime = null
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

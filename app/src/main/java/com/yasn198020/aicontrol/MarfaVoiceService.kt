package com.yasn198020.aicontrol

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
    private var tts: TextToSpeech? = null
    private val devices = mutableListOf<Device>()
    private val pendingValues = mutableMapOf<String, String>()
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

        mqtt = MqttManager(
            onLog = { status -> android.util.Log.d("MARFA_MQTT", status) },
            onConnected = { connected ->
                android.util.Log.d("MARFA_MQTT", "connected=$connected")
                if (connected) {
                    mqtt?.publishHello()
                    // Start listening only after MQTT is connected so commands
                    // have a live transport even when the main UI is closed.
                    voiceManager?.startWakeWord()
                }
            },
            onStatus = { deviceId, widgetId, value ->
                synchronized(devices) {
                    val key = "$deviceId/$widgetId"
                    val deviceIndex = devices.indexOfFirst { it.id == deviceId }
                    if (deviceIndex < 0) {
                        pendingValues[key] = value
                    } else {
                        val device = devices[deviceIndex]
                        val widgetIndex = device.widgets.indexOfFirst { it.id == widgetId }
                        if (widgetIndex < 0) {
                            pendingValues[key] = value
                            devices[deviceIndex] = device.copy(online = true)
                        } else {
                            val widgets = device.widgets.map {
                                if (it.id == widgetId) it.copy(value = value) else it
                            }
                            devices[deviceIndex] = device.copy(online = true, widgets = widgets)
                        }
                    }
                }
            },
            onConfig = { deviceId, widgetId, label, widgetType, page, topic, order, raw ->
                val type = when (widgetType.lowercase()) {
                    "toggle" -> WidgetState.Type.TOGGLE
                    "button", "vbtn", "btn" -> WidgetState.Type.BUTTON
                    "input", "text", "number", "slider" -> WidgetState.Type.INPUT
                    "anydata", "anydatavlt", "value" -> WidgetState.Type.VALUE
                    else -> WidgetState.Type.STATUS
                }
                synchronized(devices) {
                    val index = devices.indexOfFirst { it.id == deviceId }
                    val device = if (index >= 0) devices[index] else Device(deviceId, deviceId, true, emptyList())
                    val existing = device.widgets.firstOrNull { it.id == widgetId }
                    val key = "$deviceId/$widgetId"
                    val pendingValue = pendingValues[key]
                    val widget = WidgetState(
                        id = widgetId,
                        title = label.ifBlank { widgetId },
                        type = type,
                        value = pendingValue ?: existing?.value ?: "",
                        page = page.ifBlank { "Основная" },
                        topic = topic,
                        order = order,
                        unit = try { org.json.JSONObject(raw).optString("after").trim() } catch (_: Exception) { "" }
                    )
                    val widgets = if (existing == null) {
                        device.widgets + widget
                    } else {
                        device.widgets.map { if (it.id == widgetId) widget else it }
                    }
                    val updated = device.copy(online = true, widgets = widgets)
                    if (index >= 0) devices[index] = updated else devices.add(updated)
                    if (pendingValue != null) pendingValues.remove(key)
                }
            }
        )

        voiceManager = VoiceCommandManager(
            context = this,
            onResult = { text -> handleCommand(text) },
            onStatus = { status -> android.util.Log.d("MARFA_VOICE", status) }
        )

        val host = prefs.getString("mqtt_host", "m4.wqtt.ru") ?: "m4.wqtt.ru"
        val port = prefs.getString("mqtt_port", "1883")?.toIntOrNull() ?: 1883
        val prefix = prefs.getString("mqtt_prefix", "IoTManager") ?: "IoTManager"
        val username = prefs.getString("mqtt_user", "") ?: ""
        val password = prefs.getString("mqtt_pass", "") ?: ""
        val tls = prefs.getBoolean("mqtt_tls", false)

        try {
            mqtt?.connect(host, port, prefix, username, password, tls)
        } catch (e: Exception) {
            android.util.Log.e("MARFA_MQTT", "connect failed", e)
            voiceManager?.startWakeWord()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        voiceManager?.startWakeWord()
        return START_STICKY
    }

    private fun handleCommand(text: String) {
        val command = text.trim()
        if (command.isBlank()) return

        val trained = TrainedCommandMatcher(TrainedCommandStore(prefs)).matchAll(command)
        android.util.Log.d("MARFA_TRAINED", "command=" + command + " matches=" + trained.size)

        if (trained.isNotEmpty()) {
            var sent = 0
            var read = 0

            trained.forEach { action ->
                if (action.value == TRAINED_READ_VALUE) {
                    val widget = synchronizedCopyDevices()
                        .firstOrNull { it.id == action.deviceId }
                        ?.widgets
                        ?.firstOrNull { it.id == action.widgetId }

                    if (widget != null && widget.value.isNotBlank() && widget.value != "—") {
                        speak(formatTemperatureForSpeech(widget.value))
                    } else {
                        speak("Значение пока неизвестно")
                    }
                    read++
                    return@forEach
                }

                if (mqtt?.publishControl(action.deviceId, action.widgetId, action.value) == true) {
                    sent++
                }
            }

            if (sent > 0) {
                speak(if (sent == 1) "Готово" else "Выполнено")
                return
            }

            if (read > 0) return

            if (mqtt?.isConnected() != true) {
                speak("MQTT ещё не подключён")
                return
            }
        }

        val result = LocalCommandManager().interpret(command, synchronizedCopyDevices())
        when (result.action) {
            LocalCommandAction.CONTROL -> {
                val ok = mqtt?.publishControl(result.deviceId, result.widgetId, result.value) == true
                speak(if (ok) result.reply else "Не удалось отправить команду")
            }
            LocalCommandAction.READ_VALUE -> {
                speak(result.reply)
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
    }

    private fun synchronizedCopyDevices(): List<Device> =
        synchronized(devices) { devices.toList() }

    private fun formatTemperatureForSpeech(raw: String): String {
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
        return "$value $degreeWord"
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "marfa-command")
    }

    override fun onDestroy() {
        voiceManager?.stop()
        voiceManager = null
        mqtt?.disconnect()
        mqtt = null
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

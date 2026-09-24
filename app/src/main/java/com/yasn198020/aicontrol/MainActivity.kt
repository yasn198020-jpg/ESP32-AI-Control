package com.yasn198020.aicontrol

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.os.Bundle
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState
import com.yasn198020.aicontrol.devices.DeviceManager

data class TrainingTarget(val deviceId: String, val widgetId: String, val title: String)

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_WIDGET_VOICE = "com.yasn198020.aicontrol.action.WIDGET_VOICE"
    }

    override fun onStart() {
        super.onStart()
        DiagnosticTrace.setForeground(true)
    }

    override fun onStop() {
        DiagnosticTrace.setForeground(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // MQTT is owned by the background service, not by the Activity.
        MqttBackgroundService.start(applicationContext)

        if (intent?.action == "com.yasn198020.aicontrol.action.MARFA_SHORTCUT"
            && intent?.getBooleanExtra("marfa_shortcut_toggle", false) == true) {
            toggleMarfaFromShortcut()
            finish()
            return
        }

        MarfaShortcutInstaller.ensurePinned(this)
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        setContent {
            val baseDensity = LocalDensity.current
            var fontScale by remember {
                mutableFloatStateOf(prefs.getFloat("ui_font_scale", 0.85f).coerceIn(0.70f, 1.10f))
            }
            CompositionLocalProvider(
                LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale)
            ) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface(Modifier.fillMaxSize()) {
                        App(
                            fontScale = fontScale,
                            onFontScaleChange = {
                                val value = it.coerceIn(0.70f, 1.10f)
                                fontScale = value
                                prefs.edit().putFloat("ui_font_scale", value).apply()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == "com.yasn198020.aicontrol.action.MARFA_SHORTCUT"
            && intent.getBooleanExtra("marfa_shortcut_toggle", false)) {
            toggleMarfaFromShortcut()
            return
        }
        if (intent.action == ACTION_WIDGET_VOICE) {
            setIntent(intent)
            recreate()
        }
    }

    private fun toggleMarfaFromShortcut() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val active = prefs.getBoolean("marfa_voice_active", false)

        if (active) {
            stopService(Intent(this, MarfaVoiceService::class.java))
            MarfaShortcutInstaller.setActive(this, false)
        } else {
            try {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, MarfaVoiceService::class.java)
                        .putExtra("start_listening", true)
                )
            } catch (_: Exception) {
            }
            MarfaShortcutInstaller.setActive(this, true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(
    fontScale: Float,
    onFontScaleChange: (Float) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var mqttHost by remember { mutableStateOf(prefs.getString("mqtt_host", "m4.wqtt.ru") ?: "m4.wqtt.ru") }
    var mqttPort by remember { mutableStateOf(prefs.getString("mqtt_port", "1883") ?: "1883") }
    var mqttTls by remember { mutableStateOf(prefs.getBoolean("mqtt_tls", false)) }
    var mqttPrefix by remember { mutableStateOf(prefs.getString("mqtt_prefix", "IoTManager") ?: "IoTManager") }
    var username by remember { mutableStateOf(prefs.getString("mqtt_user", "") ?: "") }
    var password by remember { mutableStateOf(prefs.getString("mqtt_pass", "") ?: "") }
    var tab by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var textSizeDialogOpen by remember { mutableStateOf(false) }
    var selectedPage by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf(false) }
    var manualMqttDisconnect by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf(listOf("MQTT diagnostic log ready")) }
    var devices by remember { mutableStateOf(emptyList<Device>()) }
    var voiceText by remember { mutableStateOf("") }
    var voiceStatus by remember { mutableStateOf("Нажмите 🎤 и скажите команду") }
    val localCommandManager = remember { LocalCommandManager() }
    val speech = remember { TextToSpeech(context, null) }
    val trainedStore = remember { TrainedCommandStore(prefs) }
    val trainedMatcher = remember { TrainedCommandMatcher(trainedStore) }
    var trainedCommands by remember { mutableStateOf(trainedStore.load()) }
    var trainingTarget by remember { mutableStateOf<TrainingTarget?>(null) }
    var trainingValue by remember { mutableStateOf("1") }
    var trainingPhrase by remember { mutableStateOf("") }
    var attachToExisting by remember { mutableStateOf(false) }
    var selectedExistingPhrase by remember { mutableStateOf<String?>(null) }
    var variantPhraseTarget by remember { mutableStateOf<String?>(null) }
    var variantPhraseText by remember { mutableStateOf("") }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var latestReleaseUrl by remember { mutableStateOf<String?>(null) }
    var latestApkUrl by remember { mutableStateOf<String?>(null) }
    var updateDialogOpen by remember { mutableStateOf(false) }
    var updateDownloading by remember { mutableStateOf(false) }
    var voicePreset by remember { mutableStateOf(prefs.getString("voice_preset", "friendly") ?: "friendly") }
    var voiceRate by remember { mutableFloatStateOf(prefs.getFloat("voice_rate", 0.92f)) }
    var voicePitch by remember { mutableFloatStateOf(prefs.getFloat("voice_pitch", 1.05f)) }
    var selectedVoiceName by remember { mutableStateOf(prefs.getString("tts_voice", "") ?: "") }
    var availableVoices by remember { mutableStateOf(emptyList<android.speech.tts.Voice>()) }

    fun applyVoiceSettings() {
        try {
            speech.language = Locale("ru", "RU")
            if (selectedVoiceName.isNotBlank()) {
                speech.voices.firstOrNull { it.name == selectedVoiceName }?.let { speech.voice = it }
            }
            speech.setSpeechRate(voiceRate)
            speech.setPitch(voicePitch)
        } catch (_: Exception) {
        }
    }

    fun selectInstalledVoice(name: String) {
        selectedVoiceName = name
        prefs.edit().putString("tts_voice", name).apply()
        applyVoiceSettings()
    }

    LaunchedEffect(speech) {
        repeat(20) {
            try {
                val voices = speech.voices
                if (voices.isNotEmpty()) {
                    availableVoices = voices.sortedWith(
                        compareBy<android.speech.tts.Voice> { it.locale.language != "ru" }
                            .thenBy { it.locale.displayName }
                            .thenBy { it.name }
                    )
                    applyVoiceSettings()
                    return@LaunchedEffect
                }
            } catch (_: Exception) {
            }
            kotlinx.coroutines.delay(250)
        }
    }

    LaunchedEffect(speech, voiceRate, voicePitch, selectedVoiceName) {
        applyVoiceSettings()
    }

    fun selectVoicePreset(id: String) {
        voicePreset = id
        when (id) {
            "soft" -> { voiceRate = 0.88f; voicePitch = 1.12f }
            "friendly" -> { voiceRate = 0.92f; voicePitch = 1.05f }
            "natural" -> { voiceRate = 0.98f; voicePitch = 1.00f }
            "assistant" -> { voiceRate = 0.94f; voicePitch = 0.96f }
        }
        prefs.edit().putString("voice_preset", voicePreset)
            .putFloat("voice_rate", voiceRate).putFloat("voice_pitch", voicePitch).apply()
        applyVoiceSettings()
    }

    fun saveVoiceSettings() {
        prefs.edit().putString("voice_preset", voicePreset)
            .putFloat("voice_rate", voiceRate).putFloat("voice_pitch", voicePitch).apply()
        applyVoiceSettings()
        voiceStatus = "Настройки голоса сохранены"
    }

    fun openTraining(deviceId: String, widget: WidgetState) {
        val canTrain = widget.type == WidgetState.Type.TOGGLE ||
            widget.type == WidgetState.Type.BUTTON ||
            widget.type == WidgetState.Type.VALUE ||
            widget.type == WidgetState.Type.STATUS
        if (!canTrain) return
        trainingTarget = TrainingTarget(deviceId, widget.id, widget.title)
        trainingValue = if (widget.type == WidgetState.Type.VALUE || widget.type == WidgetState.Type.STATUS) {
            TRAINED_READ_VALUE
        } else {
            "1"
        }
        trainingPhrase = ""
        attachToExisting = false
        selectedExistingPhrase = null
    }

    fun saveTraining(phrase: String) {
        val target = trainingTarget ?: return
        val clean = phrase.trim()
        val finalPhrase = if (attachToExisting) selectedExistingPhrase?.trim().orEmpty() else clean
        if (finalPhrase.isBlank()) {
            voiceStatus = if (attachToExisting) "Выберите существующую команду" else "Фраза не распознана"
            return
        }
        trainedStore.add(TrainedVoiceCommand(finalPhrase, target.deviceId, target.widgetId, trainingValue))
        trainedCommands = trainedStore.load()
        voiceStatus = "Действие добавлено к команде: $finalPhrase"
        trainingPhrase = finalPhrase
        trainingTarget = null
        attachToExisting = false
        selectedExistingPhrase = null
    }

    fun addLog(message: String) { log = (log + message).takeLast(100) }

    val requestMicPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        voiceStatus = if (granted) {
            "Микрофон готов — удерживайте кнопку"
        } else {
            "Нужно разрешение на микрофон"
        }
    }

    var pendingNotificationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val requestNotificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingNotificationAction?.invoke()
        pendingNotificationAction = null
    }

    val voiceManager = remember {
        VoiceCommandManager(
            context = context,
            onResult = { text ->
                voiceText = text
                if (trainingTarget != null) {
                    trainingPhrase = text
                    voiceStatus = "Фраза распознана: $text"
                } else {
                    voiceStatus = "Команда распознана"
                }
            },
            onStatus = { status -> voiceStatus = status }
        )
    }

    // One process-wide runtime owns MQTT, history and scenario execution.
    // The Activity only attaches UI listeners; it never creates a second MQTT client.
    val runtime = remember { AppRuntime.get(context.applicationContext) }
    val mqtt = runtime.mqtt
    val historyStore = runtime.historyStore
    val scenarioStore = runtime.scenarioStore
    val scenarioEngine = runtime.scenarioEngine
    val scenarioActionExecutor = runtime.scenarioActionExecutor

    val deviceManager = remember {
        DeviceManager(
            onDevicesChanged = { devices = it },
            onPendingValueStored = { key, value ->
                addLog("MQTT state stored until CONFIG: " + key + " = " + value)
            }
        )
    }

    DisposableEffect(runtime, deviceManager) {
        val listener = object : AppRuntime.UiListener {
            override fun onLog(message: String) {
                addLog(message)
            }

            override fun onConnected(value: Boolean) {
                connected = value
            }

            override fun onStatus(deviceId: String, widgetId: String, value: String) {
                deviceManager.onStatus(deviceId, widgetId, value)
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
                deviceManager.onConfig(
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
        runtime.addUiListener(listener)
        onDispose {
            runtime.removeUiListener(listener)
        }
    }

    DisposableEffect(voiceManager, speech) {
        onDispose {
            voiceManager.stop()
            speech.stop()
            speech.shutdown()
        }
    }

    fun saveSettings() {
        prefs.edit()
            .putString("mqtt_host", mqttHost)
            .putString("mqtt_port", mqttPort)
            .putBoolean("mqtt_tls", mqttTls)
            .putString("mqtt_prefix", mqttPrefix)
            .putString("mqtt_user", username)
            .putString("mqtt_pass", password)
            .apply()
        addLog("Settings saved")
    }


    fun connect(save: Boolean = true) {
        manualMqttDisconnect = false
        if (save) saveSettings()
        runtime.reconnect()
        MqttBackgroundService.start(context)
    }

    // Safety: always release the microphone when the screen leaves the foreground.
    DisposableEffect(context, voiceManager) {
        val lifecycle = (context as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                voiceManager.stop()
                voiceStatus = "Микрофон выключен"
            }
        }
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
            voiceManager.stop()
        }
    }


    fun sendWidget(deviceId: String, widgetId: String, value: String): Boolean {
        val widget = devices.firstOrNull { it.id == deviceId }?.widgets?.firstOrNull { it.id == widgetId }
        if (widget == null) {
            addLog("MQTT TX skipped: widget not found: " + deviceId + "/" + widgetId)
            return false
        }

        // Control commands use the standard device/widget/control topic.
        // They do not require the CONFIG message to contain a separate topic.
        val published = when (widget.type) {
            WidgetState.Type.TOGGLE, WidgetState.Type.BUTTON ->
                mqtt.publishControl(deviceId, widgetId, value)
            else -> {
                if (widget.topic.isBlank()) {
                    addLog("MQTT TX skipped: config has no topic for " + widgetId)
                    false
                } else {
                    mqtt.publishWidget(widget.topic, value)
                }
            }
        }

        if (published) {
            devices = devices.map { device ->
                if (device.id != deviceId) device else device.copy(
                    widgets = device.widgets.map { w -> if (w.id == widgetId) w.copy(value = value) else w }
                )
            }
        }
        return published
    }

    LaunchedEffect(voiceText) {
        val command = voiceText.trim()
        if (command.isNotBlank()) {
            if (variantPhraseTarget != null) {
                variantPhraseText = command
                voiceStatus = "Вариант распознан — нажмите «Добавить»"
            } else if (trainingTarget != null) {
                saveTraining(command)
            } else {
                voiceStatus = "Анализ команды…"
                val trainedActions = trainedMatcher.matchAll(command)
                if (trainedActions.isNotEmpty()) {
                    var sent = 0
                    var skipped = 0
                    trainedActions.forEach { trained ->
                        val device = devices.firstOrNull { it.id == trained.deviceId }
                        val widget = device?.widgets?.firstOrNull { it.id == trained.widgetId }
                        if (device == null || widget == null) {
                            skipped++
                        } else if (trained.value == TRAINED_READ_VALUE) {
                            if (widget.type == WidgetState.Type.VALUE || widget.type == WidgetState.Type.STATUS) {
                                val raw = widget.value.trim()
                                val unit = widget.unit.trim()
                                val spoken = if (raw.isBlank() || raw == "—") {
                                    "${widget.title}: значение пока неизвестно"
                                } else {
                                    "${widget.title}: ${formatTemperatureForSpeech(raw, unit)}"
                                }
                                voiceStatus = spoken
                                speech.speak(spoken, if (sent == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "trained-value-$sent")
                                sent++
                            } else {
                                skipped++
                            }
                        } else if (widget.type == WidgetState.Type.TOGGLE || widget.type == WidgetState.Type.BUTTON) {
                            if (sendWidget(trained.deviceId, trained.widgetId, trained.value)) sent++ else skipped++
                        } else {
                            skipped++
                        }
                    }
                    voiceStatus = if (skipped == 0) {
                        "Выполнено действий: $sent"
                    } else {
                        "Выполнено действий: $sent, пропущено: $skipped"
                    }
                } else {
                    val result = localCommandManager.interpret(command, devices)
                    when (result.action) {
                        LocalCommandAction.CONTROL -> {
                            val device = devices.firstOrNull { it.id == result.deviceId }
                            val widget = device?.widgets?.firstOrNull { it.id == result.widgetId }
                            if (device == null || widget == null) {
                                voiceStatus = "Подходящий виджет не найден. Команда не отправлена."
                            } else if (widget.type != WidgetState.Type.TOGGLE && widget.type != WidgetState.Type.BUTTON) {
                                voiceStatus = "Этот виджет нельзя управлять голосовой командой."
                            } else {
                                val published = sendWidget(result.deviceId, result.widgetId, result.value)
                                voiceStatus = if (published) result.reply else "Команда распознана, но MQTT публикация не выполнена."
                            }
                        }
                        LocalCommandAction.READ_VALUE -> {
                            voiceStatus = result.reply
                            speech.speak(result.reply, TextToSpeech.QUEUE_FLUSH, null, "temperature")
                        }
                        LocalCommandAction.CLARIFY -> voiceStatus = result.reply
                        LocalCommandAction.NOT_FOUND -> voiceStatus = result.reply
                    }
                }
            }
        }
    }


    if (updateDialogOpen && updateStatus != null) {
        AlertDialog(
            onDismissRequest = { if (!updateDownloading) updateDialogOpen = false },
            title = { Text("Обновление приложения") },
            text = { Text(updateStatus.orEmpty()) },
            confirmButton = {
                when {
                    updateDownloading -> TextButton(onClick = { }) { Text("Скачивание…") }
                    latestApkUrl != null -> TextButton(onClick = {
                        updateDownloading = true
                        updateStatus = "Скачиваю новую версию…"
                        UpdateManager.downloadAndInstall(context, latestApkUrl!!) { message ->
                            updateDownloading = false
                            updateStatus = message
                        }
                    }) { Text("Обновить") }
                    latestReleaseUrl != null -> TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(latestReleaseUrl)))
                        updateDialogOpen = false
                    }) { Text("Открыть загрузку") }
                    else -> TextButton(onClick = { updateDialogOpen = false }) { Text("OK") }
                }
            },
            dismissButton = {
                if (!updateDownloading) TextButton(onClick = { updateDialogOpen = false }) { Text("Закрыть") }
            }
        )
    }

    variantPhraseTarget?.let { phrase ->
        AlertDialog(
            onDismissRequest = {
                variantPhraseTarget = null
                variantPhraseText = ""
            },
            title = { Text("Добавить вариант фразы") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Команда: «$phrase»")
                    Text("Произнесите или введите другой вариант этой команды.")
                    OutlinedTextField(
                        value = variantPhraseText,
                        onValueChange = { variantPhraseText = it },
                        label = { Text("Новый вариант") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            if (androidx.core.content.ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                voiceManager.startRussian()
                            } else {
                                requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🎤 Произнести вариант")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val variant = variantPhraseText.trim()
                        if (variant.isNotBlank()) {
                            trainedStore.addVariant(phrase, variant)
                            trainedCommands = trainedStore.load()
                            variantPhraseTarget = null
                            variantPhraseText = ""
                            voiceStatus = "Вариант добавлен к команде: $phrase"
                        }
                    },
                    enabled = variantPhraseText.trim().isNotBlank()
                ) { Text("Добавить") }
            },
            dismissButton = {
                TextButton(onClick = {
                    variantPhraseTarget = null
                    variantPhraseText = ""
                }) { Text("Отмена") }
            }
        )
    }

    if (textSizeDialogOpen) {
        AlertDialog(
            onDismissRequest = { textSizeDialogOpen = false },
            title = { Text("Размер текста") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${(fontScale * 100f).toInt()}%", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Slider(value = fontScale, onValueChange = onFontScaleChange, valueRange = 0.70f..1.10f, steps = 7)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Мельче")
                        Text("Обычный")
                        Text("Крупнее")
                    }
                    Text("Настройка применяется ко всему тексту приложения и сохраняется автоматически.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { textSizeDialogOpen = false }) { Text("Готово") } },
            dismissButton = { TextButton(onClick = { onFontScaleChange(0.85f) }) { Text("По умолчанию") } }
        )
    }

    trainingTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { trainingTarget = null },
            title = { Text("Обучить голосовую команду") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Виджет: ${target.title}")
                    Text("Что должна делать фраза?")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !attachToExisting,
                            onClick = { attachToExisting = false; selectedExistingPhrase = null },
                            label = { Text("Новая команда") }
                        )
                        FilterChip(
                            selected = attachToExisting,
                            onClick = { attachToExisting = true },
                            label = { Text("К существующей") }
                        )
                    }
                    if (attachToExisting) {
                        Text("Выберите существующую команду:")
                        val existingPhrases = trainedCommands.map { it.phrase }.distinct()
                        if (existingPhrases.isEmpty()) {
                            Text("Существующих команд пока нет.")
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                existingPhrases.forEach { phrase ->
                                    FilterChip(
                                        selected = selectedExistingPhrase == phrase,
                                        onClick = { selectedExistingPhrase = phrase },
                                        label = { Text("«$phrase»") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            Button(
                                onClick = { saveTraining(selectedExistingPhrase.orEmpty()) },
                                enabled = selectedExistingPhrase != null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("➕ Добавить действие к этой команде")
                            }
                        }
                    }
                    val isReadValueTraining = trainingValue == TRAINED_READ_VALUE
                    if (isReadValueTraining) {
                        Text(
                            "Эта фраза будет читать текущее значение виджета вслух. " +
                                "Например: «Какая температура в помидорах?» → приложение скажет текущее значение этого датчика."
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = trainingValue == "1",
                                onClick = { trainingValue = "1" },
                                label = { Text("Открыть / включить") }
                            )
                            FilterChip(
                                selected = trainingValue == "0",
                                onClick = { trainingValue = "0" },
                                label = { Text("Закрыть / выключить") }
                            )
                        }
                        Text(
                            "Одну и ту же фразу можно записать для нескольких виджетов. " +
                                "Например, для «Доброе утро» обучите свет и шторы отдельно — при произнесении сработают оба действия."
                        )
                    }
                    if (!attachToExisting) {
                        Text("Нажмите микрофон и произнесите фразу.")
                    }
                    if (!attachToExisting) {
                    Button(
                        onClick = {
                            if (androidx.core.content.ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                voiceManager.startRussian()
                            } else {
                                requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🎤 Записать фразу")
                    }
                    }
                    if (trainingPhrase.isNotBlank()) {
                        Text("Распознано: $trainingPhrase")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { trainingTarget = null }) { Text("Готово") }
            }
        )
    }

    Scaffold(containerColor = Color(0xFF202020),
        topBar = {
            Column {
                Row(modifier = Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        Text("☰", fontSize = 30.sp, modifier = Modifier.clickable { menuOpen = true }.padding(end = 18.dp))
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("MQTT подключение") }, onClick = { menuOpen = false; tab = 2 })
                            DropdownMenuItem(text = { Text("Журнал") }, onClick = { menuOpen = false; tab = 3 })
                            DropdownMenuItem(text = { Text("История и графики") }, onClick = { menuOpen = false; tab = 6 })
                            DropdownMenuItem(text = { Text("Сценарии") }, onClick = { menuOpen = false; tab = 4 })
                            DropdownMenuItem(text = { Text("Голос") }, onClick = { menuOpen = false; tab = 5 })
                            DropdownMenuItem(text = { Text("Размер текста") }, onClick = { menuOpen = false; textSizeDialogOpen = true })
                            DropdownMenuItem(
                                text = { Text("Установить ярлык «🎙 Марфа»") },
                                onClick = {
                                    menuOpen = false

                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Для ярлыков нужен Android 8 или новее",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                        return@DropdownMenuItem
                                    }

                                    val installed = MarfaShortcutInstaller.requestPinned(context)

                                    android.widget.Toast.makeText(
                                        context,
                                        if (installed) {
                                            "Запрос на установку отправлен. Подтвердите «Добавить» в окне лаунчера."
                                        } else {
                                            "Лаунчер не принял запрос на установку ярлыка."
                                        },
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Проверить обновление") },
                                onClick = {
                                    menuOpen = false
                                    updateStatus = "Проверяю последнюю версию…"
                                    latestReleaseUrl = null
                                    latestApkUrl = null
                                    updateDownloading = false
                                    updateDialogOpen = true
                                    UpdateManager.checkLatest(BuildConfig.VERSION_NAME) { result ->
                                        updateStatus = result.message
                                        latestReleaseUrl = result.url
                                        latestApkUrl = result.apkUrl
                                    }
                                }
                            )
                        }
                    }
                    Text("?", fontSize = 22.sp, modifier = Modifier.padding(end = 18.dp))
                    Text(when (tab) { 0 -> "Dashboard"; 1 -> "Обученные команды"; 2 -> "MQTT"; 3 -> "Log"; 4 -> "Сценарии"; 5 -> "Голос"; else -> "История и графики" }, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("ⓘ", fontSize = 22.sp, modifier = Modifier.padding(horizontal = 10.dp)); Text("☁", fontSize = 27.sp)
                }
                if (tab == 0) DashboardPageTabs(devices, selectedPage, onSelect = { selectedPage = it })
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF24252A), tonalElevation = 0.dp) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Text("▲", fontSize = 22.sp) },
                    label = { Text("Главная") }
                )

                NavigationBarItem(
                    selected = false,
                    onClick = { },
                    modifier = Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            var pressed = false
                            while (true) {
                                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                event.changes.forEach { change ->
                                    if (change.pressed && !pressed) {
                                        pressed = true
                                        if (ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.RECORD_AUDIO
                                            ) == PackageManager.PERMISSION_GRANTED
                                        ) {
                                            voiceStatus = "🎙 Слушаю… отпустите кнопку для остановки"
                                            voiceManager.startRussian()
                                        } else {
                                            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    } else if (!change.pressed && pressed) {
                                        pressed = false
                                        voiceManager.finishRussian()
                                        voiceStatus = "Микрофон выключен"
                                    }
                                }
                            }
                        }
                    },
                    icon = { Text("🎙", fontSize = 22.sp) },
                    label = { Text("Марфа") }
                )

                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Text("🎤", fontSize = 22.sp) },
                    label = { Text("Команды") }
                )
                
                NavigationBarItem(
                    selected = tab == 4,
                    onClick = { tab = 4 },
                    icon = { Text("🔔", fontSize = 22.sp) },
                    label = { Text("Сценарии") }
                )
            }
        }
    ) { padding ->
        when (tab) {
            0 -> DevicesScreen(Modifier.padding(padding), devices, selectedPage, voiceText, voiceStatus,
                onTrain = ::openTraining,
                onVoiceStart = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        voiceStatus = "🎙 Слушаю… отпустите кнопку для остановки"
                        voiceManager.startRussian()
                    } else {
                        requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onVoiceStop = {
                    voiceManager.finishRussian()
                    voiceStatus = "Микрофон выключен"
                },
                onSend = ::sendWidget)
            1 -> TrainedCommandsScreen(Modifier.padding(padding), trainedCommands, devices, onDelete = { command -> trainedStore.remove(command); trainedCommands = trainedStore.load() }, onClearAll = { trainedStore.clear(); trainedCommands = trainedStore.load() }, onAddVariant = { phrase -> variantPhraseTarget = phrase; variantPhraseText = "" })
            2 -> MqttScreen(
                Modifier.padding(padding),
                mqttHost, mqttPort, mqttPrefix, username, password, mqttTls, connected,

                { mqttHost = it }, { mqttPort = it }, { mqttPrefix = it }, { username = it }, { password = it }, { mqttTls = it },
                ::saveSettings,
                {
                    saveSettings()
                    connect(save = false)
                },
                { mqtt.publishHello() }
            )
            3 -> LogScreen(Modifier.padding(padding), log) { log = emptyList() }
            4 -> ScenariosScreen(
                Modifier.padding(padding),
                devices,
                scenarioStore,
                scenarioEngine,
                onRequestNotifications = { action ->
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingNotificationAction = action
                        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        action()
                    }
                }
            )
            6 -> HistoryScreen(Modifier.padding(padding), devices, historyStore)
            else -> VoiceSettingsScreen(
                Modifier.padding(padding), voicePreset, voiceRate, voicePitch,
                availableVoices, selectedVoiceName,
                ::selectVoicePreset,
                { voiceRate = it; voicePreset = "custom" },
                { voicePitch = it; voicePreset = "custom" },
                ::selectInstalledVoice,
                ::saveVoiceSettings,
                { sample -> applyVoiceSettings(); speech.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "voice-preview") }
            )
        }
    }
}

@Composable
private fun DashboardPageTabs(devices: List<Device>, selectedPage: String?, onSelect: (String) -> Unit) {
    val pages = devices
        .flatMap { it.widgets.map { w -> w.page.ifBlank { "Основная" } } }
        .distinct()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        pages.forEachIndexed { index, page ->
            val selected = selectedPage == page || (selectedPage == null && index == 0)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (selected) Color(0xFF626262) else Color(0xFF303030),
                modifier = Modifier.padding(end = 2.dp)
            ) {
                Text(
                    text = page,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable { onSelect(page) }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun DevicesScreen(
    modifier: Modifier,
    devices: List<Device>,
    selectedPage: String?,
    voiceText: String,
    voiceStatus: String,
    onTrain: (String, WidgetState) -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit,
    onSend: (String, String, String) -> Unit
) {
    val pages = devices
        .flatMap { it.widgets.map { it.page.ifBlank { "Основная" } } }
        .distinct()

    val activePage = selectedPage ?: pages.firstOrNull() ?: "Основная"
    val entries = devices
        .flatMap { device -> device.widgets.map { device.id to it } }
        .filter { (_, widget) -> widget.page.ifBlank { "Основная" } == activePage }
        .sortedWith(compareBy<Pair<String, WidgetState>> { it.second.order }.thenBy { it.second.title })

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (entries.isEmpty()) {
            item {
                Text(
                    "Нет конфигурации. Подключитесь к MQTT и нажмите HELLO.",
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        items(
            items = entries,
            key = { it.first + "/" + it.second.id }
        ) { (deviceId, widget) ->
            DashboardWidgetRow(widget, onSend = { value -> onSend(deviceId, widget.id, value) }, onTrain = { onTrain(deviceId, widget) })
        }

        item(key = "voice-status") {
            if (voiceText.isNotBlank()) {
                Text(
                    "«$voiceText»  •  $voiceStatus",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DashboardWidgetRow(
    widget: WidgetState,
    onSend: (String) -> Unit,
    onTrain: () -> Unit
) {
    when (widget.type) {
        WidgetState.Type.INPUT -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 6.dp)
                    .combinedClickable(onLongClick = onTrain, onClick = { }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "⌨",
                    color = Color(0xFF8065E8),
                    fontSize = 22.sp,
                    modifier = Modifier.width(44.dp)
                )
                InputWidget(
                    widget = widget,
                    onSend = onSend
                )
            }
        }

        WidgetState.Type.VALUE, WidgetState.Type.STATUS -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .padding(horizontal = 22.dp)
                    .combinedClickable(onLongClick = onTrain, onClick = { }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🌡", fontSize = 22.sp, modifier = Modifier.width(34.dp))
                Text(
                    widget.title,
                    fontSize = 19.sp,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    modifier = Modifier
                        .width(86.dp)
                        .height(64.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFF4285F4)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            widget.value.ifBlank { "—" } +
                                if (widget.unit.isNotBlank()) " " + widget.unit else "",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        WidgetState.Type.BUTTON -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(66.dp)
                    .padding(horizontal = 22.dp)
                    .combinedClickable(onLongClick = onTrain, onClick = { }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "◉",
                    color = Color(0xFF8065E8),
                    fontSize = 22.sp,
                    modifier = Modifier.width(44.dp)
                )
                Text(
                    widget.title,
                    fontSize = 19.sp,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { onSend("1") }) {
                    Text("Нажать")
                }
            }
        }

        WidgetState.Type.TOGGLE -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(66.dp)
                    .padding(horizontal = 22.dp)
                    .combinedClickable(onLongClick = onTrain, onClick = { }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "◉",
                    color = Color(0xFF8065E8),
                    fontSize = 22.sp,
                    modifier = Modifier.width(44.dp)
                )
                Text(
                    widget.title,
                    fontSize = 19.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = widget.value == "1" || widget.value.equals("true", true),
                    onCheckedChange = { checked ->
                        onSend(if (checked) "1" else "0")
                    }
                )
            }
        }
    }
}

@Composable
private fun TrainedCommandsScreen(modifier: Modifier, trainedCommands: List<TrainedVoiceCommand>, devices: List<Device>, onDelete: (TrainedVoiceCommand) -> Unit, onClearAll: () -> Unit, onAddVariant: (String) -> Unit) {
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Очистить обученные команды?") },
            text = { Text("Будут удалены все сохранённые фразы и их варианты. Настройки MQTT и другие данные приложения не изменятся.") },
            confirmButton = { TextButton(onClick = { onClearAll(); showClearDialog = false }) { Text("Удалить всё") } },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("Отмена") } }
        )
    }
    if (trainedCommands.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            Text("Пока нет обученных команд.\n\nЗажмите переключатель виджета на главном экране и запишите фразу.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Записанные команды: " + trainedCommands.size,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedButton(onClick = { showClearDialog = true }) { Text("Очистить всё") }
            }
        }
        items(items = trainedCommands, key = { it.phrase + "|" + it.deviceId + "|" + it.widgetId + "|" + it.value }) { command ->
            val device = devices.firstOrNull { it.id == command.deviceId }
            val widget = device?.widgets?.firstOrNull { it.id == command.widgetId }
            val deviceName = device?.name?.ifBlank { device.id } ?: command.deviceId
            val widgetName = widget?.title?.ifBlank { widget.id } ?: command.widgetId
            val action = if (command.value == TRAINED_READ_VALUE) "ПРОИЗНЕСТИ ЗНАЧЕНИЕ" else if (command.value == "1") "ВКЛ / ОТКРЫТЬ" else "ВЫКЛ / ЗАКРЫТЬ"
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("«" + command.phrase + "»", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(5.dp)); Text(deviceName + "  •  " + widgetName); Text(action, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                onAddVariant(command.phrase)
                            }
                        ) {
                            Text("➕ Добавить вариант фразы")
                        }
                    }
                    IconButton(onClick = { onDelete(command) }) { Text("🗑", fontSize = 22.sp) }
                }
            }
        }
    }
}
@Composable
private fun InputWidget(widget: WidgetState, onSend: (String) -> Unit) {
    var value by remember(widget.id, widget.value) { mutableStateOf(widget.value) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value, { value = it }, label = { Text(widget.title) }, modifier = Modifier.weight(1f))
        Button(onClick = { onSend(value) }) { Text("Send") }
    }
}

@Composable
private fun MqttScreen(modifier: Modifier, host: String, port: String, prefix: String, username: String, password: String, tls: Boolean, connected: Boolean,

    onHost: (String) -> Unit, onPort: (String) -> Unit, onPrefix: (String) -> Unit, onUser: (String) -> Unit, onPass: (String) -> Unit,
    onTls: (Boolean) -> Unit, onSave: () -> Unit, onConnect: () -> Unit, onHello: () -> Unit) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(host, onHost, label = { Text("MQTT host / IP") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, onPort, label = { Text("MQTT port") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(prefix, onPrefix, label = { Text("MQTT prefix") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(username, onUser, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, onPass, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("TLS / SSL")
            Switch(checked = tls, onCheckedChange = onTls)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave) { Text("Save") }
            Button(onClick = onConnect) { Text(if (connected) "Disconnect" else "Connect") }
            OutlinedButton(onClick = onHello, enabled = connected) { Text("HELLO") }
        }
        HorizontalDivider()
        Text(if (connected) "●  MQTT: connected" else "○  MQTT: disconnected", fontWeight = FontWeight.SemiBold)
        Text("MQTT: " + host + ":" + port)

        HorizontalDivider()
        Text("Голосовое управление работает локально", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Команда анализируется прямо в APK по реальным виджетам MQTT. OpenAI API и интернет для анализа команды не нужны.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun VoiceSettingsScreen(
    modifier: Modifier,
    preset: String,
    rate: Float,
    pitch: Float,
    voices: List<android.speech.tts.Voice>,
    selectedVoiceName: String,
    onPreset: (String) -> Unit,
    onRate: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onVoice: (String) -> Unit,
    onSave: () -> Unit,
    onPreview: (String) -> Unit
) {
    var voiceMenuOpen by remember { mutableStateOf(false) }

    fun languageLabel(locale: Locale): String {
        val language = locale.getDisplayLanguage(Locale("ru", "RU")).ifBlank { locale.language }
        val country = locale.getDisplayCountry(Locale("ru", "RU"))
        return if (country.isBlank()) language else "$language — $country"
    }

    fun voiceLabel(voice: android.speech.tts.Voice): String = voice.name

    val selectedVoice = voices.firstOrNull { it.name == selectedVoiceName }
    val russianVoices = voices.filter { it.locale.language == "ru" }
    val voicesByLanguage = voices.groupBy { languageLabel(it.locale) }
        .toSortedMap(compareBy { it.lowercase(Locale.ROOT) })

    Column(
        modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Голос", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("Выберите голос из установленных на телефоне.")

        Text("Установленный голос", fontWeight = FontWeight.Medium)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { voiceMenuOpen = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (selectedVoice != null) voiceLabel(selectedVoice)
                    else if (voices.isEmpty()) "Загрузка голосов…"
                    else "Выберите голос"
                )
            }
            DropdownMenu(
                expanded = voiceMenuOpen,
                onDismissRequest = { voiceMenuOpen = false },
                modifier = Modifier.fillMaxWidth(0.92f)
            ) {
                voicesByLanguage.forEach { (language, languageVoices) ->
                    DropdownMenuItem(
                        text = { Text(language, fontWeight = FontWeight.Bold) },
                        onClick = { }
                    )
                    languageVoices.sortedBy { it.name }.forEach { voice ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(voiceLabel(voice), fontWeight = FontWeight.Medium)
                                    Text(
                                        "${voice.locale.toLanguageTag()} • качество ${voice.quality} • задержка ${voice.latency}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            },
                            onClick = {
                                onVoice(voice.name)
                                voiceMenuOpen = false
                            }
                        )
                    }
                    HorizontalDivider()
                }
            }
        }

        if (voices.isNotEmpty()) {
            Text(
                "Доступно: ${voices.size}, русских: ${russianVoices.size}",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Text("Пресет", fontWeight = FontWeight.Medium)
        val presets = listOf(
            Triple("soft", "🌸 Нежный", "Мягкий и спокойный"),
            Triple("friendly", "😊 Дружелюбный", "Тёплый и естественный"),
            Triple("natural", "🎧 Естественный", "Более нейтральный"),
            Triple("assistant", "🤖 Ассистент", "Чёткий и спокойный")
        )
        presets.forEach { (id, title, description) ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onPreset(id) },
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = preset == id, onClick = { onPreset(id) })
                    Column(Modifier.weight(1f)) {
                        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Text(description, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onPreset(id); onPreview("Здравствуйте! Я готова помочь.") }) { Text("▶") }
                }
            }
        }

        Text("Скорость: " + String.format(Locale.US, "%.2f", rate), fontWeight = FontWeight.Medium)
        Slider(value = rate, onValueChange = onRate, valueRange = 0.75f..1.15f)

        Text("Высота голоса: " + String.format(Locale.US, "%.2f", pitch), fontWeight = FontWeight.Medium)
        Slider(value = pitch, onValueChange = onPitch, valueRange = 0.85f..1.25f)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onPreview("Температура для помидоров: ${formatTemperatureForSpeech("24.5", "°C")}") }, modifier = Modifier.weight(1f)) {
                Text("▶ Проверить")
            }
            Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("Сохранить") }
        }
    }
}

@Composable
private fun LogScreen(modifier: Modifier, log: List<String>, onClear: () -> Unit) {
    val context = LocalContext.current
    var trace by remember { mutableStateOf(DiagnosticTrace.read()) }
    var filter by remember { mutableStateOf("ALL") }

    LaunchedEffect(Unit) {
        while (true) {
            trace = DiagnosticTrace.read()
            delay(750)
        }
    }

    val filteredTrace = trace.filter { line ->
        when (filter) {
            "MQTT" -> line.contains(" MQTT]") || line.contains("[MQTT]")
            "SCENARIO" -> line.contains(" SCENARIO]") || line.contains("[SCENARIO]") || line.contains(" CONDITION]") || line.contains(" EDGE]")
            "ACTION" -> line.contains(" ACTION]")
            "VERIFY" -> line.contains(" VERIFY]")
            "ERROR" -> line.contains(" ERROR]")
            else -> true
        }
    }
    val traceText = filteredTrace.joinToString("\n")

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Диагностика фона", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Сохранено: ${trace.size} событий", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    DiagnosticTrace.clear()
                    trace = emptyList()
                }) {
                    Text("Очистить")
                }
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Diagnostic Trace", traceText))
                }) {
                    Text("Копировать")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("ALL" to "Все", "MQTT" to "MQTT", "SCENARIO" to "Сценарии", "ACTION" to "Действия", "VERIFY" to "Проверка", "ERROR" to "Ошибки").forEach { (id, title) ->
                OutlinedButton(
                    onClick = { filter = id },
                    modifier = Modifier
                ) {
                    Text(if (filter == id) "● $title" else title)
                }
            }
        }

        HorizontalDivider()

        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(filteredTrace) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
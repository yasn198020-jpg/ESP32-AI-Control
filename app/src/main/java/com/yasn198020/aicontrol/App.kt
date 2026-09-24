package com.yasn198020.aicontrol

import com.yasn198020.aicontrol.mqtt.*
import com.yasn198020.aicontrol.voice.*
import com.yasn198020.aicontrol.commands.*
import com.yasn198020.aicontrol.scenarios.*
import com.yasn198020.aicontrol.history.*
import com.yasn198020.aicontrol.updates.*
import com.yasn198020.aicontrol.marfa.*
import com.yasn198020.aicontrol.ui.*
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
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
    val ttsController = remember { TtsVoiceController(context, speech) }
    val voiceTraining = remember { VoiceTrainingController(prefs) }
    var trainedCommands by remember { mutableStateOf(voiceTraining.load()) }
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
        ttsController.apply(selectedVoiceName, voiceRate, voicePitch)
    }

    fun selectInstalledVoice(name: String) {
        selectedVoiceName = name
        ttsController.saveSelection(name)
        applyVoiceSettings()
    }
    LaunchedEffect(speech, ttsController) {
        repeat(20) {
            val voices = ttsController.availableVoices()
            if (voices.isNotEmpty()) {
                availableVoices = voices
                applyVoiceSettings()
                return@LaunchedEffect
            }
            delay(250)
        }
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

    val historyStore = remember { HistoryStore(prefs) }
    val scenarioStore = remember { ScenarioStore(prefs) }
    val scenarioActionExecutor = remember { ScenarioActionExecutor() }
    val scenarioEngine = remember {
        ScenarioEngine(
            scenarioStore,
            onTrigger = { scenario, rawValue, _ ->
                if (scenario.notificationEnabled) {
                    ScenarioNotifier.notify(context, scenario, rawValue)
                }
                scenarioActionExecutor.execute(scenario)
            },
            onVerificationResult = { scenario, success, rawValue ->
                if (scenario.notificationEnabled) {
                    ScenarioNotifier.notifyVerification(context, scenario, success, rawValue)
                }
            }
        )
    }

    val deviceManager = remember {
        DeviceManager(
            onDevicesChanged = { devices = it },
            onPendingValueStored = { key, value ->
                addLog("MQTT state stored until CONFIG: " + key + " = " + value)
            }
        )
    }

    val mqtt = remember {
        MqttManager(
            onLog = ::addLog,
            onConnected = { value -> connected = value },
            onStatus = { deviceId, widgetId, value ->
                historyStore.add(deviceId, widgetId, value)
                scenarioEngine.onValue(deviceId, widgetId, value)
                deviceManager.onStatus(deviceId, widgetId, value)
            },
            onConfig = { deviceId, widgetId, label, widgetType, page, topic, order, raw ->
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
        )
    }

    scenarioActionExecutor.mqtt = mqtt

    DisposableEffect(mqtt, voiceManager, speech) {
        onDispose {
            if (!context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("marfa_voice_active", false)) {
                mqtt.disconnect()
            }
            voiceManager.stop()
            ttsController.shutdown()
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

    fun connect() {
        manualMqttDisconnect = false
        saveSettings()
        mqtt.connect(mqttHost, mqttPort.toIntOrNull() ?: 1883, mqttPrefix, username, password, mqttTls)
    }

        AppLifecycleEffects(
        context = context,
        mqtt = mqtt,
        voiceManager = voiceManager,
        speech = speech,
        manualMqttDisconnect = manualMqttDisconnect,
        voiceStatus = { voiceStatus = it },
        addLog = ::addLog,
        connect = ::connect
    )

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
            2 -> MqttScreen(Modifier.padding(padding), mqttHost, mqttPort, mqttPrefix, username, password, mqttTls, connected,
                { mqttHost = it }, { mqttPort = it }, { mqttPrefix = it }, { username = it }, { password = it }, { mqttTls = it },
                ::saveSettings, {
                    if (connected) {
                        manualMqttDisconnect = true
                        mqtt.disconnect()
                    } else {
                        connect()
                    }
                }, { mqtt.publishHello() })
            3 -> LogScreen(Modifier.padding(padding), log) { log = emptyList() }
            4 -> ScenariosScreen(
                Modifier.padding(padding),
                devices,
                scenarioStore,
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

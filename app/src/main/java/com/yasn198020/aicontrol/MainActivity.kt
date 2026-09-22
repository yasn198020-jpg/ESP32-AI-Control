package com.yasn198020.aicontrol

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject

data class Device(val id: String, val name: String, val online: Boolean, val widgets: List<WidgetState>)
data class WidgetState(val id: String, val title: String, val type: Type, val value: String, val page: String = "Основная", val topic: String = "", val order: Int = 0, val unit: String = "") {
    enum class Type { TOGGLE, BUTTON, INPUT, VALUE, STATUS }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var mqttHost by remember { mutableStateOf(prefs.getString("mqtt_host", "m4.wqtt.ru") ?: "m4.wqtt.ru") }
    var mqttPort by remember { mutableStateOf(prefs.getString("mqtt_port", "1883") ?: "1883") }
    var mqttTls by remember { mutableStateOf(prefs.getBoolean("mqtt_tls", false)) }
    var mqttPrefix by remember { mutableStateOf(prefs.getString("mqtt_prefix", "IoTManager") ?: "IoTManager") }
    var username by remember { mutableStateOf(prefs.getString("mqtt_user", "") ?: "") }
    var password by remember { mutableStateOf(prefs.getString("mqtt_pass", "") ?: "") }
    var aiEndpoint by remember { mutableStateOf(prefs.getString("ai_endpoint", "https://api.openai.com/v1/responses") ?: "https://api.openai.com/v1/responses") }
    var aiApiKey by remember { mutableStateOf(prefs.getString("ai_api_key", "") ?: "") }
    var aiModel by remember { mutableStateOf(prefs.getString("ai_model", "gpt-5.6-luna") ?: "gpt-5.6-luna") }
    var tab by remember { mutableIntStateOf(0) }
    var connected by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf(listOf("MQTT diagnostic log ready")) }
    var devices by remember { mutableStateOf(emptyList<Device>()) }
    var voiceText by remember { mutableStateOf("") }
    var voiceStatus by remember { mutableStateOf("Нажмите 🎤 и скажите команду") }
    val pendingValues = remember { mutableStateMapOf<String, String>() }
    val aiManager = remember { AiCommandManager() }

    fun addLog(message: String) { log = (log + message).takeLast(300) }
    val voiceManager = remember {
        VoiceCommandManager(
            context = context,
            onResult = { text ->
                voiceText = text
                voiceStatus = "Команда распознана"
            },
            onStatus = { status -> voiceStatus = status }
        )
    }

    val requestMicPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceManager.startRussian()
        } else {
            voiceStatus = "Нужно разрешение на микрофон"
        }
    }

    val mqtt = remember {
        MqttManager(
            onLog = ::addLog,
            onConnected = { value -> connected = value },
            onStatus = { deviceId, widgetId, value ->
                val key = "$deviceId/$widgetId"
                val existingDevice = devices.firstOrNull { it.id == deviceId }
                val existingWidget = existingDevice?.widgets?.any { it.id == widgetId } == true

                if (!existingWidget) {
                    pendingValues[key] = value
                    addLog("MQTT state stored until CONFIG: " + key + " = " + value)
                }

                if (existingDevice != null && existingWidget) {
                    devices = devices.map { device ->
                        if (device.id != deviceId) device else device.copy(
                            online = true,
                            widgets = device.widgets.map { widget ->
                                if (widget.id == widgetId) widget.copy(value = value) else widget
                            }
                        )
                    }
                } else if (existingDevice != null) {
                    devices = devices.map { device ->
                        if (device.id == deviceId) device.copy(online = true) else device
                    }
                }
            },
            onConfig = { deviceId, widgetId, label, widgetType, page, topic, order, raw ->
                val json = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
                val type = when (widgetType.lowercase()) {
                    "toggle" -> WidgetState.Type.TOGGLE
                    "button", "vbtn", "btn" -> WidgetState.Type.BUTTON
                    "input" -> WidgetState.Type.INPUT
                    "anydata", "value", "text", "number", "slider" -> WidgetState.Type.VALUE
                    else -> WidgetState.Type.STATUS
                }
                val newPage = page.ifBlank { "Основная" }
                val newUnit = json.optString("after").trim()
                val key = "$deviceId/$widgetId"
                val pendingValue = pendingValues[key]
                val existing = devices.firstOrNull { it.id == deviceId }
                val existingWidget = existing?.widgets?.firstOrNull { it.id == widgetId }
                val newWidget = WidgetState(
                    widgetId,
                    label.ifBlank { widgetId },
                    type,
                    pendingValue ?: existingWidget?.value ?: "",
                    newPage,
                    topic,
                    order,
                    newUnit
                )
                if (existing == null) {
                    devices = devices + Device(deviceId, deviceId, true, listOf(newWidget))
                } else {
                    devices = devices.map { device ->
                        if (device.id != deviceId) device else {
                            val exists = device.widgets.any { it.id == widgetId }
                            device.copy(
                                online = true,
                                widgets = if (exists) device.widgets.map { w ->
                                    if (w.id == widgetId) newWidget else w
                                } else device.widgets + newWidget
                            )
                        }
                    }
                }
                if (pendingValue != null) pendingValues.remove(key)
            }
        )
    }

    DisposableEffect(mqtt, voiceManager) { onDispose { mqtt.disconnect(); voiceManager.stop() } }

    fun saveSettings() {
        prefs.edit()
            .putString("mqtt_host", mqttHost)
            .putString("mqtt_port", mqttPort)
            .putBoolean("mqtt_tls", mqttTls)
            .putString("mqtt_prefix", mqttPrefix)
            .putString("mqtt_user", username)
            .putString("mqtt_pass", password)
            .putString("ai_endpoint", aiEndpoint)
            .putString("ai_api_key", aiApiKey)
            .putString("ai_model", aiModel)
            .apply()
        addLog("Settings saved")
    }

    fun connect() {
        saveSettings()
        mqtt.connect(mqttHost, mqttPort.toIntOrNull() ?: 1883, mqttPrefix, username, password, mqttTls)
    }

    fun sendWidget(deviceId: String, widgetId: String, value: String) {
        val widget = devices.firstOrNull { it.id == deviceId }?.widgets?.firstOrNull { it.id == widgetId } ?: return
        if (widget.topic.isBlank()) {
            addLog("MQTT TX skipped: config has no topic for " + widgetId)
            return
        }
        val published = when (widget.type) {
            WidgetState.Type.TOGGLE, WidgetState.Type.BUTTON ->
                mqtt.publishControl(deviceId, widgetId, value)
            else ->
                mqtt.publishWidget(widget.topic, value)
        }

        if (published) {
            devices = devices.map { device ->
                if (device.id != deviceId) device else device.copy(
                    widgets = device.widgets.map { w -> if (w.id == widgetId) w.copy(value = value) else w }
                )
            }
        }
    }

    LaunchedEffect(voiceText) {
        val command = voiceText.trim()
        if (command.isNotBlank()) {
            voiceStatus = "ИИ анализирует команду…"
            val result = aiManager.interpret(
                endpoint = aiEndpoint,
                apiKey = aiApiKey,
                model = aiModel,
                command = command,
                devices = devices
            )
            result.onSuccess { intent ->
                if (intent.action == "clarify") {
                    voiceStatus = intent.reply.ifBlank { "Уточните команду" }
                } else {
                    val device = devices.firstOrNull { it.id == intent.deviceId }
                    val widget = device?.widgets?.firstOrNull { it.id == intent.widgetId }
                    if (device == null || widget == null) {
                        voiceStatus = "ИИ выбрал неизвестный виджет. Команда не отправлена."
                    } else if (widget.type != WidgetState.Type.TOGGLE && widget.type != WidgetState.Type.BUTTON) {
                        voiceStatus = "Выбранный виджет нельзя управлять этой командой."
                    } else if (widget.topic.isBlank()) {
                        voiceStatus = "У выбранного виджета нет MQTT topic. Команда не отправлена."
                    } else if (intent.value != "0" && intent.value != "1") {
                        voiceStatus = "Недопустимое значение команды. Команда не отправлена."
                    } else {
                        sendWidget(intent.deviceId, intent.widgetId, intent.value)
                        voiceStatus = intent.reply.ifBlank { "Команда отправлена" }
                    }
                }
            }.onFailure { error ->
                voiceStatus = "Ошибка ИИ: " + (error.message ?: "неизвестная ошибка")
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("ESP32 AI Control", fontWeight = FontWeight.SemiBold) }) },
        bottomBar = {
            NavigationBar {
                listOf("Devices", "MQTT", "Log").forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text((index + 1).toString()) }, label = { Text(title) })
                }
            }
        }
    ) { padding ->
        when (tab) {
            0 -> DevicesScreen(
                Modifier.padding(padding),
                devices,
                voiceText,
                voiceStatus,
                onVoice = {
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
                onSend = ::sendWidget
            )
            1 -> MqttScreen(
                Modifier.padding(padding),
                mqttHost, mqttPort, mqttPrefix, username, password, mqttTls, connected,
                aiEndpoint, aiApiKey, aiModel,
                { mqttHost = it }, { mqttPort = it }, { mqttPrefix = it }, { username = it }, { password = it }, { mqttTls = it },
                { aiEndpoint = it }, { aiApiKey = it }, { aiModel = it },
                ::saveSettings, { if (connected) mqtt.disconnect() else connect() }, { mqtt.publishHello() }
            )
            else -> LogScreen(Modifier.padding(padding), log) { log = emptyList() }
        }
    }
}

@Composable
private fun DevicesScreen(
    modifier: Modifier,
    devices: List<Device>,
    voiceText: String,
    voiceStatus: String,
    onVoice: () -> Unit,
    onSend: (String, String, String) -> Unit
) {
    val pageWidgets = devices
        .flatMap { device -> device.widgets.map { widget -> device.id to widget } }
        .groupBy { (_, widget) -> widget.page }
        .toSortedMap()

    LazyColumn(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "voice-command") {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Голосовые сценарии", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Например: «Открой форточку у помидоров»",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Button(onClick = onVoice, shape = RoundedCornerShape(16.dp)) {
                            Text("🎤")
                        }
                    }
                    if (voiceText.isNotBlank()) {
                        Text("Вы сказали: «" + voiceText + "»", fontWeight = FontWeight.Medium)
                    }
                    Text(voiceStatus, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        pageWidgets.forEach { (pageName, entries) ->
            item(key = "page-$pageName") {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(pageName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

                        entries
                            .sortedWith(compareBy<Pair<String, WidgetState>> { it.second.order }.thenBy { it.second.title })
                            .forEach { (deviceId, widget) ->
                                when (widget.type) {
                                    WidgetState.Type.TOGGLE -> Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(widget.title, fontWeight = FontWeight.Medium)
                                        Switch(
                                            checked = widget.value == "1" || widget.value.equals("true", true),
                                            onCheckedChange = { onSend(deviceId, widget.id, if (it) "1" else "0") }
                                        )
                                    }
                                    WidgetState.Type.BUTTON -> Button(onClick = { onSend(deviceId, widget.id, "1") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                        Text(widget.title)
                                    }
                                    WidgetState.Type.INPUT -> InputWidget(widget, onSend = { value ->
                                        if (value.isNotBlank()) onSend(deviceId, widget.id, value)
                                    })
                                    WidgetState.Type.VALUE -> Text(
                                        widget.title + ": " + widget.value +
                                            if (widget.unit.isNotBlank()) " " + widget.unit else ""
                                    )
                                    WidgetState.Type.STATUS -> Text(widget.title + ": " + widget.value)
                                }
                            }
                    }
                }
            }
        }

        if (devices.isEmpty()) {
            item { Text("Нет конфигурации. Подключитесь к MQTT и нажмите HELLO.") }
        }
    }
}


private fun executeVoiceScenario(
    command: String,
    devices: List<Device>,
    onSend: (String, String, String) -> Unit
): String {
    val text = command.lowercase()
        .replace("ё", "е")
        .trim()

    val isOpen = listOf("открой", "открыть", "открывай", "подними", "включи", "включить").any { text.contains(it) }
    val isClose = listOf("закрой", "закрыть", "закрывай", "опусти", "выключи", "выключить").any { text.contains(it) }

    if (!isOpen && !isClose) {
        return "Пока поддерживаются команды открыть/закрыть и включить/выключить"
    }

    val pageCandidates = when {
        listOf("помидор", "помидоры", "томат", "томаты").any { text.contains(it) } ->
            devices.filter { it.widgets.any { w -> w.page.lowercase().contains("🍅") || w.page.lowercase().contains("помид") || w.page.lowercase().contains("томат") } }
        listOf("огурец", "огурцы").any { text.contains(it) } ->
            devices.filter { it.widgets.any { w -> w.page.lowercase().contains("🥒") || w.page.lowercase().contains("огур") } }
        else -> devices
    }

    val greenhouseWords = listOf("форточ", "двер", "ворот", "заслон", "клапан", "автомат", "насос", "вентилят")
    val requestedObject = greenhouseWords.firstOrNull { text.contains(it) }

    val candidates = pageCandidates
        .flatMap { device -> device.widgets.map { device.id to it } }
        .filter { (_, widget) ->
            val title = widget.title.lowercase()
            when {
                requestedObject != null -> title.contains(requestedObject)
                isOpen || isClose -> greenhouseWords.any { title.contains(it) }
                else -> widget.type == WidgetState.Type.TOGGLE || widget.type == WidgetState.Type.BUTTON
            }
        }

    if (candidates.size == 1) {
        val (deviceId, widget) = candidates.first()
        onSend(deviceId, widget.id, if (isOpen) "1" else "0")
        return (if (isOpen) "Открываю: " else "Закрываю: ") + widget.title
    }

    if (candidates.isEmpty()) {
        return "Не нашёл подходящий виджет для команды"
    }

    return "Нашёл несколько подходящих устройств. Уточните: дверь или форточка?"
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
    aiEndpoint: String, aiApiKey: String, aiModel: String,
    onHost: (String) -> Unit, onPort: (String) -> Unit, onPrefix: (String) -> Unit, onUser: (String) -> Unit, onPass: (String) -> Unit, onTls: (Boolean) -> Unit,
    onAiEndpoint: (String) -> Unit, onAiApiKey: (String) -> Unit, onAiModel: (String) -> Unit,
    onSave: () -> Unit, onConnect: () -> Unit, onHello: () -> Unit) {
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
        Text("ИИ для голосовых команд", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            aiEndpoint,
            onAiEndpoint,
            label = { Text("AI endpoint") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            aiModel,
            onAiModel,
            label = { Text("AI model") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            aiApiKey,
            onAiApiKey,
            label = { Text("OpenAI API key") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
        )
        Text(
            "Ключ хранится локально в настройках приложения. Для публичной APK-версии безопаснее использовать свой backend/proxy, а не встраивать ключ в APK.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun LogScreen(modifier: Modifier, log: List<String>, onClear: () -> Unit) {
    val context = LocalContext.current
    val logText = remember(log) { log.joinToString("\n") }

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Журнал  •  " + log.size, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClear) {
                    Text("Очистить")
                }
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MQTT Log", logText))
                }) {
                    Text("Копировать")
                }
            }
        }
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(log) { Text(it) }
            }
        }
    }
}
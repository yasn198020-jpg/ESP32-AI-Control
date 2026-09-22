package com.yasn198020.aicontrol

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
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
    var tab by remember { mutableIntStateOf(0) }
    var selectedPage by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf(listOf("MQTT diagnostic log ready")) }
    var devices by remember { mutableStateOf(emptyList<Device>()) }
    var voiceText by remember { mutableStateOf("") }
    var voiceStatus by remember { mutableStateOf("Нажмите 🎤 и скажите команду") }
    val pendingValues = remember { mutableStateMapOf<String, String>() }
    val localCommandManager = remember { LocalCommandManager() }

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
            voiceStatus = "Анализ команды…"
            val result = localCommandManager.interpret(command, devices)
            when (result.action) {
                LocalCommandAction.CONTROL -> {
                    val device = devices.firstOrNull { it.id == result.deviceId }
                    val widget = device?.widgets?.firstOrNull { it.id == result.widgetId }
                    if (device == null || widget == null) {
                        voiceStatus = "Подходящий виджет не найден. Команда не отправлена."
                    } else if (widget.type != WidgetState.Type.TOGGLE && widget.type != WidgetState.Type.BUTTON) {
                        voiceStatus = "Этот виджет нельзя управлять голосовой командой."
                    } else if (widget.topic.isBlank()) {
                        voiceStatus = "У выбранного виджета нет MQTT topic."
                    } else {
                        sendWidget(result.deviceId, result.widgetId, result.value)
                        voiceStatus = result.reply
                    }
                }
                LocalCommandAction.CLARIFY -> voiceStatus = result.reply
                LocalCommandAction.NOT_FOUND -> voiceStatus = result.reply
            }
        }
    }

    Scaffold(
        containerColor = Color(0xFF202020),
        topBar = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(76.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("☰", fontSize = 30.sp, modifier = Modifier.padding(end = 18.dp))
                    Text("?", fontSize = 22.sp, modifier = Modifier.padding(end = 18.dp))
                    Text(
                        if (tab == 0) "Dashboard" else if (tab == 1) "MQTT" else "Log",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text("ⓘ", fontSize = 22.sp, modifier = Modifier.padding(horizontal = 10.dp))
                    Text("☁", fontSize = 27.sp)
                }

                if (tab == 0) {
                    DashboardPageTabs(devices, selectedPage, onSelect = { selectedPage = it })
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF24252A),
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Text("▲", fontSize = 22.sp) },
                    label = null
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Text("☰", fontSize = 22.sp) },
                    label = null
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Text("○", fontSize = 25.sp) },
                    label = null
                )
            }
        }
    ) { padding ->
        when (tab) {
            0 -> DevicesScreen(
                Modifier.padding(padding),
                devices,
                selectedPage,
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
                { mqttHost = it }, { mqttPort = it }, { mqttPrefix = it }, { username = it }, { password = it }, { mqttTls = it },
                ::saveSettings, { if (connected) mqtt.disconnect() else connect() }, { mqtt.publishHello() }
            )
            else -> LogScreen(Modifier.padding(padding), log) { log = emptyList() }
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
    onVoice: () -> Unit,
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
            DashboardWidgetRow(widget, onSend = { value -> onSend(deviceId, widget.id, value) })
        }

        item(key = "voice-hidden-access") {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onVoice,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("🎤 Голосовая команда")
            }
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

@Composable
private fun DashboardWidgetRow(
    widget: WidgetState,
    onSend: (String) -> Unit
) {
    val isValue = widget.type == WidgetState.Type.VALUE || widget.type == WidgetState.Type.STATUS

    if (isValue) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .padding(horizontal = 22.dp),
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
                        widget.value.ifBlank { "—" } + if (widget.unit.isNotBlank()) " " + widget.unit else "",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(66.dp)
                .padding(horizontal = 22.dp),
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
                onCheckedChange = { checked -> onSend(if (checked) "1" else "0") }
            )
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
    onHost: (String) -> Unit, onPort: (String) -> Unit, onPrefix: (String) -> Unit, onUser: (String) -> Unit, onPass: (String) -> Unit, onTls: (Boolean) -> Unit,
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
        Text("Голосовое управление работает локально", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Команда анализируется прямо в APK по реальным виджетам MQTT. OpenAI API и интернет для анализа команды не нужны.",
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
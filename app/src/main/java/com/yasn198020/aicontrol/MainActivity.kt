package com.yasn198020.aicontrol

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject

data class Device(val id: String, val name: String, val online: Boolean, val widgets: List<WidgetState>)
data class WidgetState(val id: String, val title: String, val type: Type, val value: String, val page: String = "Основная", val topic: String = "", val order: Int = 0, val unit: String = "") {
    enum class Type { TOGGLE, BUTTON, VALUE, STATUS }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
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
    var connected by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf(listOf("MQTT diagnostic log ready")) }
    var devices by remember { mutableStateOf(emptyList<Device>()) }

    fun addLog(message: String) { log = (log + message).takeLast(300) }
    val mqtt = remember {
        MqttManager(
            onLog = ::addLog,
            onConnected = { value -> connected = value },
            onStatus = { deviceId, widgetId, payload ->
                val value = try {
                    val json = JSONObject(payload)
                    if (json.has("status")) json.optString("status") else payload
                } catch (_: Exception) { payload }
                val existingDevice = devices.firstOrNull { it.id == deviceId }
                if (existingDevice == null) {
                    devices = devices + Device(
                        deviceId,
                        deviceId,
                        true,
                        listOf(WidgetState(widgetId, widgetId, WidgetState.Type.STATUS, value))
                    )
                } else {
                    devices = devices.map { device ->
                        if (device.id != deviceId) device else {
                            val existingWidget = device.widgets.any { it.id == widgetId }
                            device.copy(
                                online = true,
                                widgets = if (existingWidget) {
                                    device.widgets.map { widget ->
                                        if (widget.id == widgetId) widget.copy(value = value) else widget
                                    }
                                } else {
                                    device.widgets + WidgetState(widgetId, widgetId, WidgetState.Type.STATUS, value)
                                }
                            )
                        }
                    }
                }
            },
            onConfig = { deviceId, widgetId, label, widgetType, page, topic, order, raw ->
                val json = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
                val type = when (widgetType.lowercase()) {
                    "toggle" -> WidgetState.Type.TOGGLE
                    "button", "vbtn", "btn" -> WidgetState.Type.BUTTON
                    "anydata", "value", "input", "text", "number", "slider" -> WidgetState.Type.VALUE
                    else -> WidgetState.Type.STATUS
                }
                val newPage = page.ifBlank { "Основная" }
                val newUnit = json.optString("after")
                val existing = devices.firstOrNull { it.id == deviceId }
                val newWidget = WidgetState(widgetId, label, type, "", newPage, topic, order, newUnit)
                if (existing == null) {
                    devices = devices + Device(deviceId, deviceId, true, listOf(newWidget))
                } else {
                    devices = devices.map { device ->
                        if (device.id != deviceId) device else {
                            val exists = device.widgets.any { it.id == widgetId }
                            device.copy(
                                online = true,
                                widgets = if (exists) device.widgets.map { w ->
                                    if (w.id == widgetId) newWidget.copy(value = w.value) else w
                                } else device.widgets + newWidget
                            )
                        }
                    }
                }
            }
        )
    }

    DisposableEffect(mqtt) { onDispose { mqtt.disconnect() } }

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
        if (mqtt.publishWidget(widget.topic, value)) {
            devices = devices.map { device ->
                if (device.id != deviceId) device else device.copy(
                    widgets = device.widgets.map { w -> if (w.id == widgetId) w.copy(value = value) else w }
                )
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("ESP32 AI Control") }) },
        bottomBar = {
            NavigationBar {
                listOf("Devices", "MQTT", "Log").forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text((index + 1).toString()) }, label = { Text(title) })
                }
            }
        }
    ) { padding ->
        when (tab) {
            0 -> DevicesScreen(Modifier.padding(padding), devices, ::sendWidget)
            1 -> MqttScreen(Modifier.padding(padding), mqttHost, mqttPort, mqttPrefix, username, password, mqttTls, connected,
                { mqttHost = it }, { mqttPort = it }, { mqttPrefix = it }, { username = it }, { password = it }, { mqttTls = it },
                ::saveSettings, { if (connected) mqtt.disconnect() else connect() }, { mqtt.publishHello() })
            else -> LogScreen(Modifier.padding(padding), log)
        }
    }
}

@Composable
private fun DevicesScreen(
    modifier: Modifier,
    devices: List<Device>,
    onSend: (String, String, String) -> Unit
) {
    LazyColumn(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(devices, key = { it.id }) { device ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(device.name, style = MaterialTheme.typography.titleLarge)
                            Text(device.id, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(if (device.online) "ONLINE" else "OFFLINE")
                    }
                    device.widgets.groupBy { it.page }.toSortedMap().forEach { (pageName, pageWidgets) ->
                        Text(pageName, style = MaterialTheme.typography.titleMedium)
                        pageWidgets.sortedWith(compareBy<WidgetState> { it.order }.thenBy { it.title }).forEach { widget ->
                            when (widget.type) {
                                WidgetState.Type.TOGGLE -> Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(widget.title)
                                    Switch(
                                        checked = widget.value == "1" || widget.value.equals("true", true),
                                        onCheckedChange = { onSend(device.id, widget.id, if (it) "1" else "0") }
                                    )
                                }
                                WidgetState.Type.BUTTON -> Button(onClick = { onSend(device.id, widget.id, "1") }) {
                                    Text(widget.title)
                                }
                                WidgetState.Type.VALUE -> Text(widget.title + ": " + widget.value + widget.unit)
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
        Text(if (connected) "MQTT: connected" else "MQTT: disconnected")
        Text("MQTT: " + host + ":" + port)
    }
}

@Composable
private fun LogScreen(modifier: Modifier, log: List<String>) {
    LazyColumn(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { items(log) { Text(it) } }
}
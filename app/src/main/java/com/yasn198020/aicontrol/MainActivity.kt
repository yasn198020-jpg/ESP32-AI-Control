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
data class WidgetState(val id: String, val title: String, val type: Type, val value: String) {
    enum class Type { TOGGLE, INPUT, STATUS }
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
    var devices by remember {
        mutableStateOf(listOf(Device("esp32-1", "ESP32 Controller", false, listOf(
            WidgetState("relay", "Relay", WidgetState.Type.TOGGLE, "0"),
            WidgetState("value", "Value", WidgetState.Type.INPUT, ""),
            WidgetState("status", "Status", WidgetState.Type.STATUS, "offline")
        ))))
    }

    fun addLog(message: String) { log = (log + message).takeLast(300) }
    val mqtt = remember {
        MqttManager(
            onLog = ::addLog,
            onConnected = { value -> connected = value },
            onStatus = { widgetId, payload ->
                val value = try {
                    val json = JSONObject(payload)
                    if (json.has("status")) json.optString("status") else payload
                } catch (_: Exception) { payload }
                devices = devices.map { device ->
                    device.copy(
                        online = true,
                        widgets = device.widgets.map { widget ->
                            if (widget.id == widgetId) widget.copy(value = value) else widget
                        }
                    )
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

    fun toggle(deviceId: String, widgetId: String, enabled: Boolean) {
        val value = if (enabled) "1" else "0"
        val sent = mqtt.publishControl(deviceId, widgetId, value)
        if (sent) {
            devices = devices.map { device ->
                if (device.id != deviceId) device else device.copy(widgets = device.widgets.map { widget ->
                    if (widget.id == widgetId) widget.copy(value = value) else widget
                })
            }
        }
    }

    fun input(deviceId: String, widgetId: String, value: String) {
        if (mqtt.publishControl(deviceId, widgetId, value)) {
            devices = devices.map { device ->
                if (device.id != deviceId) device else device.copy(widgets = device.widgets.map { widget ->
                    if (widget.id == widgetId) widget.copy(value = value) else widget
                })
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
            0 -> DevicesScreen(Modifier.padding(padding), devices, ::toggle, ::input)
            1 -> MqttScreen(Modifier.padding(padding), mqttHost, mqttPort, mqttPrefix, username, password, mqttTls, connected,
                { mqttHost = it }, { mqttPort = it }, { mqttPrefix = it }, { username = it }, { password = it }, { mqttTls = it },
                ::saveSettings, { if (connected) mqtt.disconnect() else connect() }, { mqtt.publishHello() })
            else -> LogScreen(Modifier.padding(padding), log)
        }
    }
}

@Composable
private fun DevicesScreen(modifier: Modifier, devices: List<Device>, onToggle: (String, String, Boolean) -> Unit, onInput: (String, String, String) -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(devices, key = { it.id }) { device ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column { Text(device.name, style = MaterialTheme.typography.titleLarge); Text(device.id, style = MaterialTheme.typography.bodySmall) }
                        Text(if (device.online) "ONLINE" else "OFFLINE")
                    }
                    device.widgets.forEach { widget ->
                        when (widget.type) {
                            WidgetState.Type.TOGGLE -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(widget.title); Switch(checked = widget.value == "1", onCheckedChange = { onToggle(device.id, widget.id, it) })
                            }
                            WidgetState.Type.INPUT -> InputWidget(widget, onSend = { onInput(device.id, widget.id, it) })
                            WidgetState.Type.STATUS -> Text(widget.title + ": " + widget.value)
                        }
                    }
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
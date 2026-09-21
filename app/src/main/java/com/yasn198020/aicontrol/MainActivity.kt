package com.yasn198020.aicontrol

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

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

@Composable
private fun App() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var mqttUrl by remember { mutableStateOf(prefs.getString("mqtt_url", "") ?: "") }
    var address by remember { mutableStateOf(prefs.getString("address", "") ?: "") }
    var tab by remember { mutableIntStateOf(0) }
    var connected by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf(listOf("MQTT diagnostic log ready")) }
    var devices by remember {
        mutableStateOf(listOf(
            Device("esp32-1", "ESP32 Controller", false, listOf(
                WidgetState("relay", "Relay", WidgetState.Type.TOGGLE, "0"),
                WidgetState("value", "Value", WidgetState.Type.INPUT, ""),
                WidgetState("status", "Status", WidgetState.Type.STATUS, "offline")
            ))
        ))
    }

    fun saveSettings() {
        prefs.edit().putString("mqtt_url", mqttUrl).putString("address", address).apply()
        log = log + "Settings saved"
    }

    fun toggle(deviceId: String, widgetId: String, enabled: Boolean) {
        val value = if (enabled) "1" else "0"
        devices = devices.map { device ->
            if (device.id != deviceId) device else device.copy(
                widgets = device.widgets.map { widget ->
                    if (widget.id == widgetId) widget.copy(value = value) else widget
                }
            )
        }
        log = log + "OUT toggle $deviceId/$widgetId=$value"
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("ESP32 AI Control") }) },
        bottomBar = {
            TabRow(selectedTabIndex = tab) {
                listOf("Devices", "MQTT", "Log").forEachIndexed { index, title ->
                    Tab(tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
        }
    ) { padding ->
        when (tab) {
            0 -> DevicesScreen(Modifier.padding(padding), devices, ::toggle)
            1 -> MqttScreen(
                Modifier.padding(padding), mqttUrl, address, connected,
                { mqttUrl = it }, { address = it }, ::saveSettings,
                {
                    connected = !connected
                    log = log + if (connected) "MQTT connect requested: $mqttUrl"
                    else "MQTT disconnect requested"
                },
                { log = log + "OUT HELLO requested" }
            )
            else -> LogScreen(Modifier.padding(padding), log)
        }
    }
}

@Composable
private fun DevicesScreen(
    modifier: Modifier,
    devices: List<Device>,
    onToggle: (String, String, Boolean) -> Unit
) {
    LazyColumn(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(devices, key = { it.id }) { device ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(device.name, style = MaterialTheme.typography.titleLarge)
                            Text(device.id, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(if (device.online) "ONLINE" else "OFFLINE")
                    }
                    Spacer(Modifier.height(12.dp))
                    device.widgets.forEach { widget ->
                        when (widget.type) {
                            WidgetState.Type.TOGGLE -> Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(widget.title)
                                Switch(widget.value == "1", { onToggle(device.id, widget.id, it) })
                            }
                            WidgetState.Type.INPUT -> OutlinedTextField(
                                widget.value, {}, label = { Text(widget.title) },
                                modifier = Modifier.fillMaxWidth(), enabled = false
                            )
                            WidgetState.Type.STATUS -> Text("${widget.title}: ${widget.value}")
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MqttScreen(
    modifier: Modifier,
    mqttUrl: String,
    address: String,
    connected: Boolean,
    onMqttUrlChange: (String) -> Unit,
    onAddressChange: (String) -> Unit,
    onSave: () -> Unit,
    onConnect: () -> Unit,
    onHello: () -> Unit
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(mqttUrl, onMqttUrlChange, label = { Text("MQTT URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(address, onAddressChange, label = { Text("Device address") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave) { Text("Save") }
            Button(onClick = onConnect) { Text(if (connected) "Disconnect" else "Connect") }
            OutlinedButton(onClick = onHello) { Text("HELLO") }
        }
        HorizontalDivider()
        Text(if (connected) "MQTT: connected/requested" else "MQTT: disconnected")
    }
}

@Composable
private fun LogScreen(modifier: Modifier, log: List<String>) {
    LazyColumn(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(log) { Text(it) }
    }
}

package com.yasn198020.aicontrol

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yasn198020.aicontrol.core.Device

@Composable
fun MarfaScenarioHubScreen(
    modifier: Modifier,
    devices: List<Device>,
    deviceScenarioManager: DeviceScenarioManager,
    localScenarioStore: ScenarioStore,
    localScenarioEngine: ScenarioEngine,
    onRequestNotifications: (() -> Unit) -> Unit
) {
    var mode by remember { mutableIntStateOf(0) }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = mode == 0,
                onClick = { mode = 0 },
                label = { Text("Логика устройств") }
            )
            FilterChip(
                selected = mode == 1,
                onClick = { mode = 1 },
                label = { Text("Мои правила") }
            )
        }

        if (mode == 0) {
            DeviceScenariosScreen(modifier, devices, deviceScenarioManager)
        } else {
            ScenariosScreen(
                modifier,
                devices,
                localScenarioStore,
                localScenarioEngine,
                onRequestNotifications
            )
        }
    }
}

@Composable
private fun DeviceScenariosScreen(
    modifier: Modifier,
    devices: List<Device>,
    manager: DeviceScenarioManager
) {
    var selectedDeviceId by remember { mutableStateOf(devices.firstOrNull()?.id.orEmpty()) }
    var title by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var parseMessage by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(manager.scenarios()) }
    var deviations by remember { mutableStateOf(manager.deviationSnapshot()) }
    var showSavedSource by remember { mutableStateOf<String?>(null) }
    var showSavedScenarios by remember { mutableStateOf(false) }

    fun refresh() {
        saved = manager.scenarios()
        deviations = manager.deviationSnapshot()
    }

    LaunchedEffect(manager) {
        while (true) {
            refresh()
            kotlinx.coroutines.delay(1000)
        }
    }

    LaunchedEffect(devices) {
        if (selectedDeviceId.isBlank() && devices.isNotEmpty()) {
            selectedDeviceId = devices.first().id
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            "Сценарии ESP32 / IoTManager",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Передайте Марфе scenario.txt. ESP32 продолжает выполнять свой локальный сценарий.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )

        if (devices.isNotEmpty()) {
            Text(
                "Устройство",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                devices.forEach { device ->
                    FilterChip(
                        selected = selectedDeviceId == device.id,
                        onClick = { selectedDeviceId = device.id },
                        label = { Text(device.name.ifBlank { device.id }) }
                    )
                }
            }
        } else {
            Text(
                "Подключите MQTT и дождитесь конфигурации устройств.",
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Название") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            singleLine = true
        )

        OutlinedTextField(
            value = source,
            onValueChange = { source = it },
            label = { Text("Текст scenario.txt") },
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(top = 8.dp),
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                enabled = source.isNotBlank(),
                onClick = {
                    parseMessage = DeviceScenarioModelFormatter.summary(manager.parseSource(source))
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Проверить")
            }

            Button(
                enabled = selectedDeviceId.isNotBlank() && source.isNotBlank(),
                onClick = {
                    val result = manager.saveScenario(selectedDeviceId, title, source)
                    val item = result.first
                    if (item == null) {
                        parseMessage = DeviceScenarioModelFormatter.summary(result.second)
                    } else {
                        parseMessage = "Сценарий сохранён. Правил: " + result.second.rules.size +
                            ". Контроль исполнения включён."
                        title = ""
                        source = ""
                        refresh()
                    }
                },
                modifier = Modifier.weight(1.35f)
            ) {
                Text("Сохранить сценарий")
            }

            OutlinedButton(
                onClick = {
                    title = ""
                    source = ""
                    parseMessage = ""
                },
                modifier = Modifier.weight(0.85f)
            ) {
                Text("Очистить")
            }
        }

        if (parseMessage.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        parseMessage,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (deviations.isNotEmpty()) {
            Text(
                "Текущие отклонения",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            deviations.forEach { deviation ->
                Card(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(deviation.message, fontWeight = FontWeight.SemiBold)
                        Text("Условие: " + deviation.condition, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Сохранённые сценарии",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Button(onClick = { showSavedScenarios = true }) {
                Text("Открыть список")
            }
        }

        Text(
            if (saved.isEmpty()) "Пока нет сохранённых сценариев."
            else "Сохранено: " + saved.size,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )
    }

    if (showSavedScenarios) {
        Dialog(
            onDismissRequest = { showSavedScenarios = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Сохранённые сценарии",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showSavedScenarios = false }) {
                            Text("Закрыть")
                        }
                    }
                    Text(
                        "Все сохранённые scenario.txt для устройств.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                    if (saved.isEmpty()) {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Сохранённых сценариев пока нет.")
                        }
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(saved, key = { it.id }) { item ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(
                                            item.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            "Устройство: " + item.deviceId,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Row(
                                            Modifier.fillMaxWidth().padding(top = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                if (item.enabled) "Контроль включён"
                                                else "Контроль выключен"
                                            )
                                            Spacer(Modifier.weight(1f))
                                            Switch(
                                                checked = item.enabled,
                                                onCheckedChange = {
                                                    manager.setEnabled(item.id, it)
                                                    refresh()
                                                }
                                            )
                                            OutlinedButton(
                                                onClick = { showSavedSource = item.id }
                                            ) {
                                                Text("Открыть")
                                            }
                                            TextButton(
                                                onClick = {
                                                    manager.remove(item.id)
                                                    refresh()
                                                }
                                            ) {
                                                Text("Удалить")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    showSavedSource?.let { id ->
        val item = saved.firstOrNull { it.id == id }
        if (item != null) {
            AlertDialog(
                onDismissRequest = { showSavedSource = null },
                title = { Text(item.title) },
                text = {
                    SelectionContainer {
                        Text(
                            item.source,
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSavedSource = null }) {
                        Text("Готово")
                    }
                }
            )
        }
    }
}

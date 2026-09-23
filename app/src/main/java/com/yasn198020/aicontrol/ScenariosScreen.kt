package com.yasn198020.aicontrol

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ScenariosScreen(
    modifier: Modifier,
    devices: List<Device>,
    store: ScenarioStore,
    onRequestNotifications: () -> Unit
) {
    var scenarios by remember { mutableStateOf(store.load()) }
    var adding by remember { mutableStateOf(false) }
    fun refresh() { scenarios = store.load() }

    Column(modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Сценарии", fontSize = 24.sp)
            Button(onClick = { onRequestNotifications(); adding = true }) { Text("+ Добавить") }
        }
        Spacer(Modifier.height(8.dp))
        if (scenarios.isEmpty()) {
            Text("Нет сценариев. Например: температура t65 > 25 °C → уведомление на телефон.", fontSize = 16.sp)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(scenarios, key = { it.id }) { scenario ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(scenario.title, fontSize = 18.sp)
                            Text("${scenario.deviceId} / ${scenario.widgetId}  ${scenario.operator}  ${scenario.threshold}")
                            Text(scenario.message)
                            Text(if (scenario.enabled) "Включён" else "Выключен")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    store.update(scenario.copy(enabled = !scenario.enabled, armed = true)); refresh()
                                }) { Text(if (scenario.enabled) "Выключить" else "Включить") }
                                OutlinedButton(onClick = { store.delete(scenario.id); refresh() }) { Text("Удалить") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding) {
        ScenarioEditorDialog(devices, onDismiss = { adding = false }, onSave = {
            store.add(it); refresh(); adding = false
        })
    }
}

@Composable
private fun ScenarioEditorDialog(
    devices: List<Device>,
    onDismiss: () -> Unit,
    onSave: (Scenario) -> Unit
) {
    val numericWidgets = devices.flatMap { device ->
        device.widgets.filter { it.type == WidgetState.Type.VALUE || it.type == WidgetState.Type.STATUS }.map { device to it }
    }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var operator by remember { mutableStateOf(">") }
    var thresholdText by remember { mutableStateOf("25") }
    var title by remember { mutableStateOf("Температура высокая") }
    var message by remember { mutableStateOf("Температура выше {threshold}°C: {value}°C") }
    var menuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый сценарий") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (numericWidgets.isEmpty()) {
                    Text("Пока нет датчиков со значением. Сначала дождитесь CONFIG от устройства.")
                } else {
                    val safeIndex = selectedIndex.coerceIn(0, numericWidgets.lastIndex)
                    val pair = numericWidgets[safeIndex]
                    Text("Датчик")
                    Box {
                        OutlinedButton(onClick = { menuOpen = true }) { Text("${pair.first.id} / ${pair.second.id}  ${pair.second.title}") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            numericWidgets.forEachIndexed { index, item ->
                                DropdownMenuItem(
                                    text = { Text("${item.first.id} / ${item.second.id}  ${item.second.title}") },
                                    onClick = { selectedIndex = index; menuOpen = false }
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            operator = when (operator) { ">" -> ">="; ">=" -> "<"; "<" -> "<="; else -> ">" }
                        }) { Text(operator) }
                        OutlinedTextField(value = thresholdText, onValueChange = { thresholdText = it }, label = { Text("Порог") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Заголовок") }, singleLine = true)
                    OutlinedTextField(value = message, onValueChange = { message = it }, label = { Text("Сообщение") }, minLines = 2)
                    Text("Можно использовать {value}, {threshold}, {device}, {widget}.")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val threshold = thresholdText.replace(',', '.').toDoubleOrNull()
                if (numericWidgets.isNotEmpty() && threshold != null && threshold.isFinite()) {
                    val pair = numericWidgets[selectedIndex.coerceIn(0, numericWidgets.lastIndex)]
                    onSave(Scenario(
                        deviceId = pair.first.id, widgetId = pair.second.id,
                        title = title.trim().ifBlank { "Сценарий" },
                        operator = operator, threshold = threshold,
                        message = message.trim().ifBlank { "{value}" }
                    ))
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

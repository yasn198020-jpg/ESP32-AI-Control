package com.yasn198020.aicontrol

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight

@Composable
fun ScenariosScreen(
    modifier: Modifier,
    devices: List<Device>,
    store: ScenarioStore,
    onRequestNotifications: (() -> Unit) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var scenarios by remember { mutableStateOf(store.load()) }
    var adding by remember { mutableStateOf(false) }
    fun refresh() { scenarios = store.load() }

    Column(modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Сценарии", fontSize = 24.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    onRequestNotifications {
                        val test = scenarios.firstOrNull()
                        if (test != null) ScenarioNotifier.notify(context, test, "25.0")
                        else ScenarioNotifier.test(context)
                    }
                }) { Text("🔔 Тест") }
                Button(onClick = { onRequestNotifications { adding = true } }) { Text("+ Добавить") }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (scenarios.isEmpty()) {
            Text("Нет сценариев. Например: t65 > 25 И d12 < 10 → уведомление.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(scenarios, key = { it.id }) { scenario ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(scenario.title, fontSize = 18.sp)
                            scenario.conditions.forEachIndexed { index, condition ->
                                Text(
                                    (if (index == 0) "" else "${condition.connector} ") +
                                        "${condition.deviceId} / ${condition.widgetId} ${condition.operator} ${condition.threshold}"
                                )
                            }
                            Text(scenario.message)
                            Text(
                                if (scenario.actionType == "MQTT_CONTROL")
                                    "Действие: ${scenario.actionDeviceId}/${scenario.actionWidgetId} → ${scenario.actionValue}"
                                else "Действие: уведомление"
                            )
                            Text(if (scenario.enabled) "Включён" else "Выключен")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    store.update(scenario.copy(enabled = !scenario.enabled, armed = true))
                                    refresh()
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

private data class ConditionDraft(
    val selectedIndex: Int = 0,
    val operator: String = ">",
    val thresholdText: String = "25",
    val connector: String = "AND"
)

@Composable
private fun ScenarioWidgetTile(
    device: Device,
    widget: WidgetState,
    selected: Boolean,
    onClick: () -> Unit
) {
    val icon = when (widget.type) {
        WidgetState.Type.VALUE, WidgetState.Type.STATUS -> "🌡"
        WidgetState.Type.BUTTON, WidgetState.Type.TOGGLE -> "◉"
        else -> ""
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 22.sp, modifier = Modifier.width(38.dp))
            Column(Modifier.weight(1f)) {
                Text(widget.title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text(
                    "${device.id} / ${widget.id}" +
                        if (widget.value.isNotBlank()) "  •  ${widget.value}${widget.unit}" else "",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (selected) Text("✓", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ScenarioEditorDialog(
    devices: List<Device>,
    onDismiss: () -> Unit,
    onSave: (Scenario) -> Unit
) {
    // Кэшируем списки: при выборе плитки не нужно заново фильтровать и сортировать
    // все устройства и виджеты.
    val conditionWidgets = remember(devices) {
        devices
            .flatMap { device ->
                device.widgets.filter {
                    it.type == WidgetState.Type.VALUE ||
                    it.type == WidgetState.Type.STATUS ||
                    it.type == WidgetState.Type.TOGGLE ||
                    it.type == WidgetState.Type.BUTTON
                }.map { device to it }
            }
            .sortedWith(
                compareBy<Pair<Device, WidgetState>> { it.second.order }
                    .thenBy { it.second.title }
                    .thenBy { it.first.id }
            )
    }
    val sensors = remember(conditionWidgets) {
        conditionWidgets.filter {
            it.second.type == WidgetState.Type.VALUE || it.second.type == WidgetState.Type.STATUS
        }
    }
    val controls = remember(conditionWidgets) {
        conditionWidgets.filter {
            it.second.type == WidgetState.Type.TOGGLE || it.second.type == WidgetState.Type.BUTTON
        }
    }

    val drafts = remember { mutableStateListOf(ConditionDraft()) }
    var title by remember { mutableStateOf("Температура высокая") }
    var message by remember { mutableStateOf("Условие выполнено: {value}") }
    var actionType by remember { mutableStateOf("NOTIFICATION") }
    var actionIndex by remember { mutableIntStateOf(0) }
    var actionValue by remember { mutableStateOf("1") }
    var actionMenuOpen by remember { mutableStateOf(false) }
    var targetMenuOpen by remember { mutableStateOf(false) }
    var valueMenuOpen by remember { mutableStateOf(false) }

    fun operatorNext(value: String) = when (value) {
        ">" -> ">="
        ">=" -> "<"
        "<" -> "<="
        "<=" -> "="
        else -> ">"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый сценарий") },
        text = {
            Column(modifier = Modifier.heightIn(max = 560.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (conditionWidgets.isEmpty()) {
                    Text("Пока нет датчиков, кнопок или переключателей для проверки.")
                } else {
                    Text("Условия", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    drafts.forEachIndexed { index, draft ->
                        if (index > 0) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Связь:", modifier = Modifier.align(Alignment.CenterVertically))
                                OutlinedButton(onClick = {
                            drafts[index] = draft.copy(connector = if (draft.connector == "AND") "OR" else "AND")
                        }) {
                                    Text(if (draft.connector == "AND") "И" else "ИЛИ")
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { drafts.removeAt(index) }) { Text("Удалить") }
                            }
                        }

                        val safeIndex = draft.selectedIndex.coerceIn(0, conditionWidgets.lastIndex)
                        val pair = conditionWidgets[safeIndex]

                        // Выбор виджета показываем плитками — в том же визуальном стиле,
                        // что и элементы главного экрана.

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (sensors.isNotEmpty()) {
                                Text("Датчики", fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                                sensors.forEach { item ->
                                    val itemIndex = conditionWidgets.indexOf(item)
                                    ScenarioWidgetTile(
                                        device = item.first,
                                        widget = item.second,
                                        selected = safeIndex == itemIndex,
                                        onClick = { drafts[index] = draft.copy(selectedIndex = itemIndex) }
                                    )
                                }
                            }
                            if (controls.isNotEmpty()) {
                                Text("Кнопки и переключатели", fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                                controls.forEach { item ->
                                    val itemIndex = conditionWidgets.indexOf(item)
                                    ScenarioWidgetTile(
                                        device = item.first,
                                        widget = item.second,
                                        selected = safeIndex == itemIndex,
                                        onClick = { draft.selectedIndex = itemIndex }
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { drafts[index] = draft.copy(operator = operatorNext(draft.operator)) },
                                modifier = Modifier.width(72.dp)
                            ) {
                                Text(draft.operator)
                            }
                            OutlinedTextField(
                                value = draft.thresholdText,
                                onValueChange = { drafts[index] = draft.copy(thresholdText = it) },
                                label = { Text("Порог") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    OutlinedButton(onClick = { drafts.add(ConditionDraft()) }, modifier = Modifier.fillMaxWidth()) {
                        Text("+ Условие")
                    }
                    Text("В условиях можно использовать датчики, кнопки и переключатели. Для кнопок обычно используйте = 1 или = 0.")

                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Заголовок") }, singleLine = true)
                    OutlinedTextField(value = message, onValueChange = { message = it }, label = { Text("Сообщение") }, minLines = 2)

                    Text("Действие после условия", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Box {
                        OutlinedButton(onClick = { actionMenuOpen = true }) {
                            Text(if (actionType == "MQTT_CONTROL") "Управить виджетом" else "Только уведомление")
                        }
                        DropdownMenu(expanded = actionMenuOpen, onDismissRequest = { actionMenuOpen = false }) {
                            DropdownMenuItem(text = { Text("Только уведомление") }, onClick = { actionType = "NOTIFICATION"; actionMenuOpen = false })
                            DropdownMenuItem(text = { Text("Управить виджетом") }, onClick = { actionType = "MQTT_CONTROL"; actionMenuOpen = false })
                        }
                    }

                    if (actionType == "MQTT_CONTROL") {
                        if (controls.isEmpty()) {
                            Text("Нет переключателей или кнопок для управления.")
                        } else {
                            val safeActionIndex = actionIndex.coerceIn(0, controls.lastIndex)
                            val target = controls[safeActionIndex]
                            Box {
                                OutlinedButton(onClick = { targetMenuOpen = true }) {
                                    Text("${target.first.id} / ${target.second.id}  ${target.second.title}")
                                }
                                DropdownMenu(expanded = targetMenuOpen, onDismissRequest = { targetMenuOpen = false }) {
                                    controls.forEachIndexed { itemIndex, item ->
                                        DropdownMenuItem(
                                            text = { Text("${item.first.id} / ${item.second.id}  ${item.second.title}") },
                                            onClick = { actionIndex = itemIndex; targetMenuOpen = false }
                                        )
                                    }
                                }
                            }
                            Box {
                                OutlinedButton(onClick = { valueMenuOpen = true }) {
                                    Text(if (actionValue == "1") "Включить / Нажать" else "Выключить")
                                }
                                DropdownMenu(expanded = valueMenuOpen, onDismissRequest = { valueMenuOpen = false }) {
                                    DropdownMenuItem(text = { Text("Включить / Нажать (1)") }, onClick = { actionValue = "1"; valueMenuOpen = false })
                                    DropdownMenuItem(text = { Text("Выключить (0)") }, onClick = { actionValue = "0"; valueMenuOpen = false })
                                }
                            }
                        }
                    }
                    Text("Можно использовать {value}, {threshold}, {device}, {widget}.")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = drafts.mapNotNull { draft ->
                    val threshold = draft.thresholdText.replace(',', '.').toDoubleOrNull()
                    if (threshold == null || !threshold.isFinite() || conditionWidgets.isEmpty()) null
                    else {
                        val pair = conditionWidgets[draft.selectedIndex.coerceIn(0, conditionWidgets.lastIndex)]
                        ScenarioCondition(pair.first.id, pair.second.id, draft.operator, threshold, draft.connector)
                    }
                }
                if (parsed.size == drafts.size && parsed.isNotEmpty()) {
                    val first = parsed.first()
                    val target = if (controls.isNotEmpty()) controls[actionIndex.coerceIn(0, controls.lastIndex)] else null
                    onSave(Scenario(
                        deviceId = first.deviceId,
                        widgetId = first.widgetId,
                        title = title.trim().ifBlank { "Сценарий" },
                        operator = first.operator,
                        threshold = first.threshold,
                        message = message.trim().ifBlank { "{value}" },
                        actionType = actionType,
                        actionDeviceId = if (actionType == "MQTT_CONTROL") target?.first?.id.orEmpty() else "",
                        actionWidgetId = if (actionType == "MQTT_CONTROL") target?.second?.id.orEmpty() else "",
                        actionValue = actionValue,
                        conditions = parsed
                    ))
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

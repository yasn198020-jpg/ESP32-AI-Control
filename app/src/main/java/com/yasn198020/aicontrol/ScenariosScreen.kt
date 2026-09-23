package com.yasn198020.aicontrol
import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState

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
    var editing by remember { mutableStateOf<Scenario?>(null) }
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
                            if (scenario.actionType == "MQTT_CONTROL") {
                                val displayActions = scenario.actions.ifEmpty {
                                    if (scenario.actionDeviceId.isNotBlank() && scenario.actionWidgetId.isNotBlank()) {
                                        listOf(ScenarioAction(scenario.actionDeviceId, scenario.actionWidgetId, scenario.actionValue))
                                    } else emptyList()
                                }
                                if (displayActions.isEmpty()) {
                                    Text("Действие: не задано")
                                } else {
                                    displayActions.forEachIndexed { actionIndex, action ->
                                        Text(
                                            "Действие ${actionIndex + 1}: ${action.deviceId}/${action.widgetId} → ${action.value}"
                                        )
                                    }
                                }
                            } else {
                                Text("Действие: уведомление")
                            }
                            Text((if (scenario.enabled) "Включён" else "Выключен") + "  •  " + (if (scenario.notificationEnabled) "уведомления включены" else "без уведомлений"))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    store.update(scenario.copy(enabled = !scenario.enabled, armed = true))
                                    refresh()
                                }) { Text(if (scenario.enabled) "Выключить" else "Включить") }
                                OutlinedButton(onClick = { editing = scenario }) { Text("Изменить") }
                                OutlinedButton(onClick = {
                                    val copy = scenario.copy(
                                        id = java.util.UUID.randomUUID().toString(),
                                        title = scenario.title + " (копия)",
                                        enabled = false,
                                        armed = true
                                    )
                                    store.add(copy)
                                    refresh()
                                }) { Text("Копировать") }
                                OutlinedButton(onClick = { store.delete(scenario.id); refresh() }) { Text("Удалить") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding) {
        ScenarioEditorDialog(devices, null, onDismiss = { adding = false }, onSave = {
            store.add(it); refresh(); adding = false
        })
    }
    editing?.let { scenario ->
        ScenarioEditorDialog(devices, scenario, onDismiss = { editing = null }, onSave = {
            store.update(it); refresh(); editing = null
        })
    }
}

private data class ConditionDraft(
    val selectedIndex: Int = 0,
    val operator: String = ">",
    val thresholdText: String = "25",
    val connector: String = "AND"
)

private data class ActionDraft(
    val selectedIndex: Int = 0,
    val value: String = "1"
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
    initialScenario: Scenario?,
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
                compareBy<Pair<Device, WidgetState>> { it.second.page.ifBlank { "Основная" } }
                    .thenBy { it.second.order }
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

    val drafts = remember(initialScenario) {
        mutableStateListOf<ConditionDraft>().apply {
            val saved = initialScenario?.conditions ?: emptyList()
            if (saved.isEmpty()) add(ConditionDraft())
            else saved.forEach { add(ConditionDraft(0, it.operator, it.threshold.toString(), it.connector)) }
        }
    }
    var title by remember(initialScenario) { mutableStateOf(initialScenario?.title ?: "Температура высокая") }
    var message by remember(initialScenario) { mutableStateOf(initialScenario?.message ?: "Условие выполнено: {value}") }
    var actionType by remember(initialScenario) { mutableStateOf(initialScenario?.actionType ?: "NOTIFICATION") }
    var notificationEnabled by remember(initialScenario) { mutableStateOf(initialScenario?.notificationEnabled ?: true) }
    val actionDrafts = remember(initialScenario) {
        val saved = initialScenario?.actions?.ifEmpty {
            if (initialScenario.actionType == "MQTT_CONTROL" && initialScenario.actionDeviceId.isNotBlank() && initialScenario.actionWidgetId.isNotBlank())
                listOf(ScenarioAction(initialScenario.actionDeviceId, initialScenario.actionWidgetId, initialScenario.actionValue))
            else emptyList()
        } ?: emptyList()
        mutableStateListOf(*saved.map { ActionDraft(0, it.value) }.ifEmpty { listOf(ActionDraft()) }.toTypedArray())
    }
    var actionSelectionOpenIndex by remember { mutableIntStateOf(-1) }
    var actionValueMenuIndex by remember { mutableIntStateOf(-1) }
    var actionMenuOpen by remember { mutableStateOf(false) }
    var valueMenuOpen by remember { mutableStateOf(false) }
    var verifyEnabled by remember(initialScenario) { mutableStateOf(initialScenario?.verifyEnabled ?: false) }
    var verifyTargetIndex by remember { mutableIntStateOf(0) }
    var verifyTimeoutText by remember(initialScenario) { mutableStateOf((initialScenario?.verifyTimeoutSec ?: 30).toString()) }
    var verifyValueText by remember(initialScenario) { mutableStateOf((initialScenario?.verifyValue ?: 1.0).toString()) }
    var verifySuccessMessage by remember(initialScenario) { mutableStateOf(initialScenario?.verifySuccessMessage ?: "Подтверждение получено: {value}") }
    var verifyFailureMessage by remember(initialScenario) { mutableStateOf(initialScenario?.verifyFailureMessage ?: "Подтверждение не получено") }
    var conditionSelectionOpen by remember { mutableStateOf(false) }
    var actionSelectionOpen by remember { mutableStateOf(false) }
    var verifySelectionOpen by remember { mutableStateOf(false) }

    LaunchedEffect(initialScenario, conditionWidgets) {
        initialScenario?.conditions?.forEachIndexed { index, condition ->
            if (index < drafts.size) {
                val found = conditionWidgets.indexOfFirst { it.first.id == condition.deviceId && it.second.id == condition.widgetId }
                if (found >= 0) drafts[index] = drafts[index].copy(selectedIndex = found)
            }
        }
        initialScenario?.actions?.forEachIndexed { index, action ->
            if (index < actionDrafts.size) {
                val found = controls.indexOfFirst { it.first.id == action.deviceId && it.second.id == action.widgetId }
                if (found >= 0) actionDrafts[index] = actionDrafts[index].copy(selectedIndex = found)
            }
        }
        if (initialScenario != null && initialScenario.actions.isEmpty() && initialScenario.actionDeviceId.isNotBlank()) {
            val found = controls.indexOfFirst { it.first.id == initialScenario.actionDeviceId && it.second.id == initialScenario.actionWidgetId }
            if (found >= 0) actionDrafts[0] = actionDrafts[0].copy(selectedIndex = found)
        }
        if (initialScenario != null && initialScenario.verifyDeviceId.isNotBlank()) {
            val found = conditionWidgets.indexOfFirst { it.first.id == initialScenario.verifyDeviceId && it.second.id == initialScenario.verifyWidgetId }
            if (found >= 0) verifyTargetIndex = found
        }
    }

    fun operatorNext(value: String) = when (value) {
        ">" -> ">="
        ">=" -> "<"
        "<" -> "<="
        "<=" -> "="
        else -> ">"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialScenario == null) "Новый сценарий" else "Редактирование сценария") },
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

                        val pages = conditionWidgets
                            .map { it.second.page.ifBlank { "Основная" } }
                            .distinct()

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            pages.forEach { page ->
                                val pageWidgets = conditionWidgets.filter {
                                    it.second.page.ifBlank { "Основная" } == page
                                }

                                val visiblePageWidgets = pageWidgets.filter { item ->
                                    val itemIndex = conditionWidgets.indexOf(item)
                                    !conditionSelectionOpen || safeIndex == itemIndex
                                }

                                if (visiblePageWidgets.isNotEmpty()) {
                                    Text(
                                        page,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                    )

                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        visiblePageWidgets.forEach { item ->
                                            val itemIndex = conditionWidgets.indexOf(item)
                                            ScenarioWidgetTile(
                                                device = item.first,
                                                widget = item.second,
                                                selected = safeIndex == itemIndex,
                                                onClick = {
                                                    if (safeIndex == itemIndex) {
                                                        conditionSelectionOpen = !conditionSelectionOpen
                                                    } else {
                                                        drafts[index] = draft.copy(selectedIndex = itemIndex)
                                                        conditionSelectionOpen = false
                                                    }
                                                }
                                            )
                                        }
                                    }
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

                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Заголовок") },
                        singleLine = true,
                        
                    )
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        label = { Text("Сообщение") },
                        minLines = 2,
                        
                    )

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = notificationEnabled, onCheckedChange = { notificationEnabled = it })
                        Column {
                            Text("Показывать уведомление", fontWeight = FontWeight.Medium)
                            Text("Можно отключить уведомления для этого сценария", style = MaterialTheme.typography.bodySmall)
                        }
                    }

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
                            Text("Действия", fontWeight = FontWeight.Medium)
                            actionDrafts.forEachIndexed { actionNumber, actionDraft ->
                                val safeActionIndex = actionDraft.selectedIndex.coerceIn(0, controls.lastIndex)
                                Text("Действие " + (actionNumber + 1), fontWeight = FontWeight.SemiBold)
                                val controlPages = controls.map { it.second.page.ifBlank { "Основная" } }.distinct()
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    controlPages.forEach { page ->
                                        val pageControls = controls.filter { it.second.page.ifBlank { "Основная" } == page }
                                        val visiblePageControls = pageControls.filter { item ->
                                            val itemIndex = controls.indexOf(item)
                                            actionSelectionOpenIndex == actionNumber || safeActionIndex == itemIndex
                                        }
                                        if (visiblePageControls.isNotEmpty()) {
                                            Text(page, fontWeight = FontWeight.Medium)
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                visiblePageControls.forEach { item ->
                                                    val itemIndex = controls.indexOf(item)
                                                    ScenarioWidgetTile(
                                                        device = item.first,
                                                        widget = item.second,
                                                        selected = safeActionIndex == itemIndex,
                                                        onClick = {
                                                            if (safeActionIndex == itemIndex) {
                                                                actionSelectionOpenIndex = if (actionSelectionOpenIndex == actionNumber) -1 else actionNumber
                                                            } else {
                                                                actionDrafts[actionNumber] = actionDraft.copy(selectedIndex = itemIndex)
                                                                actionSelectionOpenIndex = -1
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box {
                                        OutlinedButton(onClick = { actionValueMenuIndex = actionNumber }) {
                                            Text(if (actionDraft.value == "1") "Включить / Нажать" else "Выключить")
                                        }
                                        DropdownMenu(expanded = actionValueMenuIndex == actionNumber, onDismissRequest = { actionValueMenuIndex = -1 }) {
                                            DropdownMenuItem(text = { Text("Включить / Нажать (1)") }, onClick = { actionDrafts[actionNumber] = actionDraft.copy(value = "1"); actionValueMenuIndex = -1 })
                                            DropdownMenuItem(text = { Text("Выключить (0)") }, onClick = { actionDrafts[actionNumber] = actionDraft.copy(value = "0"); actionValueMenuIndex = -1 })
                                        }
                                    }
                                    if (actionDrafts.size > 1) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton(enabled = actionNumber > 0, onClick = {
                                                val tmp = actionDrafts[actionNumber]
                                                actionDrafts[actionNumber] = actionDrafts[actionNumber - 1]
                                                actionDrafts[actionNumber - 1] = tmp
                                                actionSelectionOpenIndex = -1
                                                actionValueMenuIndex = -1
                                            }) { Text("↑") }
                                            TextButton(enabled = actionNumber < actionDrafts.lastIndex, onClick = {
                                                val tmp = actionDrafts[actionNumber]
                                                actionDrafts[actionNumber] = actionDrafts[actionNumber + 1]
                                                actionDrafts[actionNumber + 1] = tmp
                                                actionSelectionOpenIndex = -1
                                                actionValueMenuIndex = -1
                                            }) { Text("↓") }
                                            TextButton(onClick = { actionDrafts.removeAt(actionNumber); actionSelectionOpenIndex = -1; actionValueMenuIndex = -1 }) { Text("Удалить") }
                                        }
                                    }
                                }
                            }
                            OutlinedButton(onClick = { actionDrafts.add(ActionDraft()); actionSelectionOpenIndex = -1; actionValueMenuIndex = -1 }, modifier = Modifier.fillMaxWidth()) {
                                Text("+ Действие")
                            }
                        }
                    }
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = verifyEnabled, onCheckedChange = { verifyEnabled = it })
                        Column {
                            Text("Проверять результат", fontWeight = FontWeight.SemiBold)
                            Text("Ждать подтверждение максимум заданное время", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    if (verifyEnabled && conditionWidgets.isNotEmpty()) {
                        val safeVerifyIndex = verifyTargetIndex.coerceIn(0, conditionWidgets.lastIndex)
                        val verifyTarget = conditionWidgets[safeVerifyIndex]

                        Text("Проверять виджет", fontWeight = FontWeight.Medium)
                        val verifyPages = conditionWidgets
                            .map { it.second.page.ifBlank { "Основная" } }
                            .distinct()

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            verifyPages.forEach { page ->
                                val pageWidgets = conditionWidgets.filter {
                                    it.second.page.ifBlank { "Основная" } == page
                                }
                                val visiblePageWidgets = pageWidgets.filter { item ->
                                    val itemIndex = conditionWidgets.indexOf(item)
                                    !verifySelectionOpen || verifyTargetIndex.coerceIn(0, conditionWidgets.lastIndex) == itemIndex
                                }
                                if (visiblePageWidgets.isNotEmpty()) {
                                    Text(page, fontWeight = FontWeight.Medium)
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    visiblePageWidgets.forEach { item ->
                                        val itemIndex = conditionWidgets.indexOf(item)
                                        ScenarioWidgetTile(
                                            device = item.first,
                                            widget = item.second,
                                            selected = verifyTargetIndex.coerceIn(0, conditionWidgets.lastIndex) == itemIndex,
                                            onClick = {
                                            if (verifyTargetIndex.coerceIn(0, conditionWidgets.lastIndex) == itemIndex) {
                                                verifySelectionOpen = !verifySelectionOpen
                                            } else {
                                                verifyTargetIndex = itemIndex
                                                verifySelectionOpen = false
                                            }
                                        }
                                        )
                                    }
                                }
                            }
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = verifyTimeoutText,
                                onValueChange = { verifyTimeoutText = it },
                                label = { Text("Макс. секунд") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = verifyValueText,
                                onValueChange = { verifyValueText = it },
                                label = { Text("Результат должен быть равен") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        OutlinedTextField(
                            value = verifySuccessMessage,
                            onValueChange = { verifySuccessMessage = it },
                            label = { Text("Если подтверждено") },
                            singleLine = true,
                            modifier = Modifier
                        )
                        OutlinedTextField(
                            value = verifyFailureMessage,
                            onValueChange = { verifyFailureMessage = it },
                            label = { Text("Если не подтверждено") },
                            singleLine = true,
                            modifier = Modifier
                        )
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
                    val actions = if (actionType == "MQTT_CONTROL") {
                        actionDrafts.mapNotNull { draft ->
                            if (controls.isEmpty()) null else {
                                val target = controls[draft.selectedIndex.coerceIn(0, controls.lastIndex)]
                                ScenarioAction(target.first.id, target.second.id, draft.value)
                            }
                        }
                    } else emptyList()
                    val firstAction = actions.firstOrNull()
                    onSave(Scenario(
                        id = initialScenario?.id ?: java.util.UUID.randomUUID().toString(),
                        enabled = initialScenario?.enabled ?: true,
                        armed = initialScenario?.armed ?: true,
                        deviceId = first.deviceId,
                        widgetId = first.widgetId,
                        title = title.trim().ifBlank { "Сценарий" },
                        operator = first.operator,
                        threshold = first.threshold,
                        message = message.trim().ifBlank { "{value}" },
                        actionType = actionType,
                        actionDeviceId = firstAction?.deviceId.orEmpty(),
                        actionWidgetId = firstAction?.widgetId.orEmpty(),
                        actionValue = firstAction?.value ?: "1",
                        actions = actions,
                        notificationEnabled = notificationEnabled,
                        verifyEnabled = verifyEnabled,
                        verifyTimeoutSec = verifyTimeoutText.toIntOrNull()?.coerceIn(1, 300) ?: 30,
                        verifyDeviceId = if (verifyEnabled && conditionWidgets.isNotEmpty()) conditionWidgets[verifyTargetIndex.coerceIn(0, conditionWidgets.lastIndex)].first.id else "",
                        verifyWidgetId = if (verifyEnabled && conditionWidgets.isNotEmpty()) conditionWidgets[verifyTargetIndex.coerceIn(0, conditionWidgets.lastIndex)].second.id else "",
                        verifyOperator = "=",
                        verifyValue = verifyValueText.replace(',', '.').toDoubleOrNull() ?: 1.0,
                        verifySuccessMessage = verifySuccessMessage.trim().ifBlank { "Подтверждение получено: {value}" },
                        verifyFailureMessage = verifyFailureMessage.trim().ifBlank { "Подтверждение не получено" },
                        conditions = parsed
                    ))
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

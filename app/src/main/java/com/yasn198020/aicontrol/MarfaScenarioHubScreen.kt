private fun formatDelayForUi(delayMs: Long): String {
    val totalMinutes = delayMs / 60_000L
    if (totalMinutes < 1L) return "менее минуты"
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "$hours ч $minutes мин"
        hours > 0L -> "$hours ч"
        else -> "$minutes мин"
    }
}

package com.yasn198020.aicontrol

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
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
            DeviceScenariosScreen(modifier, deviceScenarioManager, devices)
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
    manager: DeviceScenarioManager,
    devices: List<Device>
) {
    var title by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var sensorIds by remember { mutableStateOf<List<String>>(emptyList()) }
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

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            "Сценарии ESP32 / IoTManager",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Загрузите экспорт IoTManager в JSON. ESP32 продолжает выполнять свой локальный сценарий.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )

        val context = LocalContext.current
        val jsonLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult

            runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalArgumentException("Не удалось прочитать выбранный файл.")

                val fileName = context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "scenario.json"

                val imported = IoTManagerJsonImporter.parse(fileName, raw)
                title = imported.title
                source = imported.source
                sensorIds = imported.sensorIds
                parseMessage = "Файл загружен: " + fileName +
                    "\nID из config: " + imported.sensorIds.size +
                    "\nПравил после разбора: " + manager.parseSource(imported.source).rules.size
            }.onFailure {
                parseMessage = "Ошибка импорта: " + (it.message ?: "неизвестная ошибка")
                title = ""
                source = ""
                sensorIds = emptyList()
            }
        }

        Text(
            "Источник сценария",
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp)
        )
        Button(
            onClick = {
                jsonLauncher.launch(arrayOf("application/json", "text/json", "text/plain", "text/*"))
            },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) {
            Text("Загрузить JSON с телефона")
        }

        if (source.isNotBlank()) {
            Card(
                Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Файл сценария загружен",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Привязка по ID датчиков/виджетов: " + sensorIds.size,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                    Text(
                        sensorIds.take(12).joinToString(", ") +
                            if (sensorIds.size > 12) " …" else "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Название") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            singleLine = true
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
                enabled = source.isNotBlank() && sensorIds.isNotEmpty(),
                onClick = {
                    val result = manager.saveScenario(title, source, sensorIds)
                    val item = result.first
                    if (item == null) {
                        parseMessage = DeviceScenarioModelFormatter.summary(result.second)
                    } else {
                        parseMessage = "Сценарий сохранён. Правил: " + result.second.rules.size +
                            ". Привязано ID: " + sensorIds.size + ". Контроль исполнения включён."
                        title = ""
                        source = ""
                        sensorIds = emptyList()
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
                    sensorIds = emptyList()
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
                                            "ID датчиков/виджетов: " + item.sensorIds.size,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            item.sensorIds.take(8).joinToString(", ") +
                                                if (item.sensorIds.size > 8) " …" else "",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Row(
                                            Modifier.fillMaxWidth().padding(top = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                if (item.enabled) "Контроль включён"
                                                else "Контроль выключен",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            Spacer(Modifier.weight(1f))
                                            Switch(
                                                checked = item.enabled,
                                                onCheckedChange = {
                                                    manager.setEnabled(item.id, it)
                                                    refresh()
                                                }
                                            )
                                        }
                                        Row(
                                            Modifier.fillMaxWidth().padding(top = 8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = { showSavedSource = item.id },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Открыть сценарий")
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    manager.remove(item.id)
                                                    refresh()
                                                    if (showSavedSource == item.id) {
                                                        showSavedSource = null
                                                    }
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Удалить сценарий")
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
            val model = remember(item.id, item.source) { manager.parseSource(item.source) }
            var previewCommand by remember(item.id) { mutableStateOf("") }
            var previewResult by remember(item.id) { mutableStateOf("") }
            val previewEngine = remember(item.id) { MarfaCommandEngine() }

            Dialog(
                onDismissRequest = { showSavedSource = null },
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
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "ID датчиков/виджетов: " + item.sensorIds.size,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    item.sensorIds.take(12).joinToString(", ") +
                                        if (item.sensorIds.size > 12) " …" else "",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            TextButton(onClick = { showSavedSource = null }) {
                                Text("Закрыть")
                            }
                        }

                        val labels = devices
                            .flatMap { device -> device.widgets }
                            .associate { widget -> widget.id to widget.title }
                            .filterKeys { item.sensorIds.contains(it) }
                        val overview = DeviceScenarioModelFormatter.actionOverview(model, labels)

                        val widgetLabels = devices
                            .flatMap { device -> device.widgets }
                            .associate { widget -> widget.id to widget.title }

                        fun actionDescription(action: LocalCommandActionItem): String {
                            val title = widgetLabels[action.widgetId]
                                ?.takeIf { it.isNotBlank() }
                                ?: action.widgetId
                            return when (action.value.trim()) {
                                "1" -> "включить «$title»"
                                "0" -> "выключить «$title»"
                                else -> "установить «$title» = ${action.value}"
                            }
                        }

                        LazyColumn(
                            Modifier.fillMaxSize().padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 16.dp)
                        ) {
                            item {
                                Text(
                                    "Что будет сделано по конкретной команде",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Проверка только показывает план. Команды на устройство не отправляются.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                                OutlinedTextField(
                                    value = previewCommand,
                                    onValueChange = { previewCommand = it },
                                    label = { Text("Например: «открой форточку через 20 минут»") },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    minLines = 2
                                )
                                Button(
                                    enabled = previewCommand.isNotBlank(),
                                    onClick = {
                                        val result = previewEngine.parse(previewCommand, devices)
                                        if (result.action != LocalCommandAction.CONTROL ||
                                            result.deviceId.isBlank() ||
                                            result.widgetId.isBlank()
                                        ) {
                                            previewResult = "Марфа: ${result.reply}"
                                        } else {
                                            val plan = manager.planCommand(result, devices)
                                            previewResult = buildString {
                                                append("Марфа поняла: ")
                                                append(result.reply)
                                                if (result.delayMs > 0L) {
                                                    append("\n\nВыполнение: через ")
                                                    append(formatDelayForUi(result.delayMs))
                                                } else {
                                                    append("\n\nВыполнение: сейчас")
                                                }

                                                if (!plan.blockedReason.isNullOrBlank()) {
                                                    append("\n\nНЕ БУДЕТ ВЫПОЛНЕНО:\n")
                                                    append(plan.blockedReason)
                                                } else {
                                                    if (plan.prerequisites.isNotEmpty()) {
                                                        append("\n\nСНАЧАЛА — ЗАВИСИМОСТИ:")
                                                        plan.prerequisites.forEachIndexed { index, prerequisite ->
                                                            append("\n")
                                                            append(index + 1)
                                                            append(". ")
                                                            append(prerequisite.reason)
                                                        }
                                                    }

                                                    append("\n\nПОРЯДОК ДЕЙСТВИЙ:")
                                                    plan.actions.forEachIndexed { index, action ->
                                                        append("\n")
                                                        append(index + 1)
                                                        append(". ")
                                                        append(actionDescription(action))
                                                    }

                                                    append("\n\nРезультат: команда будет выполнена в указанном порядке.")
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                )
                                if (previewResult.isNotBlank()) {
                                    Card(
                                        Modifier.fillMaxWidth().padding(top = 8.dp)
                                    ) {
                                        SelectionContainer {
                                            Text(
                                                previewResult,
                                                modifier = Modifier.padding(12.dp),
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }
                                }
                            }


                            item {
                                Text(
                                    "Действия устройства",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                if (overview.isNotBlank()) {
                                    Card(Modifier.fillMaxWidth()) {
                                        SelectionContainer {
                                            Text(
                                                overview,
                                                modifier = Modifier.padding(12.dp),
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        "Для действий не найдено достаточно данных названий виджетов.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                Text(
                                    "Дерево логики устройства",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Дерево построено из сохранённого scenario.txt и показывает ветвления и действия.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }

                            item {
                                Card(Modifier.fillMaxWidth()) {
                                    SelectionContainer {
                                        Text(
                                            DeviceScenarioModelFormatter.tree(model, labels),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState())
                                                .padding(12.dp),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }

                            item {
                                Text(
                                    "Сценарий из JSON-файла",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            item {
                                Card(Modifier.fillMaxWidth()) {
                                    SelectionContainer {
                                        Text(
                                            item.source,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState())
                                                .padding(12.dp),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }

                            item {
                                Button(
                                    onClick = {
                                        manager.remove(item.id)
                                        refresh()
                                        showSavedSource = null
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Удалить сценарий")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

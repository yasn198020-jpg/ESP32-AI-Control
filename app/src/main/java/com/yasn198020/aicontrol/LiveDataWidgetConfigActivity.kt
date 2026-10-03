package com.yasn198020.aicontrol

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState
import kotlinx.coroutines.delay

class LiveDataWidgetConfigActivity : ComponentActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        setResult(
            Activity.RESULT_CANCELED,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        )

        runCatching { MqttBackgroundService.start(applicationContext) }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize().statusBarsPadding()
                        .navigationBarsPadding()) {
                    WidgetConfiguration(
                        appWidgetId = appWidgetId,
                        onSaved = {
                            setResult(
                                Activity.RESULT_OK,
                                Intent().putExtra(
                                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                                    appWidgetId
                                )
                            )
                            finish()
                        },
                        onCancel = { finish() }
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetConfiguration(
    appWidgetId: Int,
    onSaved: () -> Unit,
    onCancel: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val runtime = remember { AppRuntime.get(context) }
    val storedSelection = remember { LiveDataWidgetStore.get(context, appWidgetId) }

    var devices by remember { mutableStateOf(runtime.deviceRepository.snapshot()) }
    var selectedDeviceId by rememberSaveable {
        mutableStateOf(storedSelection?.deviceId)
    }
    var selectedWidgetId by rememberSaveable {
        mutableStateOf(storedSelection?.widgetId)
    }
    var widgetSelectionOpen by remember { mutableStateOf(false) }

    var belowColor by rememberSaveable {
        mutableStateOf(storedSelection?.belowColor ?: 0xFF1976D2.toInt())
    }
    var thresholdTexts by rememberSaveable {
        mutableStateOf(
            storedSelection?.thresholds?.map { it.value.toString() }
                ?: listOf("0", "20")
        )
    }
    var thresholdColors by rememberSaveable {
        mutableStateOf(
            storedSelection?.thresholds?.map { it.color }
                ?: listOf(0xFF2E7D32.toInt(), 0xFFD32F2F.toInt())
        )
    }
    var validationError by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            devices = runtime.deviceRepository.snapshot()
            if (selectedDeviceId == null && devices.isNotEmpty()) {
                selectedDeviceId = devices.first().id
            }
            delay(700)
        }
    }

    val availableWidgets = remember(devices) {
        devices
            .flatMap { device ->
                device.widgets
                    .filter { it.type != WidgetState.Type.BUTTON }
                    .map { device to it }
            }
            .sortedWith(
                compareBy<Pair<Device, WidgetState>> { it.second.page.ifBlank { "Основная" } }
                    .thenBy { it.second.order }
                    .thenBy { it.second.title }
                    .thenBy { it.first.id }
            )
    }

    val selectedPair = availableWidgets.firstOrNull {
        it.first.id == selectedDeviceId && it.second.id == selectedWidgetId
    }
    if (selectedPair == null && availableWidgets.isNotEmpty()) {
        val fallback = availableWidgets.first()
        selectedDeviceId = fallback.first.id
        selectedWidgetId = fallback.second.id
    }

    val selectedDevice = devices.firstOrNull { it.id == selectedDeviceId }
    val selectedWidget = selectedPair?.second
        ?: availableWidgets.firstOrNull { it.first.id == selectedDeviceId && it.second.id == selectedWidgetId }?.second

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Настройка виджета MQTT", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Выберите ESP32 и параметр. Значение будет обновляться автоматически при каждом сообщении MQTT.",
            style = MaterialTheme.typography.bodyMedium
        )

        if (availableWidgets.isEmpty()) {
            OutlinedButton(
                onClick = { runtime.ensureConnected() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Ожидание ESP32…") }
        } else {
            Text(
                "Выбор виджета",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            val pages = availableWidgets
                .map { it.second.page.ifBlank { "Основная" } }
                .distinct()

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                pages.forEach { page ->
                    val pageWidgets = availableWidgets.filter {
                        it.second.page.ifBlank { "Основная" } == page
                    }
                    val visiblePageWidgets = pageWidgets.filter { item ->
                        widgetSelectionOpen ||
                            (item.first.id == selectedDeviceId && item.second.id == selectedWidgetId)
                    }

                    if (visiblePageWidgets.isNotEmpty()) {
                        Text(page, fontWeight = FontWeight.Medium)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            visiblePageWidgets.forEach { item ->
                                val selected =
                                    item.first.id == selectedDeviceId && item.second.id == selectedWidgetId
                                val icon = when (item.second.type) {
                                    WidgetState.Type.VALUE,
                                    WidgetState.Type.STATUS -> "🌡"
                                    WidgetState.Type.TOGGLE -> "◉"
                                    else -> "⌨"
                                }

                                Card(
                                    onClick = {
                                        if (selected) {
                                            widgetSelectionOpen = !widgetSelectionOpen
                                        } else {
                                            selectedDeviceId = item.first.id
                                            selectedWidgetId = item.second.id
                                            widgetSelectionOpen = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (selected)
                                            MaterialTheme.colorScheme.primaryContainer
                                        else
                                            MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(
                                            horizontal = 14.dp,
                                            vertical = 10.dp
                                        ),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(icon, fontSize = 22.sp, modifier = Modifier.width(38.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                item.second.title.ifBlank { item.second.id },
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                item.second.id +
                                                    "  •  " + item.first.name.ifBlank { "ESP32" } +
                                                    if (item.second.value.isNotBlank())
                                                        "  •  " + item.second.value + item.second.unit
                                                    else "",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        if (selected) Text("✓", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!widgetSelectionOpen) {
                OutlinedButton(
                    onClick = { widgetSelectionOpen = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("+ Выбрать другой элемент") }
            }
        }

        Text("Цвет по значению", style = MaterialTheme.typography.titleMedium)
        Text(
            "Добавляйте сколько угодно порогов. Цвет ниже первого порога задаётся отдельно, а каждый порог задаёт цвет начиная с этого значения.",
            style = MaterialTheme.typography.bodySmall
        )

        ColorChoice("Ниже первого порога", belowColor) { belowColor = it }

        thresholdTexts.forEachIndexed { index, textValue ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = textValue,
                            onValueChange = {
                                validationError = ""
                                thresholdTexts = thresholdTexts.toMutableList().also { list ->
                                    list[index] = it
                                }
                            },
                            label = { Text("Порог ${index + 1}") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        if (thresholdTexts.size > 1) {
                            OutlinedButton(
                                onClick = {
                                    thresholdTexts = thresholdTexts.toMutableList().also { it.removeAt(index) }
                                    thresholdColors = thresholdColors.toMutableList().also { it.removeAt(index) }
                                    validationError = ""
                                }
                            ) {
                                Text("−")
                            }
                        }
                    }

                    ColorChoice(
                        "Цвет после порога ${index + 1}",
                        thresholdColors.getOrElse(index) { 0xFF2E7D32.toInt() }
                    ) { color ->
                        thresholdColors = thresholdColors.toMutableList().also { list ->
                            if (index < list.size) list[index] = color
                        }
                    }
                }
            }
        }

        OutlinedButton(
            onClick = {
                thresholdTexts = thresholdTexts + "30"
                thresholdColors = thresholdColors + 0xFFF9A825.toInt()
                validationError = ""
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("+ Порог")
        }

        if (validationError.isNotBlank()) {
            Text(
                validationError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Предпросмотр", style = MaterialTheme.typography.titleMedium)
                Text(selectedWidget?.title ?: "—")
                Text(
                    text = selectedWidget?.let {
                        val value = it.value.ifBlank { "ожидание данных" }
                        value + it.unit
                    } ?: "Выберите параметр",
                    style = MaterialTheme.typography.headlineSmall
                )
            }
        }

        if (devices.isEmpty()) {
            HorizontalDivider()
            Text(
                "Нет загруженных ESP32. Проверьте MQTT-подключение и нажмите «Обновить».",
                style = MaterialTheme.typography.bodySmall
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        devices = runtime.deviceRepository.snapshot()
                        runtime.ensureConnected()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Обновить") }

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) { Text("Отмена") }
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) { Text("Отмена") }

                Button(
                    onClick = {
                        val deviceId = selectedDeviceId
                        val widgetId = selectedWidgetId
                        val parsed = thresholdTexts.mapIndexed { index, raw ->
                            raw.replace(',', '.').toFloatOrNull()?.let { value ->
                                LiveDataWidgetStore.Threshold(
                                    value = value,
                                    color = thresholdColors.getOrElse(index) { 0xFF2E7D32.toInt() }
                                )
                            }
                        }

                        when {
                            deviceId.isNullOrBlank() || widgetId.isNullOrBlank() ->
                                validationError = "Выберите ESP32 и параметр"
                            parsed.any { it == null } ->
                                validationError = "Все пороги должны быть числами"
                            parsed.zipWithNext().any { (a, b) -> a!!.value >= b!!.value } ->
                                validationError = "Пороги должны идти строго по возрастанию"
                            else -> {
                                LiveDataWidgetStore.save(
                                    context = context,
                                    appWidgetId = appWidgetId,
                                    deviceId = deviceId,
                                    widgetId = widgetId,
                                    thresholds = parsed.filterNotNull(),
                                    belowColor = belowColor
                                )
                                LiveDataWidgetProvider.updateOne(context, appWidgetId)
                                onSaved()
                            }
                        }
                    },
                    enabled = selectedDevice != null && selectedWidget != null,
                    modifier = Modifier.weight(1f)
                ) { Text("Сохранить") }
            }
        }
    }
}

private fun deviceLabel(device: Device): String = device.name.ifBlank { "ESP32" }

private fun widgetLabel(widget: WidgetState): String =
    widget.title.ifBlank { widget.id }

@Composable
private fun ColorChoice(label: String, selected: Int, onSelected: (Int) -> Unit) {
    val colors = listOf(
        "Синий" to 0xFF1976D2.toInt(),
        "Зелёный" to 0xFF2E7D32.toInt(),
        "Красный" to 0xFFD32F2F.toInt(),
        "Жёлтый" to 0xFFF9A825.toInt(),
        "Оранжевый" to 0xFFEF6C00.toInt(),
        "Фиолетовый" to 0xFF7B1FA2.toInt()
    )
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label + ": " + (colors.firstOrNull { it.second == selected }?.first ?: "Цвет"))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            colors.forEach { (name, color) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelected(color)
                        open = false
                    }
                )
            }
        }
    }
}
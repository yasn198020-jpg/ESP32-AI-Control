package com.yasn198020.aicontrol

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState
import kotlinx.coroutines.delay

class LiveDataWidgetControlActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { setResult(Activity.RESULT_CANCELED); finish(); return }
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize().statusBarsPadding()
                        .navigationBarsPadding()) { ControlPanel(appWidgetId) { finish() } } } }
    }
}

@Composable
private fun ControlPanel(appWidgetId: Int, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val runtime = remember { AppRuntime.get(context) }
    var devices by remember { mutableStateOf(runtime.deviceRepository.snapshot()) }
    var controls by remember { mutableStateOf(LiveDataWidgetStore.getControls(context, appWidgetId)) }
    var addSelectionOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { devices = runtime.deviceRepository.snapshot(); delay(700) } }
    fun findWidget(c: LiveDataWidgetStore.Control): Pair<Device, WidgetState>? {
        val device = devices.firstOrNull { it.id == c.deviceId } ?: return null
        val widget = device.widgets.firstOrNull { it.id == c.widgetId } ?: return null
        return device to widget
    }
    val available = devices.flatMap { device ->
        device.widgets.filter { it.type == WidgetState.Type.TOGGLE || it.type == WidgetState.Type.BUTTON || it.type == WidgetState.Type.INPUT }
            .map { widget -> device to widget }
    }.filterNot { (device, widget) -> controls.any { it.deviceId == device.id && it.widgetId == widget.id } }
        .sortedWith(
            compareBy<Pair<Device, WidgetState>> { it.second.page.ifBlank { "Основная" } }
                .thenBy { it.second.order }
                .thenBy { it.second.title }
                .thenBy { it.first.id }
        )
    fun send(device: Device, widget: WidgetState, value: String) {
        val published = when (widget.type) {
            WidgetState.Type.TOGGLE, WidgetState.Type.BUTTON -> runtime.mqtt.publishControl(device.id, widget.id, value)
            else -> if (widget.topic.isNotBlank()) runtime.mqtt.publishWidget(widget.topic, value) else false
        }
        if (published) { runtime.deviceRepository.setLocalValue(device.id, widget.id, value); message = "Отправлено: " + widget.title }
        else message = "Не удалось отправить: " + widget.title
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Управление виджетом", style = MaterialTheme.typography.headlineSmall)
        Text("Здесь можно добавлять дополнительные элементы управления. Они сохраняются отдельно для этого Android-виджета.")
        val selection = LiveDataWidgetStore.get(context, appWidgetId)
        val selectedDevice = selection?.let { s -> devices.firstOrNull { it.id == s.deviceId } }
        val selectedValue = selection?.let { s -> selectedDevice?.widgets?.firstOrNull { it.id == s.widgetId } }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Основной параметр", style = MaterialTheme.typography.titleMedium)
            Text(selectedValue?.title ?: "Не настроен")
            Text((selectedValue?.value ?: "—") + (selectedValue?.unit ?: ""), fontSize = 24.sp)
        } }
        Text("Поле управления", style = MaterialTheme.typography.titleMedium)
        controls.toList().forEach { control ->
            val found = findWidget(control)
            if (found == null) {
                Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Элемент недоступен", modifier = Modifier.weight(1f))
                    TextButton(onClick = { controls = controls.filterNot { it == control }; LiveDataWidgetStore.saveControls(context, appWidgetId, controls) }) { Text("Удалить") }
                } }
            } else {
                val (device, widget) = found
                ControlRow(device, widget, { value -> send(device, widget, value) }, { controls = controls.filterNot { it == control }; LiveDataWidgetStore.saveControls(context, appWidgetId, controls) })
            }
        }
        OutlinedButton(
            onClick = { addSelectionOpen = !addSelectionOpen },
            modifier = Modifier.fillMaxWidth(),
            enabled = available.isNotEmpty()
        ) { Text(if (addSelectionOpen) "Закрыть выбор" else "+ Добавить элемент управления") }

        if (addSelectionOpen && available.isNotEmpty()) {
            Text("Выбор элемента", style = MaterialTheme.typography.titleMedium)
            val pages = available.map { it.second.page.ifBlank { "Основная" } }.distinct()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                pages.forEach { page ->
                    val pageWidgets = available.filter { it.second.page.ifBlank { "Основная" } == page }
                    Text(page, fontWeight = FontWeight.Medium)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        pageWidgets.forEach { (device, widget) ->
                            val icon = when (widget.type) {
                                WidgetState.Type.TOGGLE, WidgetState.Type.BUTTON -> "◉"
                                WidgetState.Type.INPUT -> "⌨"
                                else -> ""
                            }
                            Card(
                                onClick = {
                                    val newControl = LiveDataWidgetStore.Control(device.id, widget.id)
                                    if (!controls.contains(newControl)) {
                                        controls = controls + newControl
                                        LiveDataWidgetStore.saveControls(context, appWidgetId, controls)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(icon, fontSize = 22.sp, modifier = Modifier.width(38.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            widget.title.ifBlank { widget.id },
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            widget.id + "  •  " + device.name.ifBlank { "ESP32" },
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (available.isEmpty() && controls.isEmpty()) Text("Нет доступных элементов управления. Нужны переключатели, кнопки или поля ввода из конфигурации ESP32.", style = MaterialTheme.typography.bodySmall)
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Закрыть") }
    }
}

@Composable
private fun ControlRow(device: Device, widget: WidgetState, onSend: (String) -> Unit, onRemove: () -> Unit) {
    var text by remember(widget.id, widget.value) { mutableStateOf(widget.value) }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) { Column(Modifier.weight(1f)) { Text(widget.title.ifBlank { widget.id }, style = MaterialTheme.typography.titleMedium); Text(device.name.ifBlank { "ESP32" }, style = MaterialTheme.typography.bodySmall) }; TextButton(onClick = onRemove) { Text("Удалить") } }
        when (widget.type) {
            WidgetState.Type.TOGGLE -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Включить / выключить"); Switch(checked = widget.value == "1" || widget.value.equals("true", true), onCheckedChange = { onSend(if (it) "1" else "0") }) }
            WidgetState.Type.BUTTON -> Button(onClick = { onSend("1") }, modifier = Modifier.fillMaxWidth()) { Text("Нажать") }
            WidgetState.Type.INPUT -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Значение") }, singleLine = true, modifier = Modifier.weight(1f)); Button(onClick = { onSend(text) }) { Text("Отправить") } }
            else -> Unit
        }
    } }
}

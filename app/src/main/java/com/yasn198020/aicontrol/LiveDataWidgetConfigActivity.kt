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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
                Surface(Modifier.fillMaxSize()) {
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

    var devices by remember { mutableStateOf(runtime.deviceRepository.snapshot()) }
    var selectedDeviceId by rememberSaveable {
        mutableStateOf(LiveDataWidgetStore.get(context, appWidgetId)?.deviceId)
    }
    var selectedWidgetId by rememberSaveable {
        mutableStateOf(LiveDataWidgetStore.get(context, appWidgetId)?.widgetId)
    }
    var deviceMenuOpen by remember { mutableStateOf(false) }
    var widgetMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            devices = runtime.deviceRepository.snapshot()
            if (selectedDeviceId == null && devices.isNotEmpty()) {
                selectedDeviceId = devices.first().id
            }
            delay(700)
        }
    }

    val selectedDevice = devices.firstOrNull { it.id == selectedDeviceId }
    val availableWidgets = selectedDevice?.widgets
        ?.filter { it.type != WidgetState.Type.BUTTON }
        ?.sortedWith(compareBy<WidgetState> { it.order }.thenBy { it.title })
        ?: emptyList()

    if (selectedWidgetId != null && availableWidgets.none { it.id == selectedWidgetId }) {
        selectedWidgetId = availableWidgets.firstOrNull()?.id
    }

    val selectedWidget = availableWidgets.firstOrNull { it.id == selectedWidgetId }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Настройка виджета MQTT", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Выберите ESP32 и параметр. Значение будет обновляться автоматически при каждом сообщении MQTT.",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedButton(
            onClick = { deviceMenuOpen = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = devices.isNotEmpty()
        ) {
            Text(
                selectedDevice?.let { deviceLabel(it) }
                    ?: if (devices.isEmpty()) "Ожидание ESP32…" else "Выберите ESP32"
            )
        }

        DropdownMenu(
            expanded = deviceMenuOpen,
            onDismissRequest = { deviceMenuOpen = false }
        ) {
            devices.forEach { device ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(deviceLabel(device)) },
                    onClick = {
                        selectedDeviceId = device.id
                        selectedWidgetId = null
                        deviceMenuOpen = false
                    }
                )
            }
        }

        OutlinedButton(
            onClick = { widgetMenuOpen = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = availableWidgets.isNotEmpty()
        ) {
            Text(
                selectedWidget?.let { widgetLabel(it) }
                    ?: if (selectedDevice == null) "Сначала выберите ESP32" else "Выберите параметр"
            )
        }

        DropdownMenu(
            expanded = widgetMenuOpen,
            onDismissRequest = { widgetMenuOpen = false }
        ) {
            availableWidgets.forEach { widget ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(
                            text = widgetLabel(widget) +
                                if (widget.unit.isNotBlank()) "  [" + widget.unit + "]" else ""
                        )
                    },
                    onClick = {
                        selectedWidgetId = widget.id
                        widgetMenuOpen = false
                    }
                )
            }
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
                        val deviceId = selectedDeviceId ?: return@Button
                        val widgetId = selectedWidgetId ?: return@Button
                        LiveDataWidgetStore.save(context, appWidgetId, deviceId, widgetId)
                        LiveDataWidgetProvider.updateOne(context, appWidgetId)
                        onSaved()
                    },
                    enabled = selectedDevice != null && selectedWidget != null,
                    modifier = Modifier.weight(1f)
                ) { Text("Сохранить") }
            }
        }
    }
}

private fun deviceLabel(device: Device): String =
    device.name.ifBlank { device.id } + "  •  " + device.id

private fun widgetLabel(widget: WidgetState): String =
    widget.title.ifBlank { widget.id }

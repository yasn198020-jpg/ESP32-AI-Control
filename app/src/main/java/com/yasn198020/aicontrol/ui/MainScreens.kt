package com.yasn198020.aicontrol.ui

import com.yasn198020.aicontrol.mqtt.*
import com.yasn198020.aicontrol.voice.*
import com.yasn198020.aicontrol.commands.*
import com.yasn198020.aicontrol.scenarios.*
import com.yasn198020.aicontrol.history.*
import com.yasn198020.aicontrol.updates.*
import com.yasn198020.aicontrol.marfa.*

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.os.Bundle
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState
import com.yasn198020.aicontrol.devices.DeviceManager

import com.yasn198020.aicontrol.*

@Composable
fun DashboardPageTabs(devices: List<Device>, selectedPage: String?, onSelect: (String) -> Unit) {
    val pages = devices
        .flatMap { it.widgets.map { w -> w.page.ifBlank { "Основная" } } }
        .distinct()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        pages.forEachIndexed { index, page ->
            val selected = selectedPage == page || (selectedPage == null && index == 0)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (selected) Color(0xFF626262) else Color(0xFF303030),
                modifier = Modifier.padding(end = 2.dp)
            ) {
                Text(
                    text = page,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable { onSelect(page) }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
fun DevicesScreen(
    modifier: Modifier,
    devices: List<Device>,
    selectedPage: String?,
    voiceText: String,
    voiceStatus: String,
    onTrain: (String, WidgetState) -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit,
    onSend: (String, String, String) -> Unit
) {
    val pages = devices
        .flatMap { it.widgets.map { it.page.ifBlank { "Основная" } } }
        .distinct()

    val activePage = selectedPage ?: pages.firstOrNull() ?: "Основная"
    val entries = devices
        .flatMap { device -> device.widgets.map { device.id to it } }
        .filter { (_, widget) -> widget.page.ifBlank { "Основная" } == activePage }
        .sortedWith(compareBy<Pair<String, WidgetState>> { it.second.order }.thenBy { it.second.title })

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (entries.isEmpty()) {
            item {
                Text(
                    "Нет конфигурации. Подключитесь к MQTT и нажмите HELLO.",
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        items(
            items = entries,
            key = { it.first + "/" + it.second.id }
        ) { (deviceId, widget) ->
            DashboardWidgetRow(widget, onSend = { value -> onSend(deviceId, widget.id, value) }, onTrain = { onTrain(deviceId, widget) })
        }

        item(key = "voice-status") {
            if (voiceText.isNotBlank()) {
                Text(
                    "«$voiceText»  •  $voiceStatus",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DashboardWidgetRow(
    widget: WidgetState,
    onSend: (String) -> Unit,
    onTrain: () -> Unit
) {
    when (widget.type) {
        WidgetState.Type.INPUT -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 6.dp)
                    .combinedClickable(onLongClick = onTrain, onClick = { }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "⌨",
                    color = Color(0xFF8065E8),
                    fontSize = 22.sp,
                    modifier = Modifier.width(44.dp)
                )
                InputWidget(
                    widget = widget,
                    onSend = onSend
                )
            }
        }

        WidgetState.Type.VALUE, WidgetState.Type.STATUS -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .padding(horizontal = 22.dp)
                    .combinedClickable(onLongClick = onTrain, onClick = { }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🌡", fontSize = 22.sp, modifier = Modifier.width(34.dp))
                Text(
                    widget.title,
                    fontSize = 19.sp,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    modifier = Modifier
                        .width(86.dp)
                        .height(64.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFF4285F4)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            widget.value.ifBlank { "—" } +
                                if (widget.unit.isNotBlank()) " " + widget.unit else "",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        WidgetState.Type.BUTTON -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(66.dp)
                    .padding(horizontal = 22.dp)
                    .combinedClickable(onLongClick = onTrain, onClick = { }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "◉",
                    color = Color(0xFF8065E8),
                    fontSize = 22.sp,
                    modifier = Modifier.width(44.dp)
                )
                Text(
                    widget.title,
                    fontSize = 19.sp,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { onSend("1") }) {
                    Text("Нажать")
                }
            }
        }

        WidgetState.Type.TOGGLE -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(66.dp)
                    .padding(horizontal = 22.dp)
                    .combinedClickable(onLongClick = onTrain, onClick = { }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "◉",
                    color = Color(0xFF8065E8),
                    fontSize = 22.sp,
                    modifier = Modifier.width(44.dp)
                )
                Text(
                    widget.title,
                    fontSize = 19.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = widget.value == "1" || widget.value.equals("true", true),
                    onCheckedChange = { checked ->
                        onSend(if (checked) "1" else "0")
                    }
                )
            }
        }
    }
}

@Composable
fun TrainedCommandsScreen(modifier: Modifier, trainedCommands: List<TrainedVoiceCommand>, devices: List<Device>, onDelete: (TrainedVoiceCommand) -> Unit, onClearAll: () -> Unit, onAddVariant: (String) -> Unit) {
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Очистить обученные команды?") },
            text = { Text("Будут удалены все сохранённые фразы и их варианты. Настройки MQTT и другие данные приложения не изменятся.") },
            confirmButton = { TextButton(onClick = { onClearAll(); showClearDialog = false }) { Text("Удалить всё") } },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("Отмена") } }
        )
    }
    if (trainedCommands.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            Text("Пока нет обученных команд.\n\nЗажмите переключатель виджета на главном экране и запишите фразу.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Записанные команды: " + trainedCommands.size,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedButton(onClick = { showClearDialog = true }) { Text("Очистить всё") }
            }
        }
        items(items = trainedCommands, key = { it.phrase + "|" + it.deviceId + "|" + it.widgetId + "|" + it.value }) { command ->
            val device = devices.firstOrNull { it.id == command.deviceId }
            val widget = device?.widgets?.firstOrNull { it.id == command.widgetId }
            val deviceName = device?.name?.ifBlank { device.id } ?: command.deviceId
            val widgetName = widget?.title?.ifBlank { widget.id } ?: command.widgetId
            val action = if (command.value == TRAINED_READ_VALUE) "ПРОИЗНЕСТИ ЗНАЧЕНИЕ" else if (command.value == "1") "ВКЛ / ОТКРЫТЬ" else "ВЫКЛ / ЗАКРЫТЬ"
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("«" + command.phrase + "»", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(5.dp)); Text(deviceName + "  •  " + widgetName); Text(action, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                onAddVariant(command.phrase)
                            }
                        ) {
                            Text("➕ Добавить вариант фразы")
                        }
                    }
                    IconButton(onClick = { onDelete(command) }) { Text("🗑", fontSize = 22.sp) }
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
fun MqttScreen(modifier: Modifier, host: String, port: String, prefix: String, username: String, password: String, tls: Boolean, connected: Boolean,
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
        Text(if (connected) "●  MQTT: connected" else "○  MQTT: disconnected", fontWeight = FontWeight.SemiBold)
        Text("MQTT: " + host + ":" + port)

        HorizontalDivider()
        Text("Голосовое управление работает локально", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Команда анализируется прямо в APK по реальным виджетам MQTT. OpenAI API и интернет для анализа команды не нужны.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun VoiceSettingsScreen(
    modifier: Modifier,
    preset: String,
    rate: Float,
    pitch: Float,
    voices: List<android.speech.tts.Voice>,
    selectedVoiceName: String,
    onPreset: (String) -> Unit,
    onRate: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onVoice: (String) -> Unit,
    onSave: () -> Unit,
    onPreview: (String) -> Unit
) {
    var voiceMenuOpen by remember { mutableStateOf(false) }

    fun languageLabel(locale: Locale): String {
        val language = locale.getDisplayLanguage(Locale("ru", "RU")).ifBlank { locale.language }
        val country = locale.getDisplayCountry(Locale("ru", "RU"))
        return if (country.isBlank()) language else "$language — $country"
    }

    fun voiceLabel(voice: android.speech.tts.Voice): String = voice.name

    val selectedVoice = voices.firstOrNull { it.name == selectedVoiceName }
    val russianVoices = voices.filter { it.locale.language == "ru" }
    val voicesByLanguage = voices.groupBy { languageLabel(it.locale) }
        .toSortedMap(compareBy { it.lowercase(Locale.ROOT) })

    Column(
        modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Голос", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("Выберите голос из установленных на телефоне.")

        Text("Установленный голос", fontWeight = FontWeight.Medium)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { voiceMenuOpen = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (selectedVoice != null) voiceLabel(selectedVoice)
                    else if (voices.isEmpty()) "Загрузка голосов…"
                    else "Выберите голос"
                )
            }
            DropdownMenu(
                expanded = voiceMenuOpen,
                onDismissRequest = { voiceMenuOpen = false },
                modifier = Modifier.fillMaxWidth(0.92f)
            ) {
                voicesByLanguage.forEach { (language, languageVoices) ->
                    DropdownMenuItem(
                        text = { Text(language, fontWeight = FontWeight.Bold) },
                        onClick = { }
                    )
                    languageVoices.sortedBy { it.name }.forEach { voice ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(voiceLabel(voice), fontWeight = FontWeight.Medium)
                                    Text(
                                        "${voice.locale.toLanguageTag()} • качество ${voice.quality} • задержка ${voice.latency}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            },
                            onClick = {
                                onVoice(voice.name)
                                voiceMenuOpen = false
                            }
                        )
                    }
                    HorizontalDivider()
                }
            }
        }

        if (voices.isNotEmpty()) {
            Text(
                "Доступно: ${voices.size}, русских: ${russianVoices.size}",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Text("Пресет", fontWeight = FontWeight.Medium)
        val presets = listOf(
            Triple("soft", "🌸 Нежный", "Мягкий и спокойный"),
            Triple("friendly", "😊 Дружелюбный", "Тёплый и естественный"),
            Triple("natural", "🎧 Естественный", "Более нейтральный"),
            Triple("assistant", "🤖 Ассистент", "Чёткий и спокойный")
        )
        presets.forEach { (id, title, description) ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onPreset(id) },
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = preset == id, onClick = { onPreset(id) })
                    Column(Modifier.weight(1f)) {
                        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Text(description, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onPreset(id); onPreview("Здравствуйте! Я готова помочь.") }) { Text("▶") }
                }
            }
        }

        Text("Скорость: " + String.format(Locale.US, "%.2f", rate), fontWeight = FontWeight.Medium)
        Slider(value = rate, onValueChange = onRate, valueRange = 0.75f..1.15f)

        Text("Высота голоса: " + String.format(Locale.US, "%.2f", pitch), fontWeight = FontWeight.Medium)
        Slider(value = pitch, onValueChange = onPitch, valueRange = 0.85f..1.25f)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onPreview("Температура для помидоров: ${formatTemperatureForSpeech("24.5", "°C")}") }, modifier = Modifier.weight(1f)) {
                Text("▶ Проверить")
            }
            Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("Сохранить") }
        }
    }
}

@Composable
fun LogScreen(modifier: Modifier, log: List<String>, onClear: () -> Unit) {
    val context = LocalContext.current
    val logText = remember(log) { log.joinToString("\n") }

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Журнал  •  " + log.size, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClear) {
                    Text("Очистить")
                }
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MQTT Log", logText))
                }) {
                    Text("Копировать")
                }
            }
        }
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(log) { Text(it) }
            }
        }
    }
}
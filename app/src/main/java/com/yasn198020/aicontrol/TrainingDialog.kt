package com.yasn198020.aicontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yasn198020.aicontrol.voice.TRAINED_READ_VALUE
import com.yasn198020.aicontrol.voice.TrainedVoiceCommand
import com.yasn198020.aicontrol.voice.TrainingTarget

@Composable
fun TrainingDialog(
    target: TrainingTarget?,
    trainedCommands: List<TrainedVoiceCommand>,
    trainingValue: String,
    trainingPhrase: String,
    attachToExisting: Boolean,
    selectedExistingPhrase: String?,
    onAttachChange: (Boolean) -> Unit,
    onSelectPhrase: (String?) -> Unit,
    onTrainingValueChange: (String) -> Unit,
    onRequestMic: () -> Unit,
    onSaveExisting: () -> Unit,
    onDismiss: () -> Unit
) {
    // A null target means the dialog is closed.
    target ?: return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Обучить голосовую команду") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Виджет: ${target.title}")
                Text("Что должна делать фраза?")
                Row(\n                    horizontalArrangement = Arrangement.spacedBy(8.dp)\n                ) {
                    FilterChip(selected = !attachToExisting, onClick = { onAttachChange(false) }, label = { Text("Новая команда") })
                    FilterChip(selected = attachToExisting, onClick = { onAttachChange(true) }, label = { Text("К существующей") })
                }
                if (attachToExisting) {
                    Text("Выберите существующую команду:")
                    val existingPhrases = remember(trainedCommands) { trainedCommands.map { it.phrase }.distinct() }
                    if (existingPhrases.isEmpty()) Text("Существующих команд пока нет.")
                    else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            existingPhrases.forEach { phrase ->
                                FilterChip(selected = selectedExistingPhrase == phrase, onClick = { onSelectPhrase(phrase) }, label = { Text("«$phrase»") }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        Button(onClick = onSaveExisting, enabled = selectedExistingPhrase != null, modifier = Modifier.fillMaxWidth()) { Text("➕ Добавить действие к этой команде") }
                    }
                }
                if (trainingValue == TRAINED_READ_VALUE) {
                    Text("Эта фраза будет читать текущее значение виджета вслух. Например: «Какая температура в помидорах?» → приложение скажет текущее значение этого датчика.")
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = trainingValue == "1", onClick = { onTrainingValueChange("1") }, label = { Text("Открыть / включить") })
                        FilterChip(selected = trainingValue == "0", onClick = { onTrainingValueChange("0") }, label = { Text("Закрыть / выключить") })
                    }
                    Text("Одну и ту же фразу можно записать для нескольких виджетов. Например, для «Доброе утро» обучите свет и шторы отдельно — при произнесении сработают оба действия.")
                }
                if (!attachToExisting) {
                    Text("Нажмите микрофон и произнесите фразу.")
                    Button(onClick = onRequestMic, modifier = Modifier.fillMaxWidth()) { Text("🎤 Записать фразу") }
                    if (trainingPhrase.isNotBlank()) Text("Распознано: $trainingPhrase")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } }
    )
}

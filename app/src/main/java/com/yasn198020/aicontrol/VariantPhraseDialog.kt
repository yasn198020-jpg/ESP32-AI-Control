package com.yasn198020.aicontrol

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun VariantPhraseDialog(
    phrase: String?,
    text: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onRequestMic: () -> Unit,
    onAdd: (String) -> Unit
) {
    phrase ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить вариант фразы") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Команда: «$phrase»")
                Text("Произнесите или введите другой вариант этой команды.")
                OutlinedTextField(value = text, onValueChange = onTextChange, label = { Text("Новый вариант") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(onClick = onRequestMic, modifier = Modifier.fillMaxWidth()) { Text("🎤 Произнести вариант") }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(text.trim()) }, enabled = text.trim().isNotBlank()) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

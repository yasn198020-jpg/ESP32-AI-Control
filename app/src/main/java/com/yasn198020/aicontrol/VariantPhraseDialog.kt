package com.yasn198020.aicontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun VariantPhraseDialog(
    phrase: String?,
    text: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onRequestMic: () -> Unit,
    onAdd: (String) -> Unit
) {
    // A null target means the dialog is closed.
    phrase ?: return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить вариант фразы") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Команда: «$phrase»")
                Text("Произнесите или введите другой вариант этой команды.")
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    label = { Text("Новый вариант") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(
                    onClick = onRequestMic,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("🎤 Произнести вариант") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(text.trim()) },
                enabled = text.trim().isNotBlank()
            ) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

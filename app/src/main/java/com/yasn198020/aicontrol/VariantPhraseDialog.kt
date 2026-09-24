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
                OutlinedTextField(\n                    value = text,\n                    onValueChange = onTextChange,\n                    label = { Text("Новый вариант") },\n                    modifier = Modifier.fillMaxWidth(),\n                    singleLine = true\n                )
                Button(\n                    onClick = onRequestMic,\n                    modifier = Modifier.fillMaxWidth()\n                ) { Text("🎤 Произнести вариант") }
            }
        },
        confirmButton = {\n            TextButton(\n                onClick = { onAdd(text.trim()) },\n                enabled = text.trim().isNotBlank()\n            ) { Text("Добавить") }\n        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

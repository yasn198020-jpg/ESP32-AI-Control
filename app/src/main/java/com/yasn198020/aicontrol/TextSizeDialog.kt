package com.yasn198020.aicontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TextSizeDialog(
    open: Boolean,
    fontScale: Float,
    onFontScaleChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    if (!open) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Размер текста") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("${(fontScale * 100f).toInt()}%", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Slider(
                    value = fontScale,
                    onValueChange = onFontScaleChange,
                    valueRange = 0.70f..1.10f,
                    steps = 7
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Мельче")
                    Text("Обычный")
                    Text("Крупнее")
                }
                Text("Настройка применяется ко всему тексту приложения и сохраняется автоматически.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        dismissButton = {
            TextButton(onClick = { onFontScaleChange(0.85f) }) { Text("По умолчанию") }
        }
    )
}

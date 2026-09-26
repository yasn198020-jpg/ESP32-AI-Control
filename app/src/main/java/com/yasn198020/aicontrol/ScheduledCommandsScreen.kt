package com.yasn198020.aicontrol

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScheduledCommandsScreen(
    modifier: Modifier = Modifier,
    context: Context
) {
    var items by remember { mutableStateOf(emptyList<ScheduledCommand>()) }

    fun refresh() {
        items = ScheduledCommandStore.all(context)
            .sortedWith(
                compareBy<ScheduledCommand> {
                    if (it.status == ScheduledCommand.STATUS_WAITING) 0 else 1
                }.thenBy { it.executeAtMillis }
            )
    }

    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            delay(750)
        }
    }

    val waitingCount = items.count { it.status == ScheduledCommand.STATUS_WAITING }
    val finishedCount = items.count {
        it.status == ScheduledCommand.STATUS_DONE ||
            it.status == ScheduledCommand.STATUS_ERROR ||
            it.status == ScheduledCommand.STATUS_CANCELLED
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Запланированные задачи",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "Ожидают: $waitingCount  •  Завершено: $finishedCount",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            OutlinedButton(onClick = ::refresh) {
                Text("Обновить")
            }
        }

        HorizontalDivider()

        if (items.isEmpty()) {
            Text(
                text = "Запланированных задач нет.",
                modifier = Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodyLarge
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items, key = { it.id }) { item ->
                ScheduledCommandCard(
                    item = item,
                    onCancel = {
                        ScheduledCommandScheduler.cancel(context, item.id)
                        refresh()
                    },
                    onDelete = {
                        ScheduledCommandScheduler.cancelAlarm(context, item.id)
                        ScheduledCommandStore.remove(context, item.id)
                        refresh()
                    }
                )
            }
        }
    }
}

@Composable
private fun ScheduledCommandCard(
    item: ScheduledCommand,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val statusText = when (item.status) {
        ScheduledCommand.STATUS_WAITING -> "ОЖИДАЕТ"
        ScheduledCommand.STATUS_DONE -> "ВЫПОЛНЕНО"
        ScheduledCommand.STATUS_ERROR -> "ОШИБКА"
        ScheduledCommand.STATUS_CANCELLED -> "ОТМЕНЕНО"
        else -> item.status
    }

    val statusColor = when (item.status) {
        ScheduledCommand.STATUS_WAITING -> Color(0xFFFFC107)
        ScheduledCommand.STATUS_DONE -> Color(0xFF4CAF50)
        ScheduledCommand.STATUS_ERROR -> Color(0xFFF44336)
        ScheduledCommand.STATUS_CANCELLED -> Color(0xFF9E9E9E)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()) }

    val executeAt = remember(item.executeAtMillis) {
        dateFormat.format(Date(item.executeAtMillis))
    }

    val createdAt = remember(item.createdAtMillis) {
        dateFormat.format(Date(item.createdAtMillis))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2B2C31)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.title.ifBlank { item.widgetId },
                    modifier = Modifier.weight(1f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text("Выполнить: $executeAt", fontWeight = FontWeight.Medium)
            Text("Устройство: ${item.deviceId}", style = MaterialTheme.typography.bodySmall)
            Text("Виджет: ${item.widgetId}", style = MaterialTheme.typography.bodySmall)
            Text("Значение: ${item.value}", style = MaterialTheme.typography.bodySmall)

            if (item.sourceText.isNotBlank()) {
                Text(
                    text = "Команда: «${item.sourceText}»",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text(
                text = "Создано: $createdAt  •  Попыток: ${item.attempts}",
                style = MaterialTheme.typography.bodySmall
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (item.status == ScheduledCommand.STATUS_WAITING) {
                    Button(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Отменить")
                    }
                }

                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Удалить")
                }
            }
        }
    }
}

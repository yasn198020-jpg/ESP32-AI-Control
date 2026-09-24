package com.yasn198020.aicontrol

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import com.yasn198020.aicontrol.updates.UpdateManager

@Composable
fun UpdateDialog(
    context: Context,
    open: Boolean,
    status: String?,
    downloading: Boolean,
    latestApkUrl: String?,
    latestReleaseUrl: String?,
    onDismiss: () -> Unit,
    onDownloading: () -> Unit,
    onStatus: (String) -> Unit,
    onFinished: (String) -> Unit
) {
    // Keep update actions local to this dialog; download state is controlled by the caller.
    if (!open || status == null) return

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("Обновление приложения") },
        text = { Text(status) },
        confirmButton = {
            when {
                downloading -> TextButton(onClick = { }) { Text("Скачивание…") }
                latestApkUrl != null -> TextButton(onClick = {
                    onDownloading()
                    onStatus("Скачиваю новую версию…")
                    UpdateManager.downloadAndInstall(context, latestApkUrl) { message ->
                        onFinished(message)
                    }
                }) { Text("Обновить") }
                latestReleaseUrl != null -> TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(latestReleaseUrl)))
                    onDismiss()
                }) { Text("Открыть загрузку") }
                else -> TextButton(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = {
            if (!downloading) TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

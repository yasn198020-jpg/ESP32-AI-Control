package com.yasn198020.aicontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

class UpdateCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            File(context.cacheDir, "updates/ESP32-AI-Control-update.apk").delete()
            File(context.cacheDir, "updates").listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".apk", ignoreCase = true)) {
                    file.delete()
                }
            }
        }
    }
}

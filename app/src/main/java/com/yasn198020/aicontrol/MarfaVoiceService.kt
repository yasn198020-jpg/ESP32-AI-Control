package com.yasn198020.aicontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class MarfaVoiceService : Service() {

    companion object {
        const val ACTION_VOICE_RESULT = "com.yasn198020.aicontrol.action.MARFA_VOICE_RESULT"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL_ID = "marfa_voice"
        private const val NOTIFICATION_ID = 1202
    }

    private var voiceManager: VoiceCommandManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground()

        getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("marfa_voice_active", true).apply()
        MarfaShortcutInstaller.setActive(this, true)

        voiceManager = VoiceCommandManager(
            context = this,
            onResult = { text ->
                if (text.isNotBlank()) {
                    sendBroadcast(
                        Intent(ACTION_VOICE_RESULT)
                            .setPackage(packageName)
                            .putExtra(EXTRA_TEXT, text)
                    )
                }
            },
            onStatus = { status ->
                android.util.Log.d("MARFA_VOICE", status)
            }
        )

        voiceManager?.startWakeWord()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        voiceManager?.startWakeWord()
        return START_STICKY
    }

    override fun onDestroy() {
        voiceManager?.stop()
        voiceManager = null
        getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("marfa_voice_active", false).apply()
        MarfaShortcutInstaller.setActive(this, false)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Марфа",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Голосовая активация Марфы"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startAsForeground() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle("🎙 Марфа")
            .setContentText("Марфа слушает команды")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

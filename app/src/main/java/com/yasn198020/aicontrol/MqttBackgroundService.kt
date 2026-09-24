package com.yasn198020.aicontrol

/**
 * Legacy background service placeholder.
 *
 * Old foreground/background MQTT handoff has been removed.
 * A new single-owner background runtime will replace this service.
 */
class MqttBackgroundService : android.app.Service() {
    override fun onStartCommand(
        intent: android.content.Intent?,
        flags: Int,
        startId: Int
    ): Int = START_NOT_STICKY

    override fun onBind(intent: android.content.Intent?): android.os.IBinder? = null
}

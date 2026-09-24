package com.yasn198020.aicontrol

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yasn198020.aicontrol.mqtt.MqttBackgroundService
import com.yasn198020.aicontrol.mqtt.MqttManager
import com.yasn198020.aicontrol.voice.VoiceCommandManager
import kotlinx.coroutines.delay

@Composable
fun AppLifecycleEffects(
    context: Context,
    mqtt: MqttManager,
    voiceManager: VoiceCommandManager,
    speech: android.speech.tts.TextToSpeech,
    manualMqttDisconnect: Boolean,
    voiceStatus: (String) -> Unit,
    addLog: (String) -> Unit,
    connect: () -> Unit
) {
// Safety: always release the microphone when the screen leaves the foreground.
DisposableEffect(context, voiceManager) {
    val lifecycle = (context as? ComponentActivity)?.lifecycle
    val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) {
            voiceManager.stop()
            voiceStatus = "Микрофон выключен"
        }
    }
    lifecycle?.addObserver(observer)
    onDispose {
        lifecycle?.removeObserver(observer)
        voiceManager.stop()
    }
}

// Keep MQTT alive in a foreground service while the app is not visible.
// The foreground activity owns the connection while it is visible, so we
// never keep two MQTT clients connected at the same time.
DisposableEffect(context, mqtt) {
    val lifecycle = (context as? ComponentActivity)?.lifecycle
    val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_STOP -> {
                val marfaActive = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("marfa_voice_active", false)
                if (!manualMqttDisconnect && !marfaActive) {
                    addLog("MQTT background mode: starting service")
                    mqtt.disconnect()
                    val intent = Intent(context, MqttBackgroundService::class.java)
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            ContextCompat.startForegroundService(context, intent)
                        } else {
                            context.startService(intent)
                        }
                    } catch (e: Exception) {
                        addLog("MQTT background service start failed: " + (e.message ?: e.javaClass.simpleName))
                    }
                }
            }
            Lifecycle.Event.ON_START -> {
                val marfaActive = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("marfa_voice_active", false)
                if (!marfaActive) {
                    try {
                        context.stopService(Intent(context, MqttBackgroundService::class.java))
                    } catch (_: Exception) {}
                }
                if (!manualMqttDisconnect && !marfaActive) {
                    addLog("MQTT foreground mode: restoring connection")
                    connect()
                }
            }
            else -> Unit
        }
    }
    lifecycle?.addObserver(observer)
    onDispose { lifecycle?.removeObserver(observer) }
}

// Automatically connect when the application opens and periodically
// restore the connection if it was lost.
LaunchedEffect(Unit) {
    delay(500)
    if (!manualMqttDisconnect && !mqtt.isConnected()) {
        addLog("MQTT auto-connect: starting")
        connect()
    }

    while (true) {
        delay(15_000)
        if (!manualMqttDisconnect && !mqtt.isConnected()) {
            addLog("MQTT auto-check: disconnected, reconnecting")
            connect()
        }
    }
}


}

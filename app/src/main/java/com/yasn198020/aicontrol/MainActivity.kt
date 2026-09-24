package com.yasn198020.aicontrol

import com.yasn198020.aicontrol.mqtt.*
import com.yasn198020.aicontrol.voice.*
import com.yasn198020.aicontrol.commands.*
import com.yasn198020.aicontrol.scenarios.*
import com.yasn198020.aicontrol.history.*
import com.yasn198020.aicontrol.updates.*
import com.yasn198020.aicontrol.marfa.*
import com.yasn198020.aicontrol.ui.*

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.os.Bundle
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState
import com.yasn198020.aicontrol.devices.DeviceManager

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_WIDGET_VOICE = "com.yasn198020.aicontrol.action.WIDGET_VOICE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == "com.yasn198020.aicontrol.action.MARFA_SHORTCUT"
            && intent?.getBooleanExtra("marfa_shortcut_toggle", false) == true) {
            toggleMarfaFromShortcut()
            finish()
            return
        }

        MarfaShortcutInstaller.ensurePinned(this)
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        setContent {
            val baseDensity = LocalDensity.current
            var fontScale by remember {
                mutableFloatStateOf(prefs.getFloat("ui_font_scale", 0.85f).coerceIn(0.70f, 1.10f))
            }
            CompositionLocalProvider(
                LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale)
            ) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface(Modifier.fillMaxSize()) {
                        App(
                            fontScale = fontScale,
                            onFontScaleChange = {
                                val value = it.coerceIn(0.70f, 1.10f)
                                fontScale = value
                                prefs.edit().putFloat("ui_font_scale", value).apply()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == "com.yasn198020.aicontrol.action.MARFA_SHORTCUT"
            && intent.getBooleanExtra("marfa_shortcut_toggle", false)) {
            toggleMarfaFromShortcut()
            return
        }
        if (intent.action == ACTION_WIDGET_VOICE) {
            setIntent(intent)
            recreate()
        }
    }

    private fun toggleMarfaFromShortcut() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val active = prefs.getBoolean("marfa_voice_active", false)

        if (active) {
            stopService(Intent(this, MarfaVoiceService::class.java))
            MarfaShortcutInstaller.setActive(this, false)
        } else {
            try {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, MarfaVoiceService::class.java)
                        .putExtra("start_listening", true)
                )
            } catch (_: Exception) {
            }
            MarfaShortcutInstaller.setActive(this, true)
        }
    }
}

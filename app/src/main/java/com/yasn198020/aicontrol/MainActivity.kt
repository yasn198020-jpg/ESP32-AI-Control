package com.yasn198020.aicontrol

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import com.yasn198020.aicontrol.marfa.MarfaShortcutInstaller
import com.yasn198020.aicontrol.marfa.MarfaVoiceService
import com.yasn198020.aicontrol.ui.UiPreferences

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
        val uiPreferences = UiPreferences(prefs)
        setContent {
            val baseDensity = LocalDensity.current
            var fontScale by remember {
                mutableFloatStateOf(uiPreferences.fontScale)
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
                                uiPreferences.saveFontScale(value)
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

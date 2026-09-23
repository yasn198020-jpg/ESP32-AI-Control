package com.yasn198020.aicontrol

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

object MarfaShortcutInstaller {
    private const val SHORTCUT_ID = "marfa_voice"
    private const val PREFS = "settings"
    private const val PREF_REQUESTED = "marfa_shortcut_requested"

    fun ensurePinned(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_REQUESTED, false)) return

        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_WIDGET_VOICE
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel("🎙 Марфа")
            .setLongLabel("🎙 Марфа — микрофон")
            .setIcon(IconCompat.createWithResource(context, R.drawable.app_icon))
            .setIntent(intent)
            .build()

        prefs.edit().putBoolean(PREF_REQUESTED, true).apply()
        ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }
}

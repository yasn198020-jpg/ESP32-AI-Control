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
        if (prefs.getBoolean(PREF_REQUESTED, false)) {
            setActive(context, prefs.getBoolean("marfa_voice_active", false))
            return
        }

        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return

        val intent = Intent(context, MarfaToggleActivity::class.java)

        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel("🎙 Марфа")
            .setLongLabel("🎙 Марфа — включить/выключить микрофон")
            .setIcon(IconCompat.createWithResource(context, inactiveIcon(context)))
            .setIntent(intent)
            .build()

        prefs.edit().putBoolean(PREF_REQUESTED, true).apply()
        ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }

    fun requestPinned(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_REQUESTED, true).apply()

        val active = prefs.getBoolean("marfa_voice_active", false)
        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel(if (active) "🎙 Марфа • ВКЛ" else "🎙 Марфа")
            .setLongLabel(
                if (active) "🎙 Марфа — микрофон включён"
                else "🎙 Марфа — включить/выключить микрофон"
            )
            .setIcon(
                IconCompat.createWithResource(
                    context,
                    if (active) R.drawable.ic_marfa_on else R.drawable.ic_marfa_off
                )
            )
            .setIntent(Intent(context, MarfaToggleActivity::class.java))
            .build()

        try {
            ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        } catch (_: Exception) {
        }
    }

    fun setActive(context: Context, active: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel(if (active) "🎙 Марфа • ВКЛ" else "🎙 Марфа")
            .setLongLabel(
                if (active) "🎙 Марфа — микрофон включён"
                else "🎙 Марфа — микрофон выключен"
            )
            .setIcon(
                IconCompat.createWithResource(
                    context,
                    if (active) R.drawable.ic_marfa_on else R.drawable.ic_marfa_off
                )
            )
            .setIntent(Intent(context, MarfaToggleActivity::class.java))
            .build()

        try {
            ShortcutManagerCompat.updateShortcuts(context, listOf(shortcut))
        } catch (_: Exception) {
        }
    }

    private fun inactiveIcon(context: Context): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            R.drawable.ic_marfa_off
        } else {
            R.drawable.ic_marfa_off
        }
}

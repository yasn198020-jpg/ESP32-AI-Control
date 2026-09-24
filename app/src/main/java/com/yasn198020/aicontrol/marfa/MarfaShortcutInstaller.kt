package com.yasn198020.aicontrol.marfa

import com.yasn198020.aicontrol.*
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build

object MarfaShortcutInstaller {
    private const val SHORTCUT_ID = "marfa_voice"
    private const val PREF_REQUESTED = "marfa_shortcut_requested"

    private fun buildShortcut(context: Context): ShortcutInfo {
        val active = context.getSharedPreferences(APP_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .getBoolean(MARFA_ACTIVE_PREF, false)

        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_MARFA_SHORTCUT
            putExtra(EXTRA_MARFA_SHORTCUT_TOGGLE, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        return ShortcutInfo.Builder(context, SHORTCUT_ID)
            .setShortLabel(if (active) "🎙 ВКЛ" else "🎙 Марфа")
            .setLongLabel(if (active) "Марфа — микрофон включён" else "Марфа — микрофон выключен")
            .setIcon(Icon.createWithResource(
                context,
                if (active) R.drawable.ic_marfa_on else R.drawable.ic_marfa_off
            ))
            .setIntent(intent)
            .build()
    }

    fun ensurePinned(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val prefs = context.getSharedPreferences(APP_SETTINGS_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_REQUESTED, false)) {
            setActive(context, prefs.getBoolean(MARFA_ACTIVE_PREF, false))
        }
    }

    fun requestPinned(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!manager.isRequestPinShortcutSupported) return false

        return try {
            val shortcut = buildShortcut(context)
            manager.removeDynamicShortcuts(listOf(SHORTCUT_ID))
            manager.pushDynamicShortcut(shortcut)

            val pinned = manager.pinnedShortcuts.any { it.id == SHORTCUT_ID }
            val pinRequest = if (pinned) {
                ShortcutInfo.Builder(context, SHORTCUT_ID).build()
            } else {
                shortcut
            }

            val accepted = manager.requestPinShortcut(pinRequest, null)

            if (accepted) {
                context.getSharedPreferences(APP_SETTINGS_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_REQUESTED, true)
                    .apply()
            }
            accepted
        } catch (_: Exception) {
            false
        }
    }

    fun setActive(context: Context, active: Boolean) {
        context.getSharedPreferences(APP_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(MARFA_ACTIVE_PREF, active)
            .apply()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return

        try {
            val pinned = manager.pinnedShortcuts.any { it.id == SHORTCUT_ID }
            val shortcut = buildShortcut(context)
            if (pinned) {
                manager.updateShortcuts(listOf(shortcut))
            } else {
                manager.removeDynamicShortcuts(listOf(SHORTCUT_ID))
                manager.pushDynamicShortcut(shortcut)
            }
        } catch (_: Exception) {
        }
    }
}
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
    private const val PREFS = "settings"
    private const val PREF_REQUESTED = "marfa_shortcut_requested"

    private fun buildShortcut(context: Context): ShortcutInfo {
        val active = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("marfa_voice_active", false)

        val intent = Intent(context, MainActivity::class.java).apply {
            action = "com.yasn198020.aicontrol.action.MARFA_SHORTCUT"
            putExtra("marfa_shortcut_toggle", true)
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
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_REQUESTED, false)) {
            setActive(context, prefs.getBoolean("marfa_voice_active", false))
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
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
        // Persist the state here as well as updating the launcher shortcut.
        // If Android kills the service before onDestroy(), the old implementation
        // could leave marfa_voice_active=true forever and the shortcut would stay ON.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("marfa_voice_active", active)
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
package com.yasn198020.aicontrol

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

    fun ensurePinned(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_REQUESTED, false)) {
            setActive(context, prefs.getBoolean("marfa_voice_active", false))
            return
        }

        // Do not silently request a launcher dialog on every first start.
        // The user can explicitly install it from the app menu.
        if (isSupported(context)) {
            prefs.edit().putBoolean(PREF_REQUESTED, true).apply()
        }
    }

    /**
     * Requests the launcher to pin the Marfa shortcut.
     *
     * Returns true when Android accepted the pin request.
     * It does not mean that the user has already confirmed the dialog.
     */
    fun requestPinned(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

        val manager = context.getSystemService(ShortcutManager::class.java)
            ?: return false

        if (!manager.isRequestPinShortcutSupported) return false

        return try {
            val pinned = manager.pinnedShortcuts.any { it.id == SHORTCUT_ID }

            // Android requires only the ID when a shortcut with this ID
            // already exists. For a new shortcut we provide all details.
            val shortcut = if (pinned) {
                ShortcutInfo.Builder(context, SHORTCUT_ID).build()
            } else {
                ShortcutInfo.Builder(context, SHORTCUT_ID)
                    .setShortLabel("🎙 Марфа")
                    .setLongLabel("🎙 Марфа — микрофон")
                    .setIcon(
                        Icon.createWithResource(
                            context,
                            if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                    .getBoolean("marfa_voice_active", false)
                            ) R.drawable.ic_marfa_on else R.drawable.ic_marfa_off
                        )
                    )
                    .setIntent(Intent(context, MarfaToggleActivity::class.java))
                    .build()
            }

            val accepted = manager.requestPinShortcut(shortcut, null)

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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(ShortcutManager::class.java) ?: return

        try {
            val pinned = manager.pinnedShortcuts.any { it.id == SHORTCUT_ID }
            if (!pinned) return

            val shortcut = ShortcutInfo.Builder(context, SHORTCUT_ID)
                .setShortLabel(if (active) "🎙 ВКЛ" else "🎙 Марфа")
                .setLongLabel(if (active) "Марфа — микрофон включён" else "Марфа — микрофон выключен")
                .setIcon(
                    Icon.createWithResource(
                        context,
                        if (active) R.drawable.ic_marfa_on else R.drawable.ic_marfa_off
                    )
                )
                .setIntent(Intent(context, MarfaToggleActivity::class.java))
                .build()

            manager.updateShortcuts(listOf(shortcut))
        } catch (_: Exception) {
        }
    }

    private fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        return manager.isRequestPinShortcutSupported
    }
}

package com.example.mobprog.service

import android.content.Context
import android.provider.Settings

/**
 * Remembers whether the user wants the quick-note bubble. The overlay permission is
 * separate: the preference says "the user wants it", the permission says "the system
 * allows it", and the service is what actually shows the bubble.
 */
object QuickNotePrefs {

    private const val FILE = "quick_note"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)
}

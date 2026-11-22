package com.example.mobprog.util

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import com.example.mobprog.R

/**
 * The light/dark choice. It is stored once and handed to AppCompat at startup, so
 * every activity, the quick-note panel and dialogs follow the same mode.
 */
object ThemePrefs {

    const val SYSTEM = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    const val LIGHT = AppCompatDelegate.MODE_NIGHT_NO
    const val DARK = AppCompatDelegate.MODE_NIGHT_YES

    val modes = intArrayOf(SYSTEM, LIGHT, DARK)

    private const val FILE = "appearance"
    private const val KEY_MODE = "mode"

    fun mode(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_MODE, SYSTEM)

    fun setMode(context: Context, mode: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MODE, mode)
            .apply()
        // Recreates the running activities with the new resources.
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /** Applies the stored choice before any activity is created. */
    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(mode(context))
    }

    /**
     * Overlay windows (the quick-note panel, the deadline dialog) are not created by
     * AppCompat, so their context has to be told about an explicit light/dark choice.
     * Must run before that context's resources are read.
     */
    fun applyTo(wrapper: ContextThemeWrapper) {
        val night = when (mode(wrapper.baseContext)) {
            LIGHT -> false
            DARK -> true
            else -> return
        }
        val configuration = Configuration(wrapper.baseContext.resources.configuration)
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        wrapper.applyOverrideConfiguration(configuration)
    }

    @StringRes
    fun labelRes(mode: Int): Int = when (mode) {
        LIGHT -> R.string.settings_theme_light
        DARK -> R.string.settings_theme_dark
        else -> R.string.settings_theme_system
    }
}

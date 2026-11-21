package com.example.mobprog.util

import android.content.Context

/** Editor-only options that are off until the user asks for them. */
object EditorPrefs {

    private const val FILE = "editor"
    private const val KEY_RAW_MARKDOWN = "raw_markdown"

    fun isRawMarkdownEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_RAW_MARKDOWN, false)

    fun setRawMarkdownEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RAW_MARKDOWN, enabled)
            .apply()
    }
}

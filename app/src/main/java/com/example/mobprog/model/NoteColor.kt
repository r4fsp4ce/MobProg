package com.example.mobprog.model

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.example.mobprog.R

/**
 * Note colours. The key is what gets stored in `notes.color`, so the database
 * never holds a raw ARGB value and colour names stay translatable.
 */
enum class NoteColor(
    val key: String,
    @StringRes val labelRes: Int,
    @ColorRes val colorRes: Int
) {
    DEFAULT("default", R.string.color_default, R.color.note_default),
    RED("red", R.string.color_red, R.color.note_red),
    ORANGE("orange", R.string.color_orange, R.color.note_orange),
    YELLOW("yellow", R.string.color_yellow, R.color.note_yellow),
    GREEN("green", R.string.color_green, R.color.note_green),
    TEAL("teal", R.string.color_teal, R.color.note_teal),
    BLUE("blue", R.string.color_blue, R.color.note_blue),
    INDIGO("indigo", R.string.color_indigo, R.color.note_indigo),
    PURPLE("purple", R.string.color_purple, R.color.note_purple),
    PINK("pink", R.string.color_pink, R.color.note_pink),
    BROWN("brown", R.string.color_brown, R.color.note_brown),
    GRAY("gray", R.string.color_gray, R.color.note_gray);

    companion object {
        fun fromKey(key: String?): NoteColor = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

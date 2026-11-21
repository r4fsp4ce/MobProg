package com.example.mobprog.model

import androidx.annotation.StringRes
import com.example.mobprog.R

/** `entries.type` in the database. */
enum class EntryType(@StringRes val labelRes: Int) {
    TEXT(R.string.type_text),
    BULLET(R.string.type_bullet),
    NUMBERED(R.string.type_numbered),
    CHECKBOX(R.string.type_checkbox);

    /** Order used by the list-type button in the ribbon. */
    fun next(): EntryType = when (this) {
        TEXT -> BULLET
        BULLET -> NUMBERED
        NUMBERED -> CHECKBOX
        CHECKBOX -> BULLET
    }

    companion object {
        fun fromName(name: String?): EntryType = entries.firstOrNull { it.name == name } ?: TEXT

        /** Types a user can pick in the editor / quick note. */
        val ribbonOrder = listOf(BULLET, NUMBERED, CHECKBOX)
    }
}

/** `entries.priority`: 0 none, 1 highest, 2, 3. */
enum class Priority(val value: Int, @StringRes val labelRes: Int) {
    NONE(0, R.string.priority_none),
    HIGHEST(1, R.string.priority_1),
    HIGH(2, R.string.priority_2),
    MEDIUM(3, R.string.priority_3);

    val badge: String get() = if (this == NONE) "" else "P$value"

    companion object {
        fun fromValue(value: Int): Priority = entries.firstOrNull { it.value == value } ?: NONE

        val selectable = listOf(HIGHEST, HIGH, MEDIUM)
    }
}

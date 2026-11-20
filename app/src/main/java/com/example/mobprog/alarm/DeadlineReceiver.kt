package com.example.mobprog.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.mobprog.notify.Notifications

/** Fires at one of the fixed offsets and posts the reminder. */
class DeadlineReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
        val noteTitle = intent.getStringExtra(EXTRA_NOTE_TITLE).orEmpty()
        val entryText = intent.getStringExtra(EXTRA_ENTRY_TEXT).orEmpty()
        val offsetMinutes = intent.getIntExtra(EXTRA_OFFSET_MINUTES, 0)
        Notifications.postDeadline(context, noteId, noteTitle, entryText, offsetMinutes)
    }

    companion object {
        const val EXTRA_NOTE_ID = "extra_note_id"
        const val EXTRA_NOTE_TITLE = "extra_note_title"
        const val EXTRA_ENTRY_TEXT = "extra_entry_text"
        const val EXTRA_OFFSET_MINUTES = "extra_offset_minutes"
    }
}

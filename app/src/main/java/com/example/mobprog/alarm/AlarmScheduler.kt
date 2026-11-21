package com.example.mobprog.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.mobprog.data.TodoStore
import com.example.mobprog.model.Entry
import com.example.mobprog.model.NoteDetail
import com.example.mobprog.model.Reminder
import com.example.mobprog.util.Dates

/**
 * Deadline reminders at fixed offsets: 1 day, 2 hours, 1 hour and 30 minutes before.
 *
 * Each (entry, offset) pair gets its own PendingIntent, so an entry can be cancelled
 * without touching its neighbours. Offsets already in the past are skipped.
 */
object AlarmScheduler {

    /** Minutes before the deadline; index is part of the request code. */
    private val offsetsMinutes = listOf(24 * 60, 2 * 60, 60, 30)

    /** Schedules (or re-schedules) every alarm for one entry. Cancels first. */
    fun syncEntry(context: Context, noteId: Long, entry: Entry, noteTitle: String) {
        cancelEntry(context, entry.id)
        val deadline = entry.deadlineAt ?: return
        if (entry.checked) return
        offsetsMinutes.forEachIndexed { index, minutes ->
            val triggerAt = deadline - minutes * 60_000L
            if (triggerAt <= System.currentTimeMillis()) return@forEachIndexed
            schedule(context, entry.id, noteId, noteTitle, entry.text, triggerAt, minutes, index)
        }
    }

    fun syncNote(context: Context, detail: NoteDetail) {
        detail.allEntries().forEach { entry -> syncEntry(context, detail.note.id, entry, detail.note.title) }
    }

    fun cancelNote(context: Context, detail: NoteDetail) {
        detail.allEntries().forEach { cancelEntry(context, it.id) }
    }

    fun cancelEntry(context: Context, entryId: Long) {
        val manager = managerOf(context) ?: return
        offsetsMinutes.indices.forEach { index ->
            manager.cancel(alarmIntent(context, entryId, index, emptyExtras()))
        }
    }

    /** Called by BootReceiver: everything that still has a future deadline is re-armed. */
    fun rescheduleAll(context: Context, store: TodoStore) {
        store.reminders()
            .filter { !it.checked && it.deadlineAt > System.currentTimeMillis() }
            .forEach { reminder -> rescheduleReminder(context, reminder) }
    }

    private fun rescheduleReminder(context: Context, reminder: Reminder) {
        cancelEntry(context, reminder.entryId)
        offsetsMinutes.forEachIndexed { index, minutes ->
            val triggerAt = reminder.deadlineAt - minutes * 60_000L
            if (triggerAt <= System.currentTimeMillis()) return@forEachIndexed
            schedule(
                context,
                reminder.entryId,
                reminder.noteId,
                reminder.noteTitle,
                reminder.text,
                triggerAt,
                minutes,
                index
            )
        }
    }

    private fun schedule(
        context: Context,
        entryId: Long,
        noteId: Long,
        noteTitle: String,
        entryText: String,
        triggerAt: Long,
        offsetMinutes: Int,
        offsetIndex: Int
    ) {
        val manager = managerOf(context) ?: return
        val intent = alarmIntent(
            context,
            entryId,
            offsetIndex,
            deadlineExtras(noteId, noteTitle, entryText, offsetMinutes)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
            // No exact-alarm permission: still remind, just less punctually.
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
        } else {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
        }
    }

    private fun alarmIntent(
        context: Context,
        entryId: Long,
        offsetIndex: Int,
        extras: android.os.Bundle
    ): PendingIntent {
        val intent = Intent(context, DeadlineReceiver::class.java).putExtras(extras)
        return PendingIntent.getBroadcast(
            context,
            requestCode(entryId, offsetIndex),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun deadlineExtras(
        noteId: Long,
        noteTitle: String,
        entryText: String,
        offsetMinutes: Int
    ): android.os.Bundle = android.os.Bundle().apply {
        putLong(DeadlineReceiver.EXTRA_NOTE_ID, noteId)
        putString(DeadlineReceiver.EXTRA_NOTE_TITLE, noteTitle)
        putString(DeadlineReceiver.EXTRA_ENTRY_TEXT, entryText)
        putInt(DeadlineReceiver.EXTRA_OFFSET_MINUTES, offsetMinutes)
    }

    private fun emptyExtras(): android.os.Bundle = android.os.Bundle()

    /** Stable per (entry, offset) request code. */
    private fun requestCode(entryId: Long, offsetIndex: Int): Int =
        ((entryId % 100_000L) * 10L + offsetIndex).toInt()

    private fun managerOf(context: Context): AlarmManager? =
        context.getSystemService(AlarmManager::class.java)

    /** Human readable deadline, used by notifications elsewhere. */
    fun describe(context: Context, deadlineAt: Long, allDay: Boolean): String =
        Dates.deadlineLabel(context, deadlineAt, allDay)
}

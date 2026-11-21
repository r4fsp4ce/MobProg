package com.example.mobprog.data

import com.example.mobprog.model.Folder
import com.example.mobprog.model.NoteDetail
import com.example.mobprog.model.NoteQuery
import com.example.mobprog.model.NoteSummary
import com.example.mobprog.model.Reminder

/**
 * Everything the UI needs from the storage layer, and nothing else.
 *
 * The frontend ships against [InMemoryTodoStore]. A `SQLiteOpenHelper` backed
 * implementation of this interface can be installed with
 * `StoreProvider.install(...)` without touching a single screen — see the
 * mapping notes below for the exact contract.
 *
 * ## Tables this maps to (ARCHITECTURE.md)
 * ```
 * folders(id, name UNIQUE NOT NULL, created_at)          -- default folder "Inbox"
 * notes(id, folder_id, title, color, pinned, archived, trashed, created_at, updated_at)
 * blocks(id, note_id, position, heading, origin_note_id)  -- origin null for base blocks
 * entries(id, block_id, position, type, text, checked, priority, deadline_at, all_day)
 * ```
 *
 * ## Rules an implementation must keep
 * - `color` stores [com.example.mobprog.model.NoteColor.key] (`"default"`, `"red"`, ...),
 *   never an ARGB value.
 * - `type` stores the [com.example.mobprog.model.EntryType] name (`"TEXT"`, `"BULLET"`,
 *   `"NUMBERED"`, `"CHECKBOX"`), `priority` stores 0..3.
 * - `deadline_at` is epoch milliseconds or NULL.
 * - Positions are contiguous from 0 inside their parent, and are what the UI shows.
 * - The Inbox folder always exists: it is the fallback for new notes, for notes
 *   whose folder was deleted, and for the quick-note panel.
 * - Deleting a note is a soft delete (`trashed = 1`) unless it is already in the
 *   trash. Only [emptyTrash] and [deleteForever] remove rows.
 * - Searching is case-insensitive LIKE over `notes.title`, `blocks.heading` and
 *   `entries.text`.
 * - [saveNote] also refreshes `updated_at`, and writes the Markdown mirror to
 *   `getExternalFilesDir("notes")/<folder>/<noteId>.md`.
 * - After any change to an entry's text, deadline or checked flag, the deadline
 *   alarms are cancelled or rescheduled (see `alarm.AlarmScheduler`).
 *
 * Calls are synchronous. A disk-backed implementation should be invoked off the
 * main thread (`util.Threads.io { ... }`), which is what every screen does.
 */
interface TodoStore {

    /** All folders, Inbox first, then by creation time. */
    fun folders(): List<Folder>

    fun folder(id: Long): Folder?

    /** Id of the "Inbox" folder. */
    fun inboxId(): Long

    /** Null when the name is blank or already taken (case-insensitive). */
    fun createFolder(name: String): FolderNameResult

    fun renameFolder(id: Long, newName: String): FolderNameResult

    /** False for the Inbox. Notes inside are moved to the Inbox, not deleted. */
    fun deleteFolder(id: Long): Boolean

    /** Number of live (not archived, not trashed) notes per folder id. */
    fun folderNoteCounts(): Map<Long, Int>

    /** Cards for the current view, search, sort and filter. Pinned ordering is done by the UI. */
    fun notes(query: NoteQuery): List<NoteSummary>

    /** Null when the note does not exist. */
    fun note(id: Long): NoteDetail?

    /**
     * Inserts or updates a note with its blocks and entries.
     * New rows arrive with id 0 (blocks/entries) or id -1 (note); the store assigns ids.
     * Returns the note id.
     */
    fun saveNote(detail: NoteDetail): Long

    fun setPinned(id: Long, pinned: Boolean)

    fun setArchived(id: Long, archived: Boolean)

    fun moveNote(id: Long, folderId: Long): Boolean

    /** Soft delete. */
    fun trashNote(id: Long)

    fun restoreNote(id: Long)

    fun deleteForever(id: Long)

    fun emptyTrash()

    /** Updates one checklist item; both the note and the entry keep their `updated_at` in sync. */
    fun markChecked(entryId: Long, checked: Boolean)

    /**
     * Appends [sourceId] to [targetId] following the append rules in ARCHITECTURE.md:
     * the source title becomes the first appended block's heading, the merged title is
     * blank, appended blocks carry `origin_note_id`, blocks are sorted once (priority-1
     * first, ties keep target blocks before appended ones), the source note is removed
     * and entry ids are kept so scheduled alarms still fire.
     *
     * Returns a token for [undoAppend], or null when the append is not allowed.
     */
    fun appendNotes(sourceId: Long, targetId: Long): AppendUndoToken?

    fun undoAppend(token: AppendUndoToken): Boolean

    /** Every entry that has a deadline, sorted by deadline. Used by the Reminders view and BootReceiver. */
    fun reminders(): List<Reminder>
}

sealed interface FolderNameResult {
    data class Created(val folder: Folder) : FolderNameResult
    data class Renamed(val folder: Folder) : FolderNameResult
    data object Blank : FolderNameResult
    data object Duplicate : FolderNameResult
    data object NotFound : FolderNameResult
}

/** Opaque handle to the snapshot taken by [TodoStore.appendNotes]. */
data class AppendUndoToken(val id: String)

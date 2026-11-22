package com.example.mobprog.model

/** Row of `folders`: id, name UNIQUE NOT NULL, created_at. */
data class Folder(
    val id: Long,
    val name: String,
    val createdAt: Long
)

/** Row of `notes`. `color` is a [NoteColor.key]. */
data class Note(
    val id: Long,
    val folderId: Long,
    val title: String,
    val color: NoteColor,
    val pinned: Boolean,
    val archived: Boolean,
    val trashed: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/** Row of `blocks`. `originNoteId` is null for a block that was never appended. */
data class Block(
    val id: Long,
    val noteId: Long,
    val position: Int,
    val heading: String,
    val originNoteId: Long?
)

/** Row of `entries`. */
data class Entry(
    val id: Long,
    val blockId: Long,
    val position: Int,
    val type: EntryType,
    val text: String,
    val checked: Boolean,
    val priority: Priority,
    val deadlineAt: Long?,
    val allDay: Boolean
)

data class BlockDetail(
    val block: Block,
    val entries: List<Entry>
)

/** A note together with its blocks and their entries — everything the editor needs. */
data class NoteDetail(
    val note: Note,
    val blocks: List<BlockDetail>
) {
    val entryCount: Int get() = blocks.sumOf { it.entries.size }

    fun allEntries(): List<Entry> = blocks.flatMap { it.entries }

    fun deepCopy(): NoteDetail = NoteDetail(
        note = note,
        blocks = blocks.map { BlockDetail(it.block, it.entries.map { entry -> entry.copy() }) }
    )
}

/**
 * The flattened shape the note grid renders. A database implementation can build
 * this with one query plus aggregate columns instead of loading every block.
 */
data class NoteSummary(
    val id: Long,
    val folderId: Long,
    val folderName: String,
    val title: String,
    val color: NoteColor,
    val pinned: Boolean,
    val archived: Boolean,
    val trashed: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    /** Markdown shown on the card: headings and text/bullet/numbered items only. */
    val previewMarkdown: String,
    /** First unchecked checklist items, in order. */
    val openCheckboxes: List<String>,
    val checkedCount: Int,
    val totalCheckboxes: Int,
    val highestPriority: Priority,
    val nextDeadlineAt: Long?,
    val nextDeadlineAllDay: Boolean
) {
    val hasOpenCheckbox: Boolean get() = openCheckboxes.isNotEmpty()
    val completion: Float get() = if (totalCheckboxes == 0) 0f else checkedCount.toFloat() / totalCheckboxes
}

/** One entry with a deadline, for the Reminders view and alarm rescheduling. */
data class Reminder(
    val entryId: Long,
    val noteId: Long,
    val noteTitle: String,
    val text: String,
    val deadlineAt: Long,
    val allDay: Boolean,
    val checked: Boolean
)

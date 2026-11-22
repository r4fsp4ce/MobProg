package com.example.mobprog.model

/**
 * Mutable working copy used by the editor. The editor keeps whole snapshots of it
 * for undo/redo, so every list has to be copied deeply.
 */
data class EntryDraft(
    var id: Long = 0L,
    var type: EntryType = EntryType.CHECKBOX,
    var text: String = "",
    var checked: Boolean = false,
    var priority: Priority = Priority.NONE,
    var deadlineAt: Long? = null,
    var allDay: Boolean = false
) {
    fun copyDraft(): EntryDraft = EntryDraft(id, type, text, checked, priority, deadlineAt, allDay)

    val isBlank: Boolean get() = text.isBlank()
}

data class BlockDraft(
    var id: Long = 0L,
    var heading: String = "",
    var originNoteId: Long? = null,
    var entries: MutableList<EntryDraft> = mutableListOf()
) {
    fun copyDraft(): BlockDraft =
        BlockDraft(id, heading, originNoteId, entries.map { it.copyDraft() }.toMutableList())
}

data class NoteDraft(
    var id: Long = -1L,
    var folderId: Long = -1L,
    var title: String = "",
    var color: NoteColor = NoteColor.DEFAULT,
    var pinned: Boolean = false,
    var blocks: MutableList<BlockDraft> = mutableListOf(BlockDraft())
) {
    fun copyDraft(): NoteDraft =
        NoteDraft(id, folderId, title, color, pinned, blocks.map { it.copyDraft() }.toMutableList())

    fun hasContent(): Boolean =
        title.isNotBlank() ||
            blocks.any { block -> block.heading.isNotBlank() || block.entries.any { !it.isBlank } }

    /** Drops blocks and entries that only hold whitespace. */
    fun normalised(): NoteDraft {
        val cleaned = blocks.mapNotNull { block ->
            val entries = block.entries.filter { !it.isBlank }.toMutableList()
            if (block.heading.isBlank() && entries.isEmpty()) null
            else block.copyDraft().also { it.entries = entries }
        }.toMutableList()
        if (cleaned.isEmpty()) cleaned.add(BlockDraft())
        return copyDraft().also { it.blocks = cleaned }
    }

    companion object {
        fun from(detail: NoteDetail): NoteDraft = NoteDraft(
            id = detail.note.id,
            folderId = detail.note.folderId,
            title = detail.note.title,
            color = detail.note.color,
            pinned = detail.note.pinned,
            blocks = detail.blocks.map { blockDetail ->
                BlockDraft(
                    id = blockDetail.block.id,
                    heading = blockDetail.block.heading,
                    originNoteId = blockDetail.block.originNoteId,
                    entries = blockDetail.entries.map { entry ->
                        EntryDraft(
                            id = entry.id,
                            type = entry.type,
                            text = entry.text,
                            checked = entry.checked,
                            priority = entry.priority,
                            deadlineAt = entry.deadlineAt,
                            allDay = entry.allDay
                        )
                    }.toMutableList()
                )
            }.toMutableList().ifEmpty { mutableListOf(BlockDraft()) }
        )
    }
}

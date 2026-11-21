package com.example.mobprog.data

import com.example.mobprog.model.Block
import com.example.mobprog.model.BlockDetail
import com.example.mobprog.model.Entry
import com.example.mobprog.model.EntryType
import com.example.mobprog.model.Folder
import com.example.mobprog.model.Note
import com.example.mobprog.model.NoteColor
import com.example.mobprog.model.NoteDetail
import com.example.mobprog.model.NoteQuery
import com.example.mobprog.model.NoteSort
import com.example.mobprog.model.NoteSummary
import com.example.mobprog.model.NoteView
import com.example.mobprog.model.Priority
import com.example.mobprog.model.Reminder
import com.example.mobprog.model.StatusFilter
import java.util.UUID

/**
 * Frontend stand-in for the SQLite store. It behaves like the real thing —
 * same ordering, filtering, soft delete and append rules — but keeps everything
 * in memory, so the app runs and every screen can be demoed before the database
 * layer lands.
 */
class InMemoryTodoStore(private val seedDemoData: Boolean = true) : TodoStore {

    private val lock = Any()
    private var nextId = 1L

    private val folderMap = LinkedHashMap<Long, Folder>()
    private val noteMap = LinkedHashMap<Long, NoteDetail>()
    private val undoShelf = HashMap<String, AppendSnapshot>()

    private val inbox: Long

    init {
        val now = System.currentTimeMillis()
        inbox = addFolder("Inbox", now).id
        if (seedDemoData) seed(now)
    }

    private class AppendSnapshot(
        val source: NoteDetail,
        val targetId: Long,
        val targetTitle: String
    )

    private fun newId(): Long = nextId++

    private fun addFolder(name: String, createdAt: Long): Folder {
        val folder = Folder(newId(), name, createdAt)
        folderMap[folder.id] = folder
        return folder
    }

    // region folders

    override fun folders(): List<Folder> = synchronized(lock) {
        folderMap.values.sortedWith(compareBy({ it.id != inbox }, { it.createdAt }))
    }

    override fun folder(id: Long): Folder? = synchronized(lock) { folderMap[id] }

    override fun inboxId(): Long = inbox

    override fun createFolder(name: String): FolderNameResult = synchronized(lock) {
        val clean = name.trim()
        when {
            clean.isEmpty() -> FolderNameResult.Blank
            isDuplicateName(clean, ignoreId = -1L) -> FolderNameResult.Duplicate
            else -> FolderNameResult.Created(addFolder(clean, System.currentTimeMillis()))
        }
    }

    override fun renameFolder(id: Long, newName: String): FolderNameResult = synchronized(lock) {
        val existing = folderMap[id] ?: return FolderNameResult.NotFound
        val clean = newName.trim()
        when {
            clean.isEmpty() -> FolderNameResult.Blank
            isDuplicateName(clean, ignoreId = id) -> FolderNameResult.Duplicate
            else -> {
                val updated = existing.copy(name = clean)
                folderMap[id] = updated
                FolderNameResult.Renamed(updated)
            }
        }
    }

    private fun isDuplicateName(name: String, ignoreId: Long): Boolean =
        folderMap.values.any { it.id != ignoreId && it.name.equals(name, ignoreCase = true) }

    override fun deleteFolder(id: Long): Boolean = synchronized(lock) {
        if (id == inbox || !folderMap.containsKey(id)) return false
        folderMap.remove(id)
        noteMap.replaceAll { _, detail ->
            if (detail.note.folderId == id) {
                detail.copy(note = detail.note.copy(folderId = inbox))
            } else {
                detail
            }
        }
        true
    }

    override fun folderNoteCounts(): Map<Long, Int> = synchronized(lock) {
        val counts = HashMap<Long, Int>()
        noteMap.values.forEach { detail ->
            if (!detail.note.trashed && !detail.note.archived) {
                counts[detail.note.folderId] = (counts[detail.note.folderId] ?: 0) + 1
            }
        }
        counts
    }

    // endregion

    // region notes

    override fun notes(query: NoteQuery): List<NoteSummary> = synchronized(lock) {
        val needle = query.search.trim()
        noteMap.values
            .asSequence()
            .filter { matchesView(it, query) }
            .filter { matchesSearch(it, needle) }
            .filter { matchesFilter(it, query) }
            .map { summaryOf(it) }
            .sortedWith(comparatorFor(query.sort))
            .toList()
    }

    private fun matchesView(detail: NoteDetail, query: NoteQuery): Boolean {
        val note = detail.note
        return when (query.view) {
            NoteView.NOTES -> !note.archived && !note.trashed
            NoteView.FOLDER -> !note.archived && !note.trashed && note.folderId == query.folderId
            NoteView.ARCHIVE -> note.archived && !note.trashed
            NoteView.TRASH -> note.trashed
            NoteView.REMINDERS -> !note.archived && !note.trashed &&
                detail.allEntries().any { it.deadlineAt != null }
        }
    }

    private fun matchesSearch(detail: NoteDetail, needle: String): Boolean {
        if (needle.isEmpty()) return true
        if (detail.note.title.contains(needle, ignoreCase = true)) return true
        return detail.blocks.any { block ->
            block.block.heading.contains(needle, ignoreCase = true) ||
                block.entries.any { it.text.contains(needle, ignoreCase = true) }
        }
    }

    private fun matchesFilter(detail: NoteDetail, query: NoteQuery): Boolean {
        val filter = query.filter
        if (filter.colors.isNotEmpty() && detail.note.color !in filter.colors) return false
        if (filter.priorities.isNotEmpty() && highestPriority(detail) !in filter.priorities) return false
        val checkboxes = detail.allEntries().filter { it.type == EntryType.CHECKBOX }
        if (filter.hasDeadline && detail.allEntries().none { it.deadlineAt != null }) return false
        return when (filter.status) {
            StatusFilter.ANY -> true
            StatusFilter.OPEN -> checkboxes.any { !it.checked }
            StatusFilter.DONE -> checkboxes.isNotEmpty() && checkboxes.all { it.checked }
        }
    }

    private fun highestPriority(detail: NoteDetail): Priority {
        val candidate = detail.allEntries()
            .map { it.priority }
            .filter { it != Priority.NONE }
            .minByOrNull { it.value }
        return candidate ?: Priority.NONE
    }

    private fun comparatorFor(sort: NoteSort): Comparator<NoteSummary> = when (sort) {
        NoteSort.UPDATED -> compareByDescending { it.updatedAt }
        NoteSort.CREATED -> compareByDescending { it.createdAt }
        // Notes without a deadline go last.
        NoteSort.DEADLINE -> Comparator { a, b ->
            val first = a.nextDeadlineAt
            val second = b.nextDeadlineAt
            when {
                first == null && second == null -> 0
                first == null -> 1
                second == null -> -1
                else -> first.compareTo(second)
            }
        }
        NoteSort.PRIORITY -> compareBy { if (it.highestPriority == Priority.NONE) 99 else it.highestPriority.value }
        NoteSort.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
    }

    private fun summaryOf(detail: NoteDetail): NoteSummary {
        val entries = detail.allEntries()
        val checkboxes = entries.filter { it.type == EntryType.CHECKBOX }
        val open = checkboxes.filter { !it.checked }
        val deadlineEntry = entries.filter { it.deadlineAt != null }.minByOrNull { it.deadlineAt ?: Long.MAX_VALUE }
        val folderName = folderMap[detail.note.folderId]?.name.orEmpty()
        return NoteSummary(
            id = detail.note.id,
            folderId = detail.note.folderId,
            folderName = folderName,
            title = detail.note.title,
            color = detail.note.color,
            pinned = detail.note.pinned,
            archived = detail.note.archived,
            trashed = detail.note.trashed,
            createdAt = detail.note.createdAt,
            updatedAt = detail.note.updatedAt,
            previewMarkdown = previewOf(detail),
            openCheckboxes = open.map { it.text }.filter { it.isNotBlank() }.take(4),
            checkedCount = checkboxes.count { it.checked },
            totalCheckboxes = checkboxes.size,
            highestPriority = highestPriority(detail),
            nextDeadlineAt = deadlineEntry?.deadlineAt,
            nextDeadlineAllDay = deadlineEntry?.allDay ?: false
        )
    }

    /** Markdown for the card: headings and plain items, checklists are drawn separately. */
    private fun previewOf(detail: NoteDetail): String {
        val builder = StringBuilder()
        detail.blocks.forEach { block ->
            if (block.block.heading.isNotBlank()) {
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append("**").append(block.block.heading).append("**\n")
            }
            block.entries.forEach { entry ->
                if (entry.type == EntryType.CHECKBOX || entry.text.isBlank()) return@forEach
                when (entry.type) {
                    EntryType.BULLET -> builder.append("• ")
                    EntryType.NUMBERED -> builder.append("1. ")
                    else -> Unit
                }
                builder.append(entry.text).append('\n')
            }
        }
        return builder.toString().trim().take(600)
    }

    override fun note(id: Long): NoteDetail? = synchronized(lock) { noteMap[id]?.deepCopy() }

    override fun saveNote(detail: NoteDetail): Long = synchronized(lock) {
        val now = System.currentTimeMillis()
        val noteId = if (detail.note.id > 0) detail.note.id else newId()
        val previous = noteMap[noteId]
        val folderId = if (folderMap.containsKey(detail.note.folderId)) detail.note.folderId else inbox
        val note = detail.note.copy(
            id = noteId,
            folderId = folderId,
            createdAt = previous?.note?.createdAt ?: now,
            updatedAt = now
        )
        val blocks = detail.blocks.mapIndexed { blockIndex, blockDetail ->
            val blockId = if (blockDetail.block.id > 0) blockDetail.block.id else newId()
            BlockDetail(
                block = Block(
                    id = blockId,
                    noteId = noteId,
                    position = blockIndex,
                    heading = blockDetail.block.heading,
                    originNoteId = blockDetail.block.originNoteId
                ),
                entries = blockDetail.entries.mapIndexed { entryIndex, entry ->
                    entry.copy(
                        id = if (entry.id > 0) entry.id else newId(),
                        blockId = blockId,
                        position = entryIndex
                    )
                }
            )
        }
        val stored = NoteDetail(note, blocks).deepCopy()
        noteMap[noteId] = stored
        noteId
    }

    override fun setPinned(id: Long, pinned: Boolean) = updateNote(id) { it.copy(pinned = pinned) }

    override fun setArchived(id: Long, archived: Boolean) = updateNote(id) { it.copy(archived = archived) }

    override fun moveNote(id: Long, folderId: Long): Boolean = synchronized(lock) {
        if (!folderMap.containsKey(folderId)) return false
        updateNote(id) { it.copy(folderId = folderId) }
        true
    }

    private fun updateNote(id: Long, change: (Note) -> Note) {
        synchronized(lock) {
            val detail = noteMap[id] ?: return
            noteMap[id] = detail.copy(note = change(detail.note).copy(updatedAt = System.currentTimeMillis()))
        }
    }

    override fun trashNote(id: Long) = updateNote(id) { it.copy(trashed = true) }

    override fun restoreNote(id: Long) = updateNote(id) { it.copy(trashed = false) }

    override fun deleteForever(id: Long) {
        synchronized(lock) { noteMap.remove(id) }
    }

    override fun emptyTrash() {
        synchronized(lock) { noteMap.entries.removeAll { it.value.note.trashed } }
    }

    override fun markChecked(entryId: Long, checked: Boolean) {
        synchronized(lock) {
            noteMap.forEach { (noteId, detail) ->
                if (detail.allEntries().none { it.id == entryId }) return@forEach
                val blocks = detail.blocks.map { blockDetail ->
                    BlockDetail(
                        blockDetail.block,
                        blockDetail.entries.map { if (it.id == entryId) it.copy(checked = checked) else it }
                    )
                }
                noteMap[noteId] = NoteDetail(detail.note.copy(updatedAt = System.currentTimeMillis()), blocks)
            }
        }
    }

    // endregion

    // region append

    override fun appendNotes(sourceId: Long, targetId: Long): AppendUndoToken? = synchronized(lock) {
        if (sourceId == targetId) return null
        val source = noteMap[sourceId] ?: return null
        val target = noteMap[targetId] ?: return null
        if (source.note.trashed || target.note.trashed) return null

        val token = AppendUndoToken(UUID.randomUUID().toString())
        undoShelf[token.id] = AppendSnapshot(source.deepCopy(), target.note.id, target.note.title)

        val appended = source.blocks.mapIndexed { index, blockDetail ->
            val blockId = newId()
            val heading = when {
                index == 0 && source.note.title.isNotBlank() -> source.note.title
                else -> blockDetail.block.heading
            }
            BlockDetail(
                block = Block(blockId, target.note.id, 0, heading, source.note.id),
                entries = blockDetail.entries.mapIndexed { entryIndex, entry ->
                    // Entry ids are kept on purpose: scheduled alarms reference them.
                    entry.copy(blockId = blockId, position = entryIndex)
                }
            )
        }

        // Sort once: blocks holding a P1 item first; ties keep target blocks before appended ones.
        val merged = (target.blocks.map { it to 0 } + appended.map { it to 1 })
            .sortedWith(
                compareBy(
                    { if (it.first.entries.any { entry -> entry.priority == Priority.HIGHEST }) 0 else 1 },
                    { it.second }
                )
            )
            .mapIndexed { index, pair ->
                val block = pair.first.block.copy(noteId = target.note.id, position = index)
                BlockDetail(block, pair.first.entries.mapIndexed { i, entry -> entry.copy(position = i) })
            }

        noteMap[target.note.id] = NoteDetail(
            target.note.copy(title = "", updatedAt = System.currentTimeMillis()),
            merged
        )
        noteMap.remove(sourceId)
        token
    }

    override fun undoAppend(token: AppendUndoToken): Boolean = synchronized(lock) {
        val snapshot = undoShelf.remove(token.id) ?: return false
        val target = noteMap[snapshot.targetId] ?: return false
        val remaining = target.blocks.filterNot { it.block.originNoteId == snapshot.source.note.id }
            .mapIndexed { index, blockDetail ->
                BlockDetail(blockDetail.block.copy(position = index), blockDetail.entries)
            }
        noteMap[snapshot.targetId] = NoteDetail(
            target.note.copy(title = snapshot.targetTitle, updatedAt = System.currentTimeMillis()),
            remaining
        )
        noteMap[snapshot.source.note.id] = snapshot.source
        true
    }

    // endregion

    override fun reminders(): List<Reminder> = synchronized(lock) {
        noteMap.values
            .asSequence()
            .filter { !it.note.trashed }
            .flatMap { detail ->
                detail.allEntries()
                    .filter { it.deadlineAt != null }
                    .map { entry ->
                        Reminder(
                            entryId = entry.id,
                            noteId = detail.note.id,
                            noteTitle = detail.note.title,
                            text = entry.text,
                            deadlineAt = entry.deadlineAt ?: 0L,
                            allDay = entry.allDay,
                            checked = entry.checked
                        )
                    }
            }
            .sortedBy { it.deadlineAt }
            .toList()
    }

    // region demo data

    private fun seed(now: Long) {
        val work = addFolder("Work", now - 90_000)
        val shopping = addFolder("Shopping", now - 80_000)

        fun demo(
            title: String,
            folder: Long,
            color: NoteColor,
            pinned: Boolean = false,
            archived: Boolean = false,
            trashed: Boolean = false,
            ageHours: Long = 1,
            blocks: MutableList<BlockDraftHolder>
        ) {
            val noteId = newId()
            val createdAt = now - ageHours * 3_600_000L
            val stored = blocks.mapIndexed { bi, holder ->
                val blockId = newId()
                BlockDetail(
                    Block(blockId, noteId, bi, holder.heading, null),
                    holder.entries.mapIndexed { ei, e ->
                        Entry(
                            id = newId(),
                            blockId = blockId,
                            position = ei,
                            type = e.type,
                            text = e.text,
                            checked = e.checked,
                            priority = e.priority,
                            deadlineAt = e.deadline,
                            allDay = e.allDay
                        )
                    }
                )
            }
            noteMap[noteId] = NoteDetail(
                Note(noteId, folder, title, color, pinned, archived, trashed, createdAt, createdAt),
                stored
            )
        }

        demo(
            title = "Sprint 12 planning",
            folder = work.id,
            color = NoteColor.YELLOW,
            pinned = true,
            ageHours = 5,
            blocks = mutableListOf(
                BlockDraftHolder(
                    heading = "",
                    entries = mutableListOf(
                        DemoEntry("Draft the API contract", EntryType.CHECKBOX, checked = true),
                        DemoEntry("Review pull requests", EntryType.CHECKBOX, checked = true),
                        DemoEntry("Write the migration notes", EntryType.CHECKBOX, priority = Priority.HIGHEST),
                        DemoEntry("Book the demo room", EntryType.CHECKBOX),
                        DemoEntry("Send the agenda", EntryType.CHECKBOX, deadline = now + 20L * 3_600_000L)
                    )
                )
            )
        )

        demo(
            title = "Groceries",
            folder = shopping.id,
            color = NoteColor.GREEN,
            ageHours = 12,
            blocks = mutableListOf(
                BlockDraftHolder(
                    heading = "",
                    entries = mutableListOf(
                        DemoEntry("Oat milk", EntryType.CHECKBOX, checked = true),
                        DemoEntry("Oranges", EntryType.CHECKBOX),
                        DemoEntry("Coffee beans", EntryType.CHECKBOX, priority = Priority.MEDIUM),
                        DemoEntry("Sourdough", EntryType.CHECKBOX)
                    )
                )
            )
        )

        demo(
            title = "Reading list",
            folder = work.id,
            color = NoteColor.BLUE,
            ageHours = 26,
            blocks = mutableListOf(
                BlockDraftHolder(
                    heading = "**Now**",
                    entries = mutableListOf(
                        DemoEntry("The Pragmatic Programmer", EntryType.BULLET),
                        DemoEntry("Designing Data-Intensive Applications", EntryType.BULLET)
                    )
                ),
                BlockDraftHolder(
                    heading = "Later",
                    entries = mutableListOf(
                        DemoEntry("Kotlin in Action", EntryType.BULLET, priority = Priority.HIGH)
                    )
                )
            )
        )

        demo(
            title = "Ideas",
            folder = inbox,
            color = NoteColor.PURPLE,
            ageHours = 40,
            blocks = mutableListOf(
                BlockDraftHolder(
                    heading = "",
                    entries = mutableListOf(
                        DemoEntry("A *markdown* preview on every card.", EntryType.TEXT),
                        DemoEntry("Offline first, no accounts, no sync.", EntryType.TEXT)
                    )
                )
            )
        )

        demo(
            title = "Call the dentist",
            folder = inbox,
            color = NoteColor.TEAL,
            ageHours = 3,
            blocks = mutableListOf(
                BlockDraftHolder(
                    heading = "",
                    entries = mutableListOf(
                        DemoEntry("Ask about the Friday slot", EntryType.CHECKBOX, deadline = now + 4L * 3_600_000L)
                    )
                )
            )
        )

        demo(
            title = "Old meeting notes",
            folder = work.id,
            color = NoteColor.GRAY,
            archived = true,
            ageHours = 240,
            blocks = mutableListOf(
                BlockDraftHolder(
                    heading = "",
                    entries = mutableListOf(DemoEntry("Decisions from the retro.", EntryType.TEXT))
                )
            )
        )

        demo(
            title = "Scratch",
            folder = inbox,
            color = NoteColor.RED,
            trashed = true,
            ageHours = 60,
            blocks = mutableListOf(
                BlockDraftHolder(
                    heading = "",
                    entries = mutableListOf(DemoEntry("Half-written thought.", EntryType.TEXT))
                )
            )
        )
    }

    private class BlockDraftHolder(val heading: String, val entries: MutableList<DemoEntry>)

    private class DemoEntry(
        val text: String,
        val type: EntryType,
        val checked: Boolean = false,
        val priority: Priority = Priority.NONE,
        val deadline: Long? = null,
        val allDay: Boolean = false
    )

    // endregion
}

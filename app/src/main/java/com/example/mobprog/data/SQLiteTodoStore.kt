
package com.example.mobprog.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import com.example.mobprog.alarm.AlarmScheduler
import com.example.mobprog.model.*
import java.io.File
import java.util.UUID

/** Persistent implementation of the frontend's TodoStore contract. */
class SQLiteTodoStore(context: Context) : TodoStore {
    private val app = context.applicationContext
    private val helper = TodoDatabaseHelper(app)

    private data class UndoSnapshot(
        val source: NoteDetail,
        val targetId: Long,
        val targetTitle: String
    )

    private val undo = mutableMapOf<String, UndoSnapshot>()

    private fun values(vararg pairs: Pair<String, Any?>): ContentValues =
        ContentValues().apply {
            pairs.forEach { (key, value) ->
                when (value) {
                    null -> putNull(key)
                    is String -> put(key, value)
                    is Long -> put(key, value)
                    is Int -> put(key, value)
                    is Boolean -> put(key, if (value) 1 else 0)
                    else -> error("Unsupported SQLite value: $key")
                }
            }
        }

    private fun Cursor.bool(i: Int) = getInt(i) != 0

    private fun Cursor.longOrNull(i: Int): Long? =
        if (isNull(i)) null else getLong(i)

    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun ensureInbox(db: SQLiteDatabase): Long {
        db.rawQuery(
            "SELECT id FROM folders WHERE name = ? COLLATE NOCASE",
            arrayOf("Inbox")
        ).use {
            if (it.moveToFirst()) return it.getLong(0)
        }

        return db.insertOrThrow(
            "folders",
            null,
            values(
                "name" to "Inbox",
                "created_at" to System.currentTimeMillis()
            )
        )
    }

    override fun inboxId(): Long =
        ensureInbox(helper.writableDatabase)

    override fun folders(): List<Folder> {
        val result = mutableListOf<Folder>()

        helper.readableDatabase.rawQuery(
            """
            SELECT id, name, created_at FROM folders
            ORDER BY CASE WHEN name='Inbox' THEN 0 ELSE 1 END,
            created_at
            """.trimIndent(),
            null
        ).use {
            while (it.moveToNext()) {
                result.add(
                    Folder(
                        it.getLong(0),
                        it.getString(1),
                        it.getLong(2)
                    )
                )
            }
        }
        return result
    }

    override fun folder(id: Long): Folder? =
        helper.readableDatabase.rawQuery(
            "SELECT id,name,created_at FROM folders WHERE id=?",
            arrayOf(id.toString())
        ).use {
            if (it.moveToFirst()) {
                Folder(
                    it.getLong(0),
                    it.getString(1),
                    it.getLong(2)
                )
            } else null
        }

    override fun createFolder(name: String): FolderNameResult {
        val clean = name.trim()
        if (clean.isEmpty()) return FolderNameResult.Blank

        return try {
            val now = System.currentTimeMillis()
            val id = helper.writableDatabase.insertOrThrow(
                "folders",
                null,
                values(
                    "name" to clean,
                    "created_at" to now
                )
            )
            FolderNameResult.Created(Folder(id, clean, now))
        } catch (_: SQLiteConstraintException) {
            FolderNameResult.Duplicate
        }
    }

    override fun renameFolder(
        id: Long,
        newName: String
    ): FolderNameResult {
        val existing = folder(id)
            ?: return FolderNameResult.NotFound

        val clean = newName.trim()
        if (clean.isEmpty()) return FolderNameResult.Blank

        if (id == inboxId() && !clean.equals("Inbox", true)) {
            return FolderNameResult.NotFound
        }

        return try {
            helper.writableDatabase.update(
                "folders",
                values("name" to clean),
                "id=?",
                arrayOf(id.toString())
            )

            val affected = noteIdsForFolder(id)
            affected.forEach {
                note(it)?.let(::writeMarkdown)
            }

            FolderNameResult.Renamed(
                existing.copy(name = clean)
            )
        } catch (_: SQLiteConstraintException) {
            FolderNameResult.Duplicate
        }
    }

    private fun noteIdsForFolder(id: Long): List<Long> {
        val ids = mutableListOf<Long>()

        helper.readableDatabase.rawQuery(
            "SELECT id FROM notes WHERE folder_id=?",
            arrayOf(id.toString())
        ).use { c ->
            while (c.moveToNext()) {
                ids.add(c.getLong(0))
            }
        }

        return ids
    }

    override fun deleteFolder(id: Long): Boolean {
        if (id == inboxId() || folder(id) == null) {
            return false
        }

        val affected = noteIdsForFolder(id)

        val removed = transaction { db ->
            db.update(
                "notes",
                values("folder_id" to ensureInbox(db)),
                "folder_id=?",
                arrayOf(id.toString())
            )

            db.delete(
                "folders",
                "id=?",
                arrayOf(id.toString())
            ) == 1
        }

        if (removed) {
            affected.forEach {
                note(it)?.let(::writeMarkdown)
            }
        }

        return removed
    }

    override fun folderNoteCounts(): Map<Long, Int> {
        val counts = mutableMapOf<Long, Int>()

        helper.readableDatabase.rawQuery(
            """
            SELECT folder_id,COUNT(*) FROM notes
            WHERE archived=0 AND trashed=0
            GROUP BY folder_id
            """.trimIndent(),
            null
        ).use {
            while (it.moveToNext()) {
                counts[it.getLong(0)] = it.getInt(1)
            }
        }

        return counts
    }

    private fun readNote(
        db: SQLiteDatabase,
        id: Long
    ): NoteDetail? {
        val n = db.rawQuery(
            """
            SELECT id,folder_id,title,color,pinned,archived,
            trashed,created_at,updated_at
            FROM notes WHERE id=?
            """.trimIndent(),
            arrayOf(id.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null

            Note(
                c.getLong(0),
                c.getLong(1),
                c.getString(2),
                NoteColor.fromKey(c.getString(3)),
                c.bool(4),
                c.bool(5),
                c.bool(6),
                c.getLong(7),
                c.getLong(8)
            )
        }

        val blocks = mutableListOf<BlockDetail>()

        db.rawQuery(
            """
            SELECT id,note_id,position,heading,origin_note_id
            FROM blocks WHERE note_id=?
            ORDER BY position,id
            """.trimIndent(),
            arrayOf(id.toString())
        ).use { b ->
            while (b.moveToNext()) {
                val block = Block(
                    b.getLong(0),
                    b.getLong(1),
                    b.getInt(2),
                    b.getString(3),
                    b.longOrNull(4)
                )

                val entries = mutableListOf<Entry>()

                db.rawQuery(
                    """
                    SELECT id,block_id,position,type,text,
                    checked,priority,deadline_at,all_day
                    FROM entries WHERE block_id=?
                    ORDER BY position,id
                    """.trimIndent(),
                    arrayOf(block.id.toString())
                ).use { e ->
                    while (e.moveToNext()) {
                        entries.add(
                            Entry(
                                e.getLong(0),
                                e.getLong(1),
                                e.getInt(2),
                                EntryType.fromName(e.getString(3)),
                                e.getString(4),
                                e.bool(5),
                                Priority.fromValue(e.getInt(6)),
                                e.longOrNull(7),
                                e.bool(8)
                            )
                        )
                    }
                }

                blocks.add(BlockDetail(block, entries))
            }
        }

        return NoteDetail(n, blocks)
    }

    override fun note(id: Long): NoteDetail? =
        readNote(helper.readableDatabase, id)

    private fun save(
        db: SQLiteDatabase,
        detail: NoteDetail
    ): Long {
        val now = System.currentTimeMillis()

        val previous =
            if (detail.note.id > 0) {
                readNote(db, detail.note.id)
            } else null

        val folderId = if (
            db.rawQuery(
                "SELECT id FROM folders WHERE id=?",
                arrayOf(detail.note.folderId.toString())
            ).use { it.moveToFirst() }
        ) {
            detail.note.folderId
        } else {
            ensureInbox(db)
        }

        val noteValues = values(
            "folder_id" to folderId,
            "title" to detail.note.title,
            "color" to detail.note.color.key,
            "pinned" to detail.note.pinned,
            "archived" to detail.note.archived,
            "trashed" to detail.note.trashed,
            "created_at" to (previous?.note?.createdAt ?: now),
            "updated_at" to now
        )

        val id = if (previous == null) {
            db.insertOrThrow("notes", null, noteValues)
        } else {
            db.update(
                "notes",
                noteValues,
                "id=?",
                arrayOf(detail.note.id.toString())
            )
            detail.note.id
        }

        db.delete(
            "blocks",
            "note_id=?",
            arrayOf(id.toString())
        )

        detail.blocks.forEachIndexed { bi, bd ->
            val blockValues = values(
                "note_id" to id,
                "position" to bi,
                "heading" to bd.block.heading,
                "origin_note_id" to bd.block.originNoteId
            )

            if (bd.block.id > 0) {
                blockValues.put("id", bd.block.id)
            }

            val blockId = db.insertOrThrow(
                "blocks",
                null,
                blockValues
            )

            bd.entries.forEachIndexed { ei, entry ->
                val ev = values(
                    "block_id" to blockId,
                    "position" to ei,
                    "type" to entry.type.name,
                    "text" to entry.text,
                    "checked" to entry.checked,
                    "priority" to entry.priority.value,
                    "deadline_at" to entry.deadlineAt,
                    "all_day" to entry.allDay
                )

                if (entry.id > 0) {
                    ev.put("id", entry.id)
                }

                db.insertOrThrow("entries", null, ev)
            }
        }

        return id
    }

    override fun saveNote(detail: NoteDetail): Long {
        val previous =
            if (detail.note.id > 0) {
                note(detail.note.id)
            } else null

        val id = transaction {
            save(it, detail)
        }

        val updated = note(id)

        val currentIds = updated?.allEntries()
            ?.map { it.id }
            ?.toSet()
            .orEmpty()

        previous?.allEntries()
            ?.filter { it.id !in currentIds }
            ?.forEach {
                AlarmScheduler.cancelEntry(app, it.id)
            }

        updated?.let {
            writeMarkdown(it)

            if (it.note.trashed || it.note.archived) {
                AlarmScheduler.cancelNote(app, it)
            } else {
                AlarmScheduler.syncNote(app, it)
            }
        }

        return id
    }

    private fun updateNote(
        id: Long,
        values: ContentValues
    ) {
        values.put(
            "updated_at",
            System.currentTimeMillis()
        )

        helper.writableDatabase.update(
            "notes",
            values,
            "id=?",
            arrayOf(id.toString())
        )

        note(id)?.let(::writeMarkdown)
    }

    override fun setPinned(id: Long, pinned: Boolean) =
        updateNote(id, values("pinned" to pinned))

    override fun setArchived(id: Long, archived: Boolean) {
        updateNote(id, values("archived" to archived))

        note(id)?.let {
            if (archived) {
                AlarmScheduler.cancelNote(app, it)
            } else if (!it.note.trashed) {
                AlarmScheduler.syncNote(app, it)
            }
        }
    }

    override fun moveNote(
        id: Long,
        folderId: Long
    ): Boolean {
        if (folder(folderId) == null || note(id) == null) {
            return false
        }

        updateNote(
            id,
            values("folder_id" to folderId)
        )

        return true
    }

    override fun trashNote(id: Long) {
        note(id)?.let {
            AlarmScheduler.cancelNote(app, it)
        }

        updateNote(id, values("trashed" to true))
    }

    override fun restoreNote(id: Long) {
        updateNote(id, values("trashed" to false))

        note(id)?.let {
            AlarmScheduler.syncNote(app, it)
        }
    }

    override fun deleteForever(id: Long) {
        note(id)?.let {
            AlarmScheduler.cancelNote(app, it)
            deleteMirror(it.note.id)
        }

        helper.writableDatabase.delete(
            "notes",
            "id=?",
            arrayOf(id.toString())
        )
    }

    override fun emptyTrash() {
        val ids = mutableListOf<Long>()

        helper.readableDatabase.rawQuery(
            "SELECT id FROM notes WHERE trashed=1",
            null
        ).use {
            while (it.moveToNext()) {
                ids.add(it.getLong(0))
            }
        }

        ids.forEach(::deleteForever)
    }

    override fun markChecked(
        entryId: Long,
        checked: Boolean
    ) {
        val noteId = helper.readableDatabase.rawQuery(
            """
            SELECT b.note_id FROM entries e
            JOIN blocks b ON b.id=e.block_id
            WHERE e.id=?
            """.trimIndent(),
            arrayOf(entryId.toString())
        ).use {
            if (it.moveToFirst()) {
                it.getLong(0)
            } else {
                return
            }
        }

        transaction { db ->
            db.update(
                "entries",
                values("checked" to checked),
                "id=?",
                arrayOf(entryId.toString())
            )

            db.update(
                "notes",
                values(
                    "updated_at" to System.currentTimeMillis()
                ),
                "id=?",
                arrayOf(noteId.toString())
            )
        }

        note(noteId)?.let {
            writeMarkdown(it)
            AlarmScheduler.syncNote(app, it)
        }
    }

    private fun summary(detail: NoteDetail): NoteSummary {
        val entries = detail.allEntries()

        val checkboxes = entries.filter {
            it.type == EntryType.CHECKBOX
        }

        val priority = entries.map { it.priority }
            .filter { it != Priority.NONE }
            .minByOrNull { it.value }
            ?: Priority.NONE

        val nearest = entries
            .filter { it.deadlineAt != null }
            .minByOrNull {
                it.deadlineAt ?: Long.MAX_VALUE
            }

        val markdown = buildString {
            detail.blocks.forEach { b ->
                if (b.block.heading.isNotBlank()) {
                    // Two trailing spaces make a Markdown hard break; a single
                    // newline is a soft break and would join the next line.
                    append("**${b.block.heading}**  \n")
                }

                b.entries.forEach { e ->
                    if (
                        e.type != EntryType.CHECKBOX &&
                        e.text.isNotBlank()
                    ) {
                        append(
                            when (e.type) {
                                EntryType.BULLET -> "• "
                                EntryType.NUMBERED -> "1. "
                                else -> ""
                            }
                        )

                        append(e.text).append("  \n")
                    }
                }
            }
        }.trim().take(600)

        return NoteSummary(
            detail.note.id,
            detail.note.folderId,
            folder(detail.note.folderId)?.name.orEmpty(),
            detail.note.title,
            detail.note.color,
            detail.note.pinned,
            detail.note.archived,
            detail.note.trashed,
            detail.note.createdAt,
            detail.note.updatedAt,
            markdown,
            checkboxes.filter { !it.checked }
                .map { it.text }
                .filter { it.isNotBlank() }
                .take(4),
            checkboxes.count { it.checked },
            checkboxes.size,
            priority,
            nearest?.deadlineAt,
            nearest?.allDay ?: false
        )
    }

    override fun notes(query: NoteQuery): List<NoteSummary> {
        val ids = mutableListOf<Long>()

        helper.readableDatabase.rawQuery(
            "SELECT id FROM notes",
            null
        ).use {
            while (it.moveToNext()) {
                ids.add(it.getLong(0))
            }
        }

        val needle = query.search.trim()

        return ids.mapNotNull(::note).filter { d ->
            val n = d.note

            val view = when (query.view) {
                NoteView.NOTES ->
                    !n.archived && !n.trashed

                NoteView.FOLDER ->
                    !n.archived && !n.trashed &&
                            n.folderId == query.folderId

                NoteView.ARCHIVE ->
                    n.archived && !n.trashed

                NoteView.TRASH ->
                    n.trashed

                NoteView.REMINDERS ->
                    !n.archived && !n.trashed &&
                            d.allEntries().any {
                                it.deadlineAt != null
                            }
            }

            val search = needle.isEmpty() ||
                    n.title.contains(needle, true) ||
                    d.blocks.any { b ->
                        b.block.heading.contains(
                            needle,
                            true
                        ) ||
                                b.entries.any {
                                    it.text.contains(needle, true)
                                }
                    }

            val f = query.filter

            val checks = d.allEntries().filter {
                it.type == EntryType.CHECKBOX
            }

            val p = d.allEntries()
                .map { it.priority }
                .filter { it != Priority.NONE }
                .minByOrNull { it.value }
                ?: Priority.NONE

            view && search &&
                    (f.colors.isEmpty() || n.color in f.colors) &&
                    (f.priorities.isEmpty() || p in f.priorities) &&
                    (!f.hasDeadline || d.allEntries().any {
                        it.deadlineAt != null
                    }) &&
                    when (f.status) {
                        StatusFilter.ANY -> true

                        StatusFilter.OPEN ->
                            checks.any { !it.checked }

                        StatusFilter.DONE ->
                            checks.isNotEmpty() &&
                                    checks.all { it.checked }
                    }
        }.map(::summary).sortedWith(
            when (query.sort) {
                NoteSort.UPDATED ->
                    compareByDescending { it.updatedAt }

                NoteSort.CREATED ->
                    compareByDescending { it.createdAt }

                NoteSort.DEADLINE ->
                    compareBy {
                        it.nextDeadlineAt ?: Long.MAX_VALUE
                    }

                NoteSort.PRIORITY ->
                    compareBy {
                        if (
                            it.highestPriority == Priority.NONE
                        ) {
                            99
                        } else {
                            it.highestPriority.value
                        }
                    }

                NoteSort.TITLE ->
                    compareBy(String.CASE_INSENSITIVE_ORDER) {
                        it.title
                    }
            }
        )
    }

    override fun reminders(): List<Reminder> {
        val ids = mutableListOf<Long>()

        helper.readableDatabase.rawQuery(
            "SELECT id FROM notes WHERE trashed=0",
            null
        ).use {
            while (it.moveToNext()) {
                ids.add(it.getLong(0))
            }
        }

        return ids.mapNotNull(::note).flatMap { d ->
            d.allEntries()
                .filter { it.deadlineAt != null }
                .map { e ->
                    Reminder(
                        e.id,
                        d.note.id,
                        d.note.title,
                        e.text,
                        e.deadlineAt ?: 0L,
                        e.allDay,
                        e.checked
                    )
                }
        }.sortedBy { it.deadlineAt }
    }

    override fun appendNotes(
        sourceId: Long,
        targetId: Long
    ): AppendUndoToken? {
        if (sourceId == targetId) return null

        val source = note(sourceId) ?: return null
        val target = note(targetId) ?: return null

        if (source.note.trashed || target.note.trashed) {
            return null
        }

        val token = AppendUndoToken(
            UUID.randomUUID().toString()
        )

        val incoming = source.blocks.mapIndexed { index, b ->
            BlockDetail(
                b.block.copy(
                    id = 0L,
                    noteId = targetId,
                    heading = if (
                        index == 0 &&
                        source.note.title.isNotBlank()
                    ) {
                        source.note.title
                    } else {
                        b.block.heading
                    },
                    originNoteId = sourceId
                ),
                b.entries
            )
        }

        val merged = (
                target.blocks.map { it to 0 } +
                        incoming.map { it to 1 }
                ).sortedWith(
                compareBy(
                    {
                        if (
                            it.first.entries.any { e ->
                                e.priority == Priority.HIGHEST
                            }
                        ) 0 else 1
                    },
                    { it.second }
                )
            ).map { it.first }

        transaction { db ->
            // Cascading source deletion frees its entry IDs.
            db.delete(
                "notes",
                "id=?",
                arrayOf(sourceId.toString())
            )

            save(
                db,
                NoteDetail(
                    target.note.copy(title = ""),
                    merged
                )
            )
        }

        undo[token.id] = UndoSnapshot(
            source.deepCopy(),
            targetId,
            target.note.title
        )

        note(targetId)?.let {
            writeMarkdown(it)

            if (it.note.archived) {
                AlarmScheduler.cancelNote(app, it)
            } else {
                AlarmScheduler.syncNote(app, it)
            }
        }

        deleteMirror(sourceId)

        return token
    }

    override fun undoAppend(
        token: AppendUndoToken
    ): Boolean {
        val snapshot = undo[token.id] ?: return false
        val source = snapshot.source
        val target = note(snapshot.targetId) ?: return false

        if (note(source.note.id) != null) return false

        val appended = target.blocks.filter {
            it.block.originNoteId == source.note.id
        }

        if (appended.isEmpty()) return false

        val remaining = target.blocks.filterNot {
            it.block.originNoteId == source.note.id
        }

        // Preserve target edits and restore original source.
        transaction { db ->
            val restoredTarget = NoteDetail(
                target.note.copy(
                    title = snapshot.targetTitle
                ),
                remaining
            )

            save(db, restoredTarget)
            saveWithIds(db, source)
        }

        undo.remove(token.id)

        note(target.note.id)?.let {
            writeMarkdown(it)
            AlarmScheduler.syncNote(app, it)
        }

        note(source.note.id)?.let {
            writeMarkdown(it)
            AlarmScheduler.syncNote(app, it)
        }

        return true
    }

    private fun saveWithIds(
        db: SQLiteDatabase,
        detail: NoteDetail
    ) {
        val n = detail.note

        db.insertOrThrow(
            "notes",
            null,
            values(
                "id" to n.id,
                "folder_id" to n.folderId,
                "title" to n.title,
                "color" to n.color.key,
                "pinned" to n.pinned,
                "archived" to n.archived,
                "trashed" to n.trashed,
                "created_at" to n.createdAt,
                "updated_at" to n.updatedAt
            )
        )

        detail.blocks.forEachIndexed { bi, b ->
            db.insertOrThrow(
                "blocks",
                null,
                values(
                    "id" to b.block.id,
                    "note_id" to n.id,
                    "position" to bi,
                    "heading" to b.block.heading,
                    "origin_note_id" to b.block.originNoteId
                )
            )

            b.entries.forEachIndexed { ei, e ->
                db.insertOrThrow(
                    "entries",
                    null,
                    values(
                        "id" to e.id,
                        "block_id" to b.block.id,
                        "position" to ei,
                        "type" to e.type.name,
                        "text" to e.text,
                        "checked" to e.checked,
                        "priority" to e.priority.value,
                        "deadline_at" to e.deadlineAt,
                        "all_day" to e.allDay
                    )
                )
            }
        }
    }

    private fun deleteMirror(id: Long) {
        app.getExternalFilesDir("notes")
            ?.walkTopDown()
            ?.filter {
                it.isFile && it.name == "$id.md"
            }
            ?.forEach { it.delete() }
    }

    private fun writeMarkdown(detail: NoteDetail) {
        val base = app.getExternalFilesDir("notes")
            ?: return

        deleteMirror(detail.note.id)

        val folderName = folder(detail.note.folderId)
            ?.name
            .orEmpty()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")

        val dir = File(base, folderName).apply {
            mkdirs()
        }

        val markdown = buildString {
            if (detail.note.title.isNotBlank()) {
                append("# ")
                    .append(detail.note.title)
                    .append("\n\n")
            }

            detail.blocks.forEach { b ->
                if (b.block.heading.isNotBlank()) {
                    append("## ")
                        .append(b.block.heading)
                        .append('\n')
                }

                b.entries.forEach { e ->
                    append(
                        when (e.type) {
                            EntryType.TEXT -> ""
                            EntryType.BULLET -> "- "
                            EntryType.NUMBERED -> "1. "
                            EntryType.CHECKBOX ->
                                if (e.checked) {
                                    "- [x] "
                                } else {
                                    "- [ ] "
                                }
                        }
                    )

                    append(e.text).append('\n')
                }

                append('\n')
            }
        }

        File(
            dir,
            "${detail.note.id}.md"
        ).writeText(markdown)
    }
}

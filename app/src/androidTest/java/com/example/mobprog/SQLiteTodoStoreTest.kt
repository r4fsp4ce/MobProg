
package com.example.mobprog

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mobprog.data.*
import com.example.mobprog.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SQLiteTodoStoreTest {

    private lateinit var store: SQLiteTodoStore

    private val context
        get() = InstrumentationRegistry
            .getInstrumentation()
            .targetContext

    @Before
    fun setup() {
        context.deleteDatabase(
            TodoDatabaseHelper.DATABASE_NAME
        )
        store = SQLiteTodoStore(context)
    }

    @After
    fun cleanup() {
        context.deleteDatabase(
            TodoDatabaseHelper.DATABASE_NAME
        )
    }

    // HELPER: CREATE SAMPLE NOTE

    private fun sampleNote(title: String): NoteDetail {
        val note = Note(
            id = -1L,
            folderId = store.inboxId(),
            title = title,
            color = NoteColor.YELLOW,
            pinned = false,
            archived = false,
            trashed = false,
            createdAt = 0L,
            updatedAt = 0L
        )

        val block = Block(
            id = 0L,
            noteId = -1L,
            position = 0,
            heading = "",
            originNoteId = null
        )

        val entry = Entry(
            id = 0L,
            blockId = 0L,
            position = 0,
            type = EntryType.CHECKBOX,
            text = "Finish assignment",
            checked = false,
            priority = Priority.HIGHEST,
            deadlineAt = null,
            allDay = false
        )

        return NoteDetail(
            note,
            listOf(BlockDetail(block, listOf(entry)))
        )
    }

    // TEST 1: DATABASE INITIALIZATION

    @Test
    fun databaseCreatesInbox() {
        val inbox = store.folder(store.inboxId())
        assertNotNull(inbox)
        assertEquals("Inbox", inbox?.name)
    }

    // TEST 2: CREATE FOLDER

    @Test
    fun createFolderWorks() {
        val result = store.createFolder("School")
        assertTrue(result is FolderNameResult.Created)
        assertTrue(store.folders().any { it.name == "School" })
    }

    // TEST 3: DUPLICATE FOLDER VALIDATION

    @Test
    fun duplicateFolderIsRejected() {
        store.createFolder("School")
        val result = store.createFolder("school")
        assertEquals(FolderNameResult.Duplicate, result)
    }

    // TEST 4: RENAME FOLDER

    @Test
    fun renameFolderWorks() {
        val created = store.createFolder("School")
                as FolderNameResult.Created

        val result = store.renameFolder(
            created.folder.id,
            "College"
        )

        assertTrue(result is FolderNameResult.Renamed)
        assertEquals(
            "College",
            store.folder(created.folder.id)?.name
        )
    }

    // TEST 5: DELETE FOLDER

    @Test
    fun deleteFolderWorks() {
        val created = store.createFolder("School")
                as FolderNameResult.Created

        assertTrue(store.deleteFolder(created.folder.id))
        assertNull(store.folder(created.folder.id))
    }

    // TEST 6: CREATE AND READ NOTE

    @Test
    fun createAndReadNoteWorks() {
        val id = store.saveNote(sampleNote("Backend Test"))
        val saved = store.note(id)

        assertNotNull(saved)
        assertEquals("Backend Test", saved?.note?.title)
        assertEquals(
            "Finish assignment",
            saved?.allEntries()?.first()?.text
        )
    }

    // TEST 7: UPDATE NOTE

    @Test
    fun updateNoteWorks() {
        val id = store.saveNote(sampleNote("Original Title"))
        val existing = store.note(id)!!

        store.saveNote(
            existing.copy(
                note = existing.note.copy(
                    title = "Updated Title"
                )
            )
        )

        assertEquals(
            "Updated Title",
            store.note(id)?.note?.title
        )
    }

    // TEST 8: CHECKLIST UPDATE

    @Test
    fun checklistUpdateWorks() {
        val id = store.saveNote(sampleNote("Checklist Test"))
        val entryId = store.note(id)!!.allEntries().first().id

        store.markChecked(entryId, true)

        assertTrue(
            store.note(id)!!.allEntries().first().checked
        )
    }

    // TEST 9: TRASH AND RESTORE

    @Test
    fun trashAndRestoreWorks() {
        val id = store.saveNote(sampleNote("Trash Test"))

        store.trashNote(id)
        assertTrue(store.note(id)!!.note.trashed)

        store.restoreNote(id)
        assertFalse(store.note(id)!!.note.trashed)
    }

    // TEST 10: PERMANENT DELETE

    @Test
    fun deleteForeverWorks() {
        val id = store.saveNote(sampleNote("Delete Test"))
        store.deleteForever(id)
        assertNull(store.note(id))
    }

    // TEST 11: DATA PERSISTENCE

    @Test
    fun notePersistsAcrossStoreInstances() {
        val id = store.saveNote(sampleNote("Persistent Note"))
        val reopenedStore = SQLiteTodoStore(context)

        assertEquals(
            "Persistent Note",
            reopenedStore.note(id)?.note?.title
        )
    }

    // TEST 12: SEARCH NOTES

    @Test
    fun searchNotesWorks() {
        store.saveNote(sampleNote("Android Project"))
        store.saveNote(sampleNote("Shopping List"))

        val results = store.notes(
            NoteQuery(search = "Android")
        )

        assertEquals(1, results.size)
        assertEquals("Android Project", results.first().title)
    }

    // TEST 13: SORT NOTES BY TITLE

    @Test
    fun sortNotesByTitleWorks() {
        store.saveNote(sampleNote("Zebra"))
        store.saveNote(sampleNote("Apple"))

        val results = store.notes(
            NoteQuery(sort = NoteSort.TITLE)
        )

        assertEquals(
            listOf("Apple", "Zebra"),
            results.map { it.title }
        )
    }

    // TEST 14: FILTER NOTES BY COLOR

    @Test
    fun filterNotesByColorWorks() {
        store.saveNote(sampleNote("Yellow Note"))

        val results = store.notes(
            NoteQuery(
                filter = NoteFilter(
                    colors = setOf(NoteColor.YELLOW)
                )
            )
        )

        assertEquals(1, results.size)
        assertEquals("Yellow Note", results.first().title)
    }

    // TEST 15: MERGE TWO NOTES

    @Test
    fun mergeNotesWorks() {
        val sourceId = store.saveNote(sampleNote("Source Note"))
        val targetId = store.saveNote(sampleNote("Target Note"))

        val token = store.appendNotes(sourceId, targetId)

        assertNotNull(token)
        assertNull(store.note(sourceId))

        val merged = store.note(targetId)!!

        assertEquals("", merged.note.title)
        assertEquals(2, merged.allEntries().size)

        assertTrue(
            merged.blocks.any {
                it.block.originNoteId == sourceId
            }
        )
    }

    // TEST 16: UNDO MERGED NOTES

    @Test
    fun undoMergeWorks() {
        val sourceId = store.saveNote(sampleNote("Source Note"))
        val targetId = store.saveNote(sampleNote("Target Note"))

        val token = store.appendNotes(sourceId, targetId)

        assertNotNull(token)
        assertTrue(store.undoAppend(token!!))

        assertEquals(
            "Source Note",
            store.note(sourceId)?.note?.title
        )

        assertEquals(
            "Target Note",
            store.note(targetId)?.note?.title
        )
    }

    // TEST 17: FOLDER DELETION MOVES NOTES TO INBOX

    @Test
    fun deletingFolderMovesNotesToInbox() {
        val created = store.createFolder("School")
                as FolderNameResult.Created

        val detail = sampleNote("School Note")

        val noteId = store.saveNote(
            detail.copy(
                note = detail.note.copy(
                    folderId = created.folder.id
                )
            )
        )

        assertTrue(store.deleteFolder(created.folder.id))

        assertEquals(
            store.inboxId(),
            store.note(noteId)?.note?.folderId
        )
    }

    // TEST 18: MARKDOWN EXPORT

    @Test
    fun markdownExportWorks() {
        val id = store.saveNote(sampleNote("Markdown Export Test"))
        val base = context.getExternalFilesDir("notes")

        assertNotNull(base)

        val markdownFile = File(
            File(base!!, "Inbox"),
            "$id.md"
        )

        assertTrue(
            "Markdown file should exist",
            markdownFile.exists()
        )

        val content = markdownFile.readText()

        assertTrue(content.contains("# Markdown Export Test"))
        assertTrue(content.contains("Finish assignment"))
    }

    // TEST 19: DEADLINE PERSISTENCE

    @Test
    fun deadlinePersistsInDatabase() {
        val deadline = System.currentTimeMillis() + 86400000L
        val original = sampleNote("Deadline Test")

        val updatedBlock = original.blocks.first().copy(
            entries = original.blocks.first().entries.map {
                it.copy(deadlineAt = deadline)
            }
        )

        val id = store.saveNote(
            original.copy(blocks = listOf(updatedBlock))
        )

        val reopenedStore = SQLiteTodoStore(context)

        val savedEntry = reopenedStore.note(id)!!
            .allEntries().first()

        assertEquals(deadline, savedEntry.deadlineAt)
    }

    // TEST 20: REMINDERS QUERY

    @Test
    fun remindersReturnsSavedDeadline() {
        val deadline = System.currentTimeMillis() + 86400000L
        val original = sampleNote("Reminder Test")

        val updatedBlock = original.blocks.first().copy(
            entries = original.blocks.first().entries.map {
                it.copy(deadlineAt = deadline)
            }
        )

        val id = store.saveNote(
            original.copy(blocks = listOf(updatedBlock))
        )

        val reminders = store.reminders()

        assertEquals(1, reminders.size)
        assertEquals(id, reminders.first().noteId)
        assertEquals(deadline, reminders.first().deadlineAt)
    }

    // TEST 21: CHECKLIST REMINDER STATUS

    @Test
    fun reminderReflectsCheckedStatus() {
        val deadline = System.currentTimeMillis() + 86400000L
        val original = sampleNote("Checklist Reminder")

        val updatedBlock = original.blocks.first().copy(
            entries = original.blocks.first().entries.map {
                it.copy(deadlineAt = deadline)
            }
        )

        val id = store.saveNote(
            original.copy(blocks = listOf(updatedBlock))
        )

        val entryId = store.note(id)!!
            .allEntries().first().id

        store.markChecked(entryId, true)

        val reminder = store.reminders()
            .first { it.entryId == entryId }

        assertTrue(reminder.checked)
    }

    // TEST 22: ENTRY ID PRESERVATION AFTER UPDATE

    @Test
    fun entryIdRemainsSameAfterNoteUpdate() {
        val id = store.saveNote(sampleNote("Original Note"))

        val original = store.note(id)!!
        val originalEntryId = original.allEntries().first().id

        store.saveNote(
            original.copy(
                note = original.note.copy(
                    title = "Updated Note"
                )
            )
        )

        val updated = store.note(id)!!
        val updatedEntryId = updated.allEntries().first().id

        assertEquals(
            "Entry ID must remain unchanged after update",
            originalEntryId,
            updatedEntryId
        )
    }

    // TEST 23: PRESERVE ENTRY IDS AFTER MERGE

    @Test
    fun mergePreservesEntryIds() {
        val sourceId = store.saveNote(sampleNote("Source"))
        val targetId = store.saveNote(sampleNote("Target"))

        val sourceEntryId =
            store.note(sourceId)!!.allEntries().first().id

        val targetEntryId =
            store.note(targetId)!!.allEntries().first().id

        val token = store.appendNotes(sourceId, targetId)

        assertNotNull(token)

        val mergedEntryIds = store.note(targetId)!!
            .allEntries().map { it.id }

        assertTrue(mergedEntryIds.contains(sourceEntryId))
        assertTrue(mergedEntryIds.contains(targetEntryId))
    }

    // TEST 24: PRESERVE DEADLINES AFTER MERGE

    @Test
    fun mergePreservesDeadlines() {
        val deadline = System.currentTimeMillis() + 86400000L

        val source = sampleNote("Source")
        val sourceWithDeadline = source.copy(
            blocks = source.blocks.map { block ->
                block.copy(
                    entries = block.entries.map {
                        it.copy(deadlineAt = deadline)
                    }
                )
            }
        )

        val sourceId = store.saveNote(sourceWithDeadline)
        val targetId = store.saveNote(sampleNote("Target"))

        assertNotNull(store.appendNotes(sourceId, targetId))

        val merged = store.note(targetId)!!

        assertTrue(
            merged.allEntries().any {
                it.deadlineAt == deadline
            }
        )
    }

    // TEST 25: UNDO RESTORES IDS AND DEADLINES

    @Test
    fun undoMergeRestoresEntryIdsAndDeadlines() {
        val deadline = System.currentTimeMillis() + 86400000L

        val source = sampleNote("Source")
        val sourceWithDeadline = source.copy(
            blocks = source.blocks.map { block ->
                block.copy(
                    entries = block.entries.map {
                        it.copy(deadlineAt = deadline)
                    }
                )
            }
        )

        val sourceId = store.saveNote(sourceWithDeadline)
        val targetId = store.saveNote(sampleNote("Target"))

        val originalEntryId =
            store.note(sourceId)!!.allEntries().first().id

        val token = store.appendNotes(sourceId, targetId)

        assertNotNull(token)
        assertTrue(store.undoAppend(token!!))

        val restored = store.note(sourceId)!!
        val restoredEntry = restored.allEntries().first()

        assertEquals(originalEntryId, restoredEntry.id)
        assertEquals(deadline, restoredEntry.deadlineAt)
    }

    // TEST 26: UNDO PRESERVES TARGET EDITS

    @Test
    fun undoMergePreservesTargetEdits() {
        val sourceId = store.saveNote(
            sampleNote("Source Note")
        )
        val targetId = store.saveNote(
            sampleNote("Target Note")
        )

        val token = store.appendNotes(sourceId, targetId)
        assertNotNull(token)

        val merged = store.note(targetId)!!
        val targetBlock = merged.blocks.first {
            it.block.originNoteId == null
        }

        val editedBlocks = merged.blocks.map { block ->
            if (block.block.id == targetBlock.block.id) {
                block.copy(
                    entries = block.entries.map { entry ->
                        entry.copy(text = "Edited after merge")
                    }
                )
            } else {
                block
            }
        }

        store.saveNote(
            merged.copy(blocks = editedBlocks)
        )

        assertTrue(store.undoAppend(token!!))

        val restoredTarget = store.note(targetId)!!

        assertEquals("Target Note", restoredTarget.note.title)
        assertTrue(
            restoredTarget.allEntries().any {
                it.text == "Edited after merge"
            }
        )

        assertNotNull(store.note(sourceId))
    }

    // TEST 27: MARKDOWN MOVES AFTER FOLDER RENAME

    @Test
    fun markdownMovesAfterFolderRename() {
        val folder = store.createFolder("School")
                as FolderNameResult.Created

        val detail = sampleNote("School Note")
        val noteId = store.saveNote(
            detail.copy(
                note = detail.note.copy(
                    folderId = folder.folder.id
                )
            )
        )

        val base = context.getExternalFilesDir("notes")!!
        val oldFile = File(
            File(base, "School"),
            "$noteId.md"
        )

        assertTrue(oldFile.exists())

        val result = store.renameFolder(
            folder.folder.id,
            "College"
        )

        assertTrue(result is FolderNameResult.Renamed)

        val newFile = File(
            File(base, "College"),
            "$noteId.md"
        )

        assertTrue(
            "Markdown must exist in renamed folder",
            newFile.exists()
        )

        assertFalse(
            "Old Markdown file should be removed",
            oldFile.exists()
        )
    }

    // TEST 28: MARKDOWN MOVES AFTER FOLDER DELETE

    @Test
    fun markdownMovesAfterFolderDelete() {
        val folder = store.createFolder("School")
                as FolderNameResult.Created

        val detail = sampleNote("School Note")
        val noteId = store.saveNote(
            detail.copy(
                note = detail.note.copy(
                    folderId = folder.folder.id
                )
            )
        )

        val base = context.getExternalFilesDir("notes")!!
        val oldFile = File(
            File(base, "School"),
            "$noteId.md"
        )

        assertTrue(oldFile.exists())
        assertTrue(store.deleteFolder(folder.folder.id))

        val inboxFile = File(
            File(base, "Inbox"),
            "$noteId.md"
        )

        assertTrue(
            "Markdown must move to Inbox",
            inboxFile.exists()
        )

        assertFalse(
            "Old Markdown file should be removed",
            oldFile.exists()
        )
    }
}

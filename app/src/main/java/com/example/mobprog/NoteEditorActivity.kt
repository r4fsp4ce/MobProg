package com.example.mobprog

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.example.mobprog.alarm.AlarmScheduler
import com.example.mobprog.data.StoreProvider
import com.example.mobprog.data.TodoStore
import com.example.mobprog.databinding.ActivityNoteEditorBinding
import com.example.mobprog.model.Block
import com.example.mobprog.model.BlockDetail
import com.example.mobprog.model.Entry
import com.example.mobprog.model.EntryType
import com.example.mobprog.model.Note
import com.example.mobprog.model.NoteColor
import com.example.mobprog.model.NoteDetail
import com.example.mobprog.model.NoteDraft
import com.example.mobprog.model.Priority
import com.example.mobprog.ui.BlockEditorView
import com.example.mobprog.ui.DateTimeDialog
import com.example.mobprog.ui.EntryRowView
import com.example.mobprog.ui.RibbonView
import com.example.mobprog.util.EditorPrefs
import com.example.mobprog.util.Insets
import com.example.mobprog.util.NoteMarkdown
import com.example.mobprog.util.Threads
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.ArrayDeque

/**
 * Full editor: title, a vertical stack of blocks, and the shared ribbon.
 * Undo/redo keeps in-memory snapshots of the draft.
 */
class NoteEditorActivity : AppCompatActivity(), RibbonView.Listener, BlockEditorView.Callback {

    private lateinit var binding: ActivityNoteEditorBinding
    private lateinit var ribbon: RibbonView

    private val store: TodoStore get() = StoreProvider.get()

    private var draft: NoteDraft = NoteDraft()
    private var loadedNote: Note? = null
    private var original: NoteDraft? = null
    private var noteId: Long = -1L
    private var initialFolderId: Long = -1L
    private var focusedRow: EntryRowView? = null
    private var editorFocused = false

    private val blockViews = mutableListOf<BlockEditorView>()
    private val undoShelf = ArrayDeque<NoteDraft>()
    private val redoShelf = ArrayDeque<NoteDraft>()

    private var loading = true
    private var textSnapshotTaken = false
    private var textBaseline: NoteDraft? = null

    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val folderId = result.data?.getLongExtra(FoldersActivity.EXTRA_FOLDER_ID, -1L) ?: -1L
            if (folderId <= 0L) return@registerForActivityResult
            draft.folderId = folderId
            updateFolderChip()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityNoteEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.applySystemBarsAndIme(binding.editorRoot)

        ribbon = binding.ribbon
        ribbon.listener = this
        ribbon.showUndoRedo(true)
        ribbon.showRawMarkdown(false)

        noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
        initialFolderId = intent.getLongExtra(EXTRA_FOLDER_ID, -1L)

        binding.toolbar.setNavigationOnClickListener { requestDiscard() }
        binding.toolbar.inflateMenu(R.menu.editor_toolbar)
        binding.toolbar.menu.findItem(R.id.action_delete_note)?.isVisible = noteId > 0
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_delete_note) {
                confirmDelete()
                true
            } else {
                false
            }
        }

        binding.folderChip.setOnClickListener { pickFolder() }
        binding.folderChip.contentDescription = getString(R.string.action_change_folder)
        binding.titleInput.doAfterTextChanged {
            if (!loading) noteTextChanged()
        }
        binding.titleInput.setOnFocusChangeListener { _, hasFocus ->
            editorFocused = hasFocus
            refreshRawMarkdownButton()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = requestDiscard()
        })

        load()
    }

    override fun onResume() {
        super.onResume()
        refreshRawMarkdownButton()
    }

    // region loading and rendering

    private fun load() {
        if (noteId > 0) {
            Threads.load({ store.note(noteId) }) { detail ->
                if (detail == null) {
                    finish()
                    return@load
                }
                loadedNote = detail.note
                draft = NoteDraft.from(detail)
                binding.toolbar.title = ""
                renderAll()
                original = collectDraft()
            }
        } else {
            draft = NoteDraft(
                folderId = if (initialFolderId > 0) initialFolderId else store.inboxId()
            )
            binding.toolbar.title = getString(R.string.editor_new_note)
            renderAll()
            original = collectDraft()
        }
    }

    private fun renderAll() {
        loading = true
        binding.titleInput.setText(draft.title)
        rebuildBlocks()
        updateFolderChip()
        updateRibbon()
        loading = false
        textSnapshotTaken = false
        textBaseline = collectDraft()
        updateUndoRedo()
        refreshRawMarkdownButton()
    }

    private fun rebuildBlocks() {
        binding.blockContainer.removeAllViews()
        blockViews.clear()
        draft.blocks.forEachIndexed { index, block ->
            val view = BlockEditorView(this)
            view.callback = this
            view.bind(block, index, draft.blocks.size)
            binding.blockContainer.addView(
                view,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            blockViews += view
        }
        focusedRow = null
        editorFocused = false
        if (blockViews.isEmpty()) {
            addBlock()
        }
    }

    private fun updateFolderChip() {
        val folderId = draft.folderId
        Threads.load({ store.folder(folderId) }) { folder ->
            binding.folderChip.text = folder?.name ?: getString(R.string.inbox_name)
        }
    }

    private fun updateRibbon() {
        val row = activeRow()
        ribbon.showListType(row?.draft?.type ?: EntryType.CHECKBOX)
        ribbon.showPriority(row?.draft?.priority ?: Priority.NONE)
        ribbon.showColor(draft.color)
    }

    private fun updateUndoRedo() {
        ribbon.setUndoEnabled(undoShelf.isNotEmpty())
        ribbon.setRedoEnabled(redoShelf.isNotEmpty())
    }

    // endregion

    // region draft handling

    private fun collectDraft(): NoteDraft = draft.copyDraft().also { collected ->
        collected.title = binding.titleInput.text?.toString().orEmpty()
        collected.blocks = blockViews.map { it.collect() }.toMutableList()
    }

    private fun activeRow(): EntryRowView? =
        focusedRow ?: blockViews.firstOrNull()?.rows()?.lastOrNull()

    private fun blockContaining(row: EntryRowView): BlockEditorView? =
        blockViews.firstOrNull { block -> block.rows().contains(row) }

    private fun addBlock() {
        draft.blocks.add(com.example.mobprog.model.BlockDraft())
        rebuildBlocks()
    }

    private fun pushUndo(snapshot: NoteDraft) {
        undoShelf.addLast(snapshot)
        while (undoShelf.size > MAX_HISTORY) undoShelf.removeFirst()
        redoShelf.clear()
        textSnapshotTaken = true
        textBaseline = null
        updateUndoRedo()
    }

    private fun noteTextChanged() {
        binding.editorError.isVisible = false
        if (textSnapshotTaken) return
        val baseline = textBaseline ?: return
        undoShelf.addLast(baseline)
        while (undoShelf.size > MAX_HISTORY) undoShelf.removeFirst()
        redoShelf.clear()
        textSnapshotTaken = true
        updateUndoRedo()
    }

    private fun undo() {
        if (undoShelf.isEmpty()) return
        redoShelf.addLast(collectDraft())
        draft = undoShelf.removeLast()
        renderAll()
    }

    private fun redo() {
        if (redoShelf.isEmpty()) return
        undoShelf.addLast(collectDraft())
        draft = redoShelf.removeLast()
        renderAll()
    }

    private fun isDirty(): Boolean {
        val opened = original ?: return false
        return collectDraft() != opened
    }

    private fun toDetail(source: NoteDraft): NoteDetail {
        val base = loadedNote
        val note = Note(
            id = source.id,
            folderId = source.folderId,
            title = source.title.trim(),
            color = source.color,
            pinned = source.pinned,
            archived = base?.archived ?: false,
            trashed = base?.trashed ?: false,
            createdAt = base?.createdAt ?: 0L,
            updatedAt = base?.updatedAt ?: 0L
        )
        val blocks = source.blocks.mapIndexed { blockIndex, blockDraft ->
            val blockId = blockDraft.id
            BlockDetail(
                block = Block(
                    id = blockId,
                    noteId = source.id,
                    position = blockIndex,
                    heading = blockDraft.heading.trim(),
                    originNoteId = blockDraft.originNoteId
                ),
                entries = blockDraft.entries.mapIndexed { entryIndex, entry ->
                    Entry(
                        id = entry.id,
                        blockId = blockId,
                        position = entryIndex,
                        type = entry.type,
                        text = entry.text.trim(),
                        checked = entry.checked,
                        priority = entry.priority,
                        deadlineAt = entry.deadlineAt,
                        allDay = entry.allDay
                    )
                }
            )
        }
        return NoteDetail(note, blocks)
    }

    // endregion

    // region ribbon

    override fun onListType() {
        pushUndo(collectDraft())
        val row = activeRow()
        val block = row?.let { blockContaining(it) }
        if (row != null && block != null) {
            val next = row.draft.type.next()
            block.setEntryType(row, next)
            ribbon.showListType(next)
            focusedRow = row
        } else {
            val first = blockViews.firstOrNull() ?: return
            first.addEntry(type = EntryType.CHECKBOX, requestFocus = true)
            ribbon.showListType(EntryType.CHECKBOX)
        }
    }

    override fun onClock() {
        val row = activeRow()
        if (row == null) {
            toast(R.string.editor_error_no_item)
            return
        }
        pushUndo(collectDraft())
        focusedRow = row
        showDateTime(row)
    }

    override fun onPriority() {
        val row = activeRow()
        if (row == null) {
            toast(R.string.editor_error_no_item)
            return
        }
        val anchor: View = ribbon.priorityAnchor
        val popup = PopupMenu(this, anchor)
        val options = listOf(Priority.HIGHEST, Priority.HIGH, Priority.MEDIUM, Priority.NONE)
        options.forEachIndexed { index, priority ->
            popup.menu.add(0, priority.value, index, getString(priority.labelRes))
        }
        popup.setOnMenuItemClickListener { item ->
            val chosen = Priority.fromValue(item.itemId)
            pushUndo(collectDraft())
            row.draft.priority = chosen
            row.applyPriority()
            ribbon.showPriority(chosen)
            true
        }
        popup.show()
    }

    override fun onColor() {
        val popup = PopupMenu(this, ribbon.colorAnchor)
        NoteColor.entries.forEachIndexed { index, color ->
            val item = popup.menu.add(0, index, index, getString(color.labelRes))
            item.icon = colorDot(color)
        }
        popup.setOnMenuItemClickListener { item ->
            val color = NoteColor.entries[item.itemId]
            pushUndo(collectDraft())
            draft.color = color
            ribbon.showColor(color)
            true
        }
        popup.show()
    }

    override fun onUndo() = undo()

    override fun onRedo() = redo()

    override fun onDiscard() = requestDiscard()

    override fun onSave() = save()

    override fun onRawMarkdown() = showRawMarkdown()

    /** The button is opt-in and only shows while a field has the cursor. */
    private fun refreshRawMarkdownButton() {
        ribbon.showRawMarkdown(EditorPrefs.isRawMarkdownEnabled(this) && editorFocused)
    }

    private fun showRawMarkdown() {
        val markdown = NoteMarkdown.from(collectDraft())
        val pad = (20 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            text = markdown.ifBlank { getString(R.string.raw_markdown_empty) }
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextIsSelectable(true)
            setTextColor(ContextCompat.getColor(this@NoteEditorActivity, R.color.color_on_surface))
            setPadding(pad, pad, pad, pad)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.raw_markdown_title)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    // endregion

    // region block callbacks

    override fun onBlockUp(view: BlockEditorView) = moveBlock(view, -1)

    override fun onBlockDown(view: BlockEditorView) = moveBlock(view, 1)

    override fun onBlockDelete(view: BlockEditorView) {
        if (blockViews.size <= 1) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.editor_delete_block_title)
            .setMessage(R.string.editor_delete_block_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val index = blockViews.indexOf(view)
                if (index < 0) return@setPositiveButton
                pushUndo(collectDraft())
                draft.blocks.removeAt(index)
                rebuildBlocks()
            }
            .show()
    }

    override fun onEntryFocused(row: EntryRowView) {
        focusedRow = row
        editorFocused = true
        ribbon.showListType(row.draft.type)
        ribbon.showPriority(row.draft.priority)
        refreshRawMarkdownButton()
    }

    override fun onEntryBlur(row: EntryRowView) {
        editorFocused = binding.titleInput.hasFocus()
        refreshRawMarkdownButton()
    }

    override fun onEntryDeadline(row: EntryRowView) {
        focusedRow = row
        showDateTime(row)
    }

    override fun onEntryDeadlineLong(row: EntryRowView) {
        pushUndo(collectDraft())
        row.draft.deadlineAt = null
        row.draft.allDay = false
        row.applyDeadline()
        toast(R.string.editor_deadline_removed)
    }

    override fun onContentChanged() {
        // Called by block views too; the editor only needs one snapshot per typing burst.
        if (loading) return
        noteTextChanged()
    }

    // endregion

    private fun moveBlock(view: BlockEditorView, delta: Int) {
        val index = blockViews.indexOf(view)
        val target = index + delta
        if (index < 0 || target < 0 || target >= draft.blocks.size) return
        pushUndo(collectDraft())
        val moving = draft.blocks.removeAt(index)
        draft.blocks.add(target, moving)
        rebuildBlocks()
    }

    private fun showDateTime(row: EntryRowView) {
        DateTimeDialog(this, row.draft.deadlineAt, row.draft.allDay) { millis, allDay ->
            row.draft.deadlineAt = millis
            row.draft.allDay = allDay
            row.applyDeadline()
            toast(R.string.editor_deadline_set)
        }
    }

    private fun pickFolder() {
        folderPickerLauncher.launch(
            Intent(this, FoldersActivity::class.java)
                .putExtra(FoldersActivity.EXTRA_PICK_MODE, true)
        )
    }

    private fun save() {
        val collected = collectDraft().normalised()
        binding.editorError.isVisible = false
        if (!collected.hasContent()) {
            binding.editorError.setText(R.string.editor_error_empty)
            binding.editorError.isVisible = true
            return
        }
        val detail = toDetail(collected)
        Threads.io {
            val savedId = store.saveNote(detail)
            store.note(savedId)?.let { AlarmScheduler.syncNote(this, it) }
            Threads.post {
                setResult(
                    RESULT_OK,
                    Intent()
                        .putExtra(EXTRA_NOTE_ID, savedId)
                        .putExtra(EXTRA_ACTION, ACTION_SAVED)
                )
                finish()
            }
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_delete_note_title)
            .setMessage(
                if (draft.title.isBlank()) {
                    getString(R.string.dialog_delete_note_message_untitled)
                } else {
                    getString(R.string.dialog_delete_note_message, draft.title)
                }
            )
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val id = noteId
                Threads.io {
                    store.note(id)?.let { AlarmScheduler.cancelNote(this, it) }
                    store.trashNote(id)
                    Threads.post {
                        setResult(
                            RESULT_OK,
                            Intent()
                                .putExtra(EXTRA_NOTE_ID, id)
                                .putExtra(EXTRA_ACTION, ACTION_DELETED)
                        )
                        finish()
                    }
                }
            }
            .show()
    }

    private fun requestDiscard() {
        if (!isDirty()) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.editor_discard_title)
            .setMessage(R.string.editor_discard_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_discard) { _, _ ->
                if (noteId > 0) {
                    setResult(
                        RESULT_OK,
                        Intent()
                            .putExtra(EXTRA_NOTE_ID, noteId)
                            .putExtra(EXTRA_ACTION, ACTION_DISCARDED)
                    )
                }
                finish()
            }
            .show()
    }

    private fun colorDot(color: NoteColor): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(ContextCompat.getColor(this@NoteEditorActivity, color.colorRes))
            setStroke(
                (resources.displayMetrics.density).toInt(),
                ContextCompat.getColor(this@NoteEditorActivity, R.color.color_outline)
            )
        }

    private fun toast(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_NOTE_ID = "extra_note_id"
        const val EXTRA_FOLDER_ID = "extra_folder_id"
        const val EXTRA_ACTION = "extra_action"
        const val ACTION_SAVED = "saved"
        const val ACTION_DELETED = "deleted"
        const val ACTION_DISCARDED = "discarded"

        private const val MAX_HISTORY = 50
    }
}

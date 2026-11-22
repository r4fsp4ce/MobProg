package com.example.mobprog.service

import android.app.Service
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.example.mobprog.R
import com.example.mobprog.alarm.AlarmScheduler
import com.example.mobprog.data.StoreProvider
import com.example.mobprog.data.TodoStore
import com.example.mobprog.databinding.ViewQuickNotePanelBinding
import com.example.mobprog.databinding.ViewQuickNoteRowBinding
import com.example.mobprog.model.Block
import com.example.mobprog.model.BlockDetail
import com.example.mobprog.model.Entry
import com.example.mobprog.model.EntryDraft
import com.example.mobprog.model.EntryType
import com.example.mobprog.model.Note
import com.example.mobprog.model.NoteColor
import com.example.mobprog.model.NoteDetail
import com.example.mobprog.model.Priority
import com.example.mobprog.ui.ColorSwatchPopup
import com.example.mobprog.ui.RibbonView
import com.example.mobprog.util.Dates
import com.example.mobprog.util.ThemePrefs
import com.example.mobprog.util.Threads

/**
 * The panel shown over other apps. Its body is a small block editor: every line
 * carries its own type (checkbox, bullet, number), so the marker the user picks
 * is visible while typing. Undo/redo snapshots the whole set of lines.
 */
class QuickNotePanel(
    private val service: Service,
    private val onClose: () -> Unit,
    private val showDeadlinePicker: (Long?, Boolean, (Long, Boolean) -> Unit) -> Unit
) : RibbonView.Listener {

    private val themed: Context = ContextThemeWrapper(service, R.style.Theme_MobProg)
        .also { ThemePrefs.applyTo(it) }
    private val binding = ViewQuickNotePanelBinding.inflate(LayoutInflater.from(themed))

    private val store: TodoStore get() = StoreProvider.get()

    private var defaultType: EntryType = EntryType.CHECKBOX
    private var color: NoteColor = NoteColor.DEFAULT

    private class Line(val binding: ViewQuickNoteRowBinding, val draft: EntryDraft)

    private val lines = mutableListOf<Line>()
    private var focused: Line? = null
    private var applyingHistory = false

    private val history = mutableListOf<List<EntryDraft>>()
    private var historyIndex = -1
    private val historyHandler = Handler(Looper.getMainLooper())
    private val recordHistoryRunnable = Runnable { recordHistory() }

    val view: View get() = binding.root
    val dragHandle: View get() = binding.quickDragHandle

    init {
        binding.quickRibbon.listener = this
        binding.quickRibbon.showUndoRedo(true)
        binding.quickRibbon.showListType(defaultType)
        binding.quickRibbon.showPriority(Priority.NONE)
        binding.quickRibbon.showColor(color)
        binding.quickRibbon.showClock(null)
        binding.quickScroll.maxHeight = dp(228)

        binding.quickAddItem.setOnClickListener {
            addLine(EntryDraft(type = currentType()), focus = true)
            recordHistory()
        }

        binding.root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                closePanel()
                true
            } else {
                false
            }
        }

        addLine(EntryDraft(type = defaultType), focus = true)
        recordHistory()
    }

    /** Focus the first line so the keyboard opens with the panel. */
    fun focusInput() {
        val line = lines.firstOrNull() ?: return
        line.binding.quickEntryText.requestFocus()
        line.binding.quickEntryText.setSelection(line.binding.quickEntryText.text?.length ?: 0)
    }

    // region ribbon

    override fun onListType() {
        val next = currentType().next()
        defaultType = next
        // No focused line (e.g. after undo): apply to the last line so the body changes too.
        val line = focused ?: lines.lastOrNull()
        if (line != null) {
            focused = line
            line.draft.type = next
            bindLine(line)
            renumber()
            line.binding.quickEntryText.requestFocus()
            recordHistory()
        }
        binding.quickRibbon.showListType(next)
    }

    override fun onClock() {
        val line = activeLine() ?: return
        showDeadlinePicker(line.draft.deadlineAt, line.draft.allDay) { millis, isAllDay ->
            line.draft.deadlineAt = millis
            line.draft.allDay = isAllDay
            bindLine(line)
            showLineState(line)
            recordHistory()
        }
    }

    override fun onPriority() = showPriorityMenu(binding.quickRibbon.priorityAnchor)

    /** Priority menu for the active row, anchored on the ribbon button. */
    private fun showPriorityMenu(anchor: View) {
        val line = activeLine() ?: return
        val popup = PopupMenu(themed, anchor)
        val options = Priority.selectable + Priority.NONE
        options.forEachIndexed { index, option ->
            popup.menu.add(0, option.value, index, themed.getString(option.labelRes))
        }
        popup.setOnMenuItemClickListener { item ->
            line.draft.priority = Priority.fromValue(item.itemId)
            bindLine(line)
            showLineState(line)
            recordHistory()
            true
        }
        popup.show()
    }

    override fun onColor() {
        ColorSwatchPopup(themed, color) { picked ->
            color = picked
            binding.quickRibbon.showColor(color)
        }.show(binding.quickRibbon.colorAnchor, binding.root)
    }

    override fun onUndo() {
        historyHandler.removeCallbacks(recordHistoryRunnable)
        // Capture anything typed since the last snapshot before stepping back.
        recordHistory()
        if (historyIndex > 0) {
            historyIndex--
            render(history[historyIndex])
        }
        updateUndoState()
    }

    override fun onRedo() {
        historyHandler.removeCallbacks(recordHistoryRunnable)
        if (historyIndex in 0 until history.lastIndex) {
            historyIndex++
            render(history[historyIndex])
        }
        updateUndoState()
    }

    override fun onDiscard() = closePanel()

    override fun onSave() = save()

    // endregion

    // region lines

    private fun addLine(draft: EntryDraft, focus: Boolean, index: Int = -1) {
        val rowBinding = ViewQuickNoteRowBinding.inflate(
            LayoutInflater.from(themed),
            binding.quickRows,
            false
        )
        val line = Line(rowBinding, draft)

        rowBinding.quickEntryText.setText(draft.text)
        rowBinding.quickEntryCheckbox.isChecked = draft.checked

        rowBinding.quickEntryText.doAfterTextChanged { editable ->
            if (applyingHistory) return@doAfterTextChanged
            val value = editable?.toString().orEmpty()
            val newline = value.indexOf('\n')
            if (newline < 0) {
                draft.text = value
                scheduleHistory()
                return@doAfterTextChanged
            }
            // Enter starts the next item; the text after it moves into the new row.
            val before = value.substring(0, newline)
            val after = value.substring(newline + 1)
            draft.text = before
            rowBinding.quickEntryText.setText(before)
            rowBinding.quickEntryText.setSelection(before.length)
            addLineAfter(line, after)
            recordHistory()
        }
        rowBinding.quickEntryText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                focused = line
                showLineState(line)
            }
        }
        rowBinding.quickEntryDeadline.setOnClickListener {
            focused = line
            onClock()
        }
        rowBinding.quickEntryCheckbox.setOnCheckedChangeListener { _, checked ->
            if (applyingHistory) return@setOnCheckedChangeListener
            draft.checked = checked
            recordHistory()
        }

        val position = if (index in 0..lines.size) index else lines.size
        binding.quickRows.addView(rowBinding.root, position)
        lines.add(position, line)
        bindLine(line)
        renumber()

        if (focus) {
            defaultType = draft.type
            guarded {
                rowBinding.quickEntryText.requestFocus()
                rowBinding.quickEntryText.setSelection(
                    rowBinding.quickEntryText.text?.length ?: 0
                )
            }
            binding.quickScroll.post {
                binding.quickScroll.smoothScrollTo(0, rowBinding.root.top)
            }
        }
    }

    /** Splits the text after a newline into a new row directly below [line]. */
    private fun addLineAfter(line: Line, text: String) {
        val index = lines.indexOf(line)
        if (index < 0) return
        addLine(EntryDraft(type = line.draft.type, text = text), focus = true, index = index + 1)
    }

    private fun bindLine(line: Line) {
        val type = line.draft.type
        line.binding.quickEntryCheckbox.isVisible = type == EntryType.CHECKBOX
        line.binding.quickEntryCheckbox.isChecked = line.draft.checked

        val prefix = line.binding.quickEntryPrefix
        prefix.isVisible = type == EntryType.BULLET || type == EntryType.NUMBERED
        prefix.text = prefixFor(line)
        prefix.contentDescription = themed.getString(type.labelRes)

        val priority = line.draft.priority
        val priorityPill = line.binding.quickEntryPriority
        priorityPill.isVisible = priority != Priority.NONE
        if (priority != Priority.NONE) {
            priorityPill.text = priority.badge
            priorityPill.contentDescription = themed.getString(
                R.string.priority_label,
                themed.getString(priority.labelRes)
            )
        }

        val deadline = line.draft.deadlineAt
        val deadlinePill = line.binding.quickEntryDeadline
        deadlinePill.isVisible = deadline != null
        if (deadline != null) {
            val label = Dates.deadlineLabel(themed, deadline, line.draft.allDay)
            deadlinePill.text = label
            deadlinePill.contentDescription =
                themed.getString(R.string.deadline_chip_description, label)
            deadlinePill.setTextColor(
                themed.getColor(
                    if (Dates.isPast(deadline)) {
                        R.color.deadline_overdue
                    } else {
                        R.color.deadline_normal
                    }
                )
            )
        }

        line.binding.quickEntryMeta.isVisible =
            priority != Priority.NONE || deadline != null
    }

    private fun prefixFor(line: Line): String = when (line.draft.type) {
        EntryType.BULLET -> "•"
        EntryType.NUMBERED -> "${lines.indexOfFirst { it === line } + 1}."
        else -> ""
    }

    private fun renumber() {
        lines.forEach { line ->
            if (line.draft.type == EntryType.NUMBERED) {
                line.binding.quickEntryPrefix.text = prefixFor(line)
            }
        }
    }

    private fun currentType(): EntryType = focused?.draft?.type ?: defaultType

    private fun activeLine(): Line? = focused ?: lines.lastOrNull()

    /** The ribbon mirrors the row that owns the cursor, or the last row. */
    private fun showLineState(line: Line) {
        binding.quickRibbon.showListType(line.draft.type)
        binding.quickRibbon.showPriority(line.draft.priority)
        binding.quickRibbon.showClock(
            line.draft.deadlineAt?.let {
                Dates.deadlineLabel(themed, it, line.draft.allDay)
            }
        )
    }

    /** requestFocus can throw if the panel window is already going away. */
    private inline fun guarded(block: () -> Unit) {
        runCatching(block)
    }

    // endregion

    // region history

    private fun scheduleHistory() {
        if (applyingHistory) return
        historyHandler.removeCallbacks(recordHistoryRunnable)
        historyHandler.postDelayed(recordHistoryRunnable, HISTORY_DELAY_MS)
    }

    private fun recordHistory() {
        if (applyingHistory) return
        historyHandler.removeCallbacks(recordHistoryRunnable)

        val snapshot = lines.map { it.draft.copyDraft() }
        if (historyIndex in history.indices && history[historyIndex] == snapshot) {
            updateUndoState()
            return
        }

        while (history.size > historyIndex + 1) {
            history.removeAt(history.lastIndex)
        }
        history.add(snapshot)
        if (history.size > MAX_HISTORY) {
            history.removeAt(0)
        }
        historyIndex = history.lastIndex
        updateUndoState()
    }

    private fun updateUndoState() {
        binding.quickRibbon.setUndoEnabled(historyIndex > 0)
        binding.quickRibbon.setRedoEnabled(historyIndex in 0 until history.lastIndex)
    }

    private fun render(snapshot: List<EntryDraft>) {
        historyHandler.removeCallbacks(recordHistoryRunnable)
        applyingHistory = true
        binding.quickRows.removeAllViews()
        lines.clear()
        focused = null
        snapshot.forEach { addLine(it.copyDraft(), focus = false) }
        applyingHistory = false
        defaultType = lines.lastOrNull()?.draft?.type ?: defaultType
        renumber()
        val last = lines.lastOrNull()
        if (last != null) {
            showLineState(last)
        } else {
            binding.quickRibbon.showListType(defaultType)
            binding.quickRibbon.showPriority(Priority.NONE)
            binding.quickRibbon.showClock(null)
        }
        updateUndoState()
    }

    // endregion

    private fun closePanel() {
        historyHandler.removeCallbacks(recordHistoryRunnable)
        onClose()
    }

    private fun save() {
        historyHandler.removeCallbacks(recordHistoryRunnable)
        val drafts = lines.map { it.draft }.filter { it.text.isNotBlank() }
        if (drafts.isEmpty()) {
            Toast.makeText(themed, R.string.quick_note_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val entries = drafts.mapIndexed { index, draft ->
            Entry(
                id = 0L,
                blockId = 0L,
                position = index,
                type = draft.type,
                text = draft.text.trim(),
                checked = draft.checked,
                priority = draft.priority,
                deadlineAt = draft.deadlineAt,
                allDay = draft.allDay
            )
        }
        val note = Note(
            id = -1L,
            folderId = store.inboxId(),
            title = "",
            color = color,
            pinned = false,
            archived = false,
            trashed = false,
            createdAt = 0L,
            updatedAt = 0L
        )
        val block = Block(id = 0L, noteId = -1L, position = 0, heading = "", originNoteId = null)

        Threads.io {
            val savedId = store.saveNote(NoteDetail(note, listOf(BlockDetail(block, entries))))
            store.note(savedId)?.let { AlarmScheduler.syncNote(service, it) }
            Threads.post {
                Toast.makeText(service, R.string.quick_note_saved, Toast.LENGTH_SHORT).show()
                closePanel()
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()

    private companion object {
        const val HISTORY_DELAY_MS = 450L
        const val MAX_HISTORY = 60
    }
}

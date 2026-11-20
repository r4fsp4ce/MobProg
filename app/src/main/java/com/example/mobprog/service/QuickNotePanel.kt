package com.example.mobprog.service

import android.app.Service
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.mobprog.R
import com.example.mobprog.alarm.AlarmScheduler
import com.example.mobprog.data.StoreProvider
import com.example.mobprog.data.TodoStore
import com.example.mobprog.databinding.ViewQuickNotePanelBinding
import com.example.mobprog.model.Block
import com.example.mobprog.model.BlockDetail
import com.example.mobprog.model.Entry
import com.example.mobprog.model.EntryType
import com.example.mobprog.model.Note
import com.example.mobprog.model.NoteColor
import com.example.mobprog.model.NoteDetail
import com.example.mobprog.model.Priority
import com.example.mobprog.ui.RibbonView
import com.example.mobprog.util.Dates
import com.example.mobprog.util.Threads

/**
 * The panel shown over other apps. It reuses the editor ribbon and writes straight
 * into the Inbox when saved.
 */
class QuickNotePanel(
    private val service: Service,
    private val onClose: () -> Unit,
    private val showDeadlinePicker: (Long?, Boolean, (Long, Boolean) -> Unit) -> Unit
) : RibbonView.Listener {

    private val themed: Context = ContextThemeWrapper(service, R.style.Theme_MobProg)
    private val binding = ViewQuickNotePanelBinding.inflate(LayoutInflater.from(themed))

    private val store: TodoStore get() = StoreProvider.get()

    private var type: EntryType = EntryType.CHECKBOX
    private var priority: Priority = Priority.NONE
    private var color: NoteColor = NoteColor.DEFAULT
    private var deadlineAt: Long? = null
    private var allDay: Boolean = false

    val view: View get() = binding.root

    init {
        binding.quickRibbon.listener = this
        binding.quickRibbon.showUndoRedo(false)
        binding.quickRibbon.showListType(type)
        binding.quickRibbon.showPriority(priority)
        binding.quickRibbon.showColor(color)
        binding.quickRibbon.showClock(null)
        binding.quickInput.requestFocus()
    }

    override fun onListType() {
        type = type.next()
        binding.quickRibbon.showListType(type)
    }

    override fun onClock() {
        showDeadlinePicker(deadlineAt, allDay) { millis, isAllDay ->
            deadlineAt = millis
            allDay = isAllDay
            binding.quickRibbon.showClock(Dates.deadlineLabel(themed, millis, isAllDay))
        }
    }

    override fun onPriority() {
        val popup = PopupMenu(themed, binding.quickRibbon.priorityAnchor)
        val options = Priority.selectable + Priority.NONE
        options.forEachIndexed { index, option ->
            popup.menu.add(0, option.value, index, themed.getString(option.labelRes))
        }
        popup.setOnMenuItemClickListener { item ->
            priority = Priority.fromValue(item.itemId)
            binding.quickRibbon.showPriority(priority)
            true
        }
        popup.show()
    }

    override fun onColor() {
        val popup = PopupMenu(themed, binding.quickRibbon.colorAnchor)
        NoteColor.entries.forEachIndexed { index, option ->
            val item = popup.menu.add(0, index, index, themed.getString(option.labelRes))
            item.icon = ContextCompat.getDrawable(themed, option.colorRes)
        }
        popup.setOnMenuItemClickListener { item ->
            color = NoteColor.entries[item.itemId]
            binding.quickRibbon.showColor(color)
            true
        }
        popup.show()
    }

    override fun onUndo() = Unit

    override fun onRedo() = Unit

    override fun onDiscard() = onClose()

    override fun onSave() = save()

    private fun save() {
        val text = binding.quickInput.text?.toString().orEmpty().trim()
        if (text.isBlank()) {
            Toast.makeText(themed, R.string.quick_note_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val entries = lines.map { line ->
            Entry(
                id = 0L,
                blockId = 0L,
                position = 0,
                type = type,
                text = line,
                checked = false,
                priority = priority,
                deadlineAt = deadlineAt,
                allDay = allDay
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
                onClose()
            }
        }
    }
}

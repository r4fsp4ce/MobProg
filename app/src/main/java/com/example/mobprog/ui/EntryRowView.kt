package com.example.mobprog.ui

import android.content.Context
import android.graphics.Paint
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.example.mobprog.R
import com.example.mobprog.databinding.ViewEntryRowBinding
import com.example.mobprog.model.EntryDraft
import com.example.mobprog.model.EntryType
import com.example.mobprog.model.Priority
import com.example.mobprog.util.Dates

/** One row inside a block: checkbox/bullet/number, the text, its priority and deadline. */
class EntryRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding = ViewEntryRowBinding.inflate(LayoutInflater.from(context), this, true)

    var draft: EntryDraft = EntryDraft()
        private set

    var onFocus: ((EntryRowView) -> Unit)? = null
    var onEnter: ((EntryRowView, String) -> Unit)? = null
    var onDeadlineClick: ((EntryRowView) -> Unit)? = null
    var onDeadlineLongClick: ((EntryRowView) -> Unit)? = null
    var onContentChanged: (() -> Unit)? = null

    private var slot = 0
    private var bindingValues = false
    private var splitting = false

    init {
        orientation = HORIZONTAL
        binding.entryCheckbox.setOnCheckedChangeListener { _, checked ->
            draft.checked = checked
            applyCheckedStyle()
            if (!bindingValues) {
                onFocus?.invoke(this)
                onContentChanged?.invoke()
            }
        }
        binding.entryText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) onFocus?.invoke(this)
        }
        // A newline means "start the next item": the tail moves into a new row.
        binding.entryText.doAfterTextChanged { editable ->
            if (bindingValues || splitting) return@doAfterTextChanged
            val value = editable?.toString().orEmpty()
            draft.text = value
            val newline = value.indexOf('\n')
            if (newline < 0) {
                // Plain typing must reach the editor, or undo stays disabled.
                onContentChanged?.invoke()
                return@doAfterTextChanged
            }
            val before = value.substring(0, newline)
            val after = value.substring(newline + 1)
            splitting = true
            binding.entryText.setText(before)
            binding.entryText.setSelection(before.length)
            splitting = false
            draft.text = before
            onEnter?.invoke(this, after)
        }
        binding.entryDeadline.setOnClickListener { onDeadlineClick?.invoke(this) }
        binding.entryDeadline.setOnLongClickListener {
            onDeadlineLongClick?.invoke(this)
            true
        }
    }

    fun bind(entry: EntryDraft, slot: Int) {
        bindingValues = true
        draft = entry
        this.slot = slot
        binding.entryText.setText(entry.text)
        binding.entryCheckbox.isChecked = entry.checked
        bindingValues = false
        applyType()
        applyPriority()
        applyDeadline()
        applyCheckedStyle()
    }

    fun collect(): EntryDraft {
        draft.text = binding.entryText.text?.toString().orEmpty()
        draft.checked = binding.entryCheckbox.isChecked
        // Undo/redo keeps snapshots of this; they must not share the live object.
        return draft.copyDraft()
    }

    fun setType(type: EntryType) {
        draft.type = type
        applyType()
        applyCheckedStyle()
    }

    fun setSlot(index: Int) {
        slot = index
        if (draft.type == EntryType.NUMBERED) {
            binding.entryPrefix.text = "${index + 1}."
        }
    }

    fun applyPriority() {
        val priority = draft.priority
        binding.entryPriority.isVisible = priority != Priority.NONE
        binding.entryPriority.text = priority.badge
        if (priority != Priority.NONE) {
            binding.entryPriority.contentDescription =
                context.getString(R.string.priority_label, context.getString(priority.labelRes))
        }
        updateMeta()
    }

    fun applyDeadline() {
        val deadline = draft.deadlineAt
        if (deadline == null) {
            binding.entryDeadline.isVisible = false
            updateMeta()
            return
        }
        val label = Dates.deadlineLabel(context, deadline, draft.allDay)
        binding.entryDeadline.isVisible = true
        binding.entryDeadline.text = label
        binding.entryDeadline.contentDescription =
            context.getString(R.string.deadline_chip_description, label)
        val overdue = Dates.isPast(deadline)
        binding.entryDeadline.setTextColor(
            context.getColor(if (overdue) R.color.deadline_overdue else R.color.deadline_normal)
        )
        updateMeta()
    }

    /** The badge line only exists while there is something to show in it. */
    private fun updateMeta() {
        binding.entryMeta.isVisible =
            draft.priority != Priority.NONE || draft.deadlineAt != null
    }

    fun focusText() {
        binding.entryText.requestFocus()
    }

    /** Checked checkboxes are struck through, and typing keeps the line-through. */
    private fun applyCheckedStyle() {
        val strike = draft.type == EntryType.CHECKBOX && draft.checked
        val flags = binding.entryText.paintFlags
        binding.entryText.paintFlags = if (strike) {
            flags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            flags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
    }

    private fun applyType() {
        val type = draft.type
        binding.entryCheckbox.isVisible = type == EntryType.CHECKBOX
        binding.entryPrefix.isVisible = type == EntryType.BULLET || type == EntryType.NUMBERED
        binding.entryPrefix.text = when (type) {
            EntryType.BULLET -> "•"
            EntryType.NUMBERED -> "${slot + 1}."
            else -> ""
        }
        binding.entryPrefix.contentDescription = context.getString(type.labelRes)
    }
}

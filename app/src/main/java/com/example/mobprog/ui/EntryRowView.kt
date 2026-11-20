package com.example.mobprog.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.view.isVisible
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
    var onDeadlineClick: ((EntryRowView) -> Unit)? = null
    var onDeadlineLongClick: ((EntryRowView) -> Unit)? = null
    var onContentChanged: (() -> Unit)? = null

    private var slot = 0
    private var bindingValues = false

    init {
        orientation = HORIZONTAL
        binding.entryCheckbox.setOnCheckedChangeListener { _, checked ->
            draft.checked = checked
            if (!bindingValues) {
                onFocus?.invoke(this)
                onContentChanged?.invoke()
            }
        }
        binding.entryText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) onFocus?.invoke(this)
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
    }

    fun collect(): EntryDraft {
        draft.text = binding.entryText.text?.toString().orEmpty()
        draft.checked = binding.entryCheckbox.isChecked
        return draft
    }

    fun setType(type: EntryType) {
        draft.type = type
        applyType()
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
            binding.entryPriority.contentDescription = context.getString(R.string.priority_badge, priority.value)
        }
    }

    fun applyDeadline() {
        val deadline = draft.deadlineAt
        if (deadline == null) {
            binding.entryDeadline.isVisible = false
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
    }

    fun focusText() {
        binding.entryText.requestFocus()
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

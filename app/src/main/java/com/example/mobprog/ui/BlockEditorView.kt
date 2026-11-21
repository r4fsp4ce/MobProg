package com.example.mobprog.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.example.mobprog.databinding.ViewEditorBlockBinding
import com.example.mobprog.model.BlockDraft
import com.example.mobprog.model.EntryDraft
import com.example.mobprog.model.EntryType

/**
 * One block of the editor: heading, its entries, and the reorder/delete controls.
 * Entries are plain child views rather than a nested RecyclerView — a block holds
 * a handful of rows and nesting scrolling lists gets in the way of typing.
 */
class BlockEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Callback {
        fun onBlockUp(view: BlockEditorView)
        fun onBlockDown(view: BlockEditorView)
        fun onBlockDelete(view: BlockEditorView)
        fun onEntryFocused(row: EntryRowView)
        fun onEntryDeadline(row: EntryRowView)
        fun onEntryDeadlineLong(row: EntryRowView)
        fun onContentChanged()
    }

    private val binding = ViewEditorBlockBinding.inflate(LayoutInflater.from(context), this, true)
    private val rows = mutableListOf<EntryRowView>()

    var callback: Callback? = null
    var draft: BlockDraft = BlockDraft()
        private set
    private var newEntryType = EntryType.CHECKBOX

    init {
        orientation = VERTICAL
        binding.blockHeading.doAfterTextChanged { callback?.onContentChanged() }
        binding.blockAddEntry.setOnClickListener { addEntry() }
        binding.blockUp.setOnClickListener { callback?.onBlockUp(this) }
        binding.blockDown.setOnClickListener { callback?.onBlockDown(this) }
        binding.blockDelete.setOnClickListener { callback?.onBlockDelete(this) }
    }

    fun bind(block: BlockDraft, index: Int, total: Int) {
        draft = block
        rows.clear()
        binding.blockEntries.removeAllViews()
        binding.blockHeading.setText(block.heading)
        block.entries.forEachIndexed { slot, entry -> appendRow(entry, slot) }
        binding.blockUp.isVisible = index > 0
        binding.blockDown.isVisible = index < total - 1
        binding.blockDelete.isVisible = total > 1
    }

    fun collect(): BlockDraft {
        val collected = rows.map { it.collect() }.toMutableList()
        return draft.copyDraft().also {
            it.heading = binding.blockHeading.text?.toString().orEmpty().trimEnd()
            it.entries = collected
        }
    }

    fun lastUsedType(): EntryType =
        rows.lastOrNull()?.draft?.type ?: newEntryType

    fun addEntry(type: EntryType = lastUsedType(), requestFocus: Boolean = false) {
        val entry = EntryDraft(type = type)
        draft.entries.add(entry)
        val row = appendRow(entry, rows.size)
        renumber()
        if (requestFocus) {
            row.focusText()
            row.post {
                (parent as? android.view.View)?.requestRectangleOnScreen(
                    android.graphics.Rect(0, 0, row.width, row.height)
                )
            }
        }
    }

    fun setEntryType(row: EntryRowView, type: EntryType) {
        row.setType(type)
        newEntryType = type
        renumber()
        callback?.onContentChanged()
    }

    fun rows(): List<EntryRowView> = rows

    private fun appendRow(entry: EntryDraft, slot: Int): EntryRowView {
        val row = EntryRowView(context)
        row.bind(entry, slot)
        row.onFocus = { callback?.onEntryFocused(it) }
        row.onDeadlineClick = { callback?.onEntryDeadline(it) }
        row.onDeadlineLongClick = { callback?.onEntryDeadlineLong(it) }
        row.onContentChanged = { callback?.onContentChanged() }
        binding.blockEntries.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        rows.add(row)
        return row
    }

    private fun renumber() {
        rows.forEachIndexed { index, row -> row.setSlot(index) }
    }
}

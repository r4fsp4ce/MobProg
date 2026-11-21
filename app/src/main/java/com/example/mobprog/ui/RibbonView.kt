package com.example.mobprog.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.mobprog.R
import com.example.mobprog.databinding.ViewRibbonBinding
import com.example.mobprog.model.EntryType
import com.example.mobprog.model.NoteColor
import com.example.mobprog.model.Priority

/**
 * The bottom ribbon shared by NoteEditorActivity and the quick-note panel:
 * list type, deadline, priority, colour, undo/redo, discard and save.
 */
class RibbonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onListType()
        fun onClock()
        fun onPriority()
        fun onColor()
        fun onUndo()
        fun onRedo()
        fun onDiscard()
        fun onSave()

        /** Only the full editor offers raw Markdown; the quick-note ribbon leaves it hidden. */
        fun onRawMarkdown() = Unit
    }

    private val binding = ViewRibbonBinding.inflate(LayoutInflater.from(context), this, true)

    var listener: Listener? = null

    /** Anchors for the priority and colour popups. */
    val priorityAnchor: View get() = binding.ribbonPriority
    val colorAnchor: View get() = binding.ribbonColor

    init {
        orientation = HORIZONTAL
        binding.ribbonListType.setOnClickListener { listener?.onListType() }
        binding.ribbonClock.setOnClickListener { listener?.onClock() }
        binding.ribbonPriority.setOnClickListener { listener?.onPriority() }
        binding.ribbonColor.setOnClickListener { listener?.onColor() }
        binding.ribbonUndo.setOnClickListener { listener?.onUndo() }
        binding.ribbonRedo.setOnClickListener { listener?.onRedo() }
        binding.ribbonRawMarkdown.setOnClickListener { listener?.onRawMarkdown() }
        binding.ribbonDiscard.setOnClickListener { listener?.onDiscard() }
        binding.ribbonSave.setOnClickListener { listener?.onSave() }
    }

    /** The quick-note panel has no undo history. */
    fun showUndoRedo(visible: Boolean) {
        binding.ribbonUndo.isVisible = visible
        binding.ribbonRedo.isVisible = visible
    }

    fun setUndoEnabled(enabled: Boolean) {
        binding.ribbonUndo.isEnabled = enabled
        binding.ribbonUndo.alpha = if (enabled) 1f else 0.4f
    }

    fun setRedoEnabled(enabled: Boolean) {
        binding.ribbonRedo.isEnabled = enabled
        binding.ribbonRedo.alpha = if (enabled) 1f else 0.4f
    }

    /** The raw-Markdown button only appears while text is being edited and the option is on. */
    fun showRawMarkdown(visible: Boolean) {
        binding.ribbonRawMarkdown.isVisible = visible
    }

    fun showListType(type: EntryType) {
        binding.ribbonListType.setImageResource(iconFor(type))
        binding.ribbonListType.contentDescription = context.getString(
            R.string.list_type_label,
            context.getString(type.labelRes)
        )
    }

    fun showPriority(priority: Priority) {
        binding.ribbonPriority.contentDescription = context.getString(
            R.string.priority_label,
            context.getString(priority.labelRes)
        )
        binding.ribbonPriority.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                context,
                if (priority == Priority.NONE) R.color.icon_tint else R.color.color_primary
            )
        )
    }

    fun showColor(color: NoteColor) {
        val fill = ContextCompat.getColor(context, color.colorRes)
        val stroke = ContextCompat.getColor(context, R.color.icon_tint)
        val size = dp(20)
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(dp(2), stroke)
            setSize(size, size)
        }
        val padding = dp(10)
        binding.ribbonColor.setPadding(padding, padding, padding, padding)
        binding.ribbonColor.setImageDrawable(dot)
        binding.ribbonColor.imageTintList = null
        binding.ribbonColor.contentDescription = context.getString(
            R.string.color_label,
            context.getString(color.labelRes)
        )
    }

    fun showClock(deadlineLabel: String?) {
        binding.ribbonClock.contentDescription =
            deadlineLabel ?: context.getString(R.string.cd_clock)
    }

    private fun iconFor(type: EntryType): Int = when (type) {
        EntryType.TEXT -> R.drawable.ic_list_bullet
        EntryType.BULLET -> R.drawable.ic_list_bullet
        EntryType.NUMBERED -> R.drawable.ic_list_numbered
        EntryType.CHECKBOX -> R.drawable.ic_checklist
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

package com.example.mobprog.ui

import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.mobprog.R
import com.example.mobprog.databinding.ItemNoteCardBinding
import com.example.mobprog.model.NoteSummary
import com.example.mobprog.model.Priority
import com.example.mobprog.util.Dates

/** Card grid: colour, pin, Markdown preview, checklist, progress, priority and deadline. */
class NotesAdapter(private val callbacks: Callbacks) :
    ListAdapter<NoteSummary, NotesAdapter.CardHolder>(DIFF) {

    interface Callbacks {
        fun onOpenNote(note: NoteSummary)
        fun onNoteMenu(note: NoteSummary, anchor: View)
    }

    inner class CardHolder(val binding: ItemNoteCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(note: NoteSummary) {
            val context = binding.root.context
            val solid = ContextCompat.getColor(context, note.color.colorRes)
            binding.card.setCardBackgroundColor(solid)

            binding.cardTitle.text = note.title.ifBlank { context.getString(R.string.untitled_note) }
            binding.cardPinIndicator.isVisible = note.pinned

            val hasPreview = note.previewMarkdown.isNotBlank()
            binding.cardPreview.isVisible = hasPreview
            if (hasPreview) {
                MarkdownUtils.render(binding.cardPreview, note.previewMarkdown)
            }

            binding.cardChecklist.removeAllViews()
            note.openCheckboxes.forEach { line ->
                val row = TextView(context).apply {
                    text = context.getString(R.string.card_checkbox_open, line)
                    textSize = 11f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(ContextCompat.getColor(context, R.color.color_on_surface))
                    val pad = dp(1)
                    setPadding(0, pad, 0, pad)
                }
                binding.cardChecklist.addView(row)
            }
            binding.cardChecklist.isVisible = note.openCheckboxes.isNotEmpty()

            binding.cardCompleted.isVisible = note.checkedCount > 0
            if (note.checkedCount > 0) {
                binding.cardCompleted.text = context.resources.getQuantityString(
                    R.plurals.completed_count,
                    note.checkedCount,
                    note.checkedCount
                )
            }

            binding.cardProgressRow.isVisible = note.totalCheckboxes > 0
            if (note.totalCheckboxes > 0) {
                binding.cardProgress.progress = (note.completion * 100).toInt()
                binding.cardProgressText.text = context.getString(
                    R.string.card_progress,
                    note.checkedCount,
                    note.totalCheckboxes
                )
                binding.cardProgressText.contentDescription = context.getString(
                    R.string.card_progress_description,
                    note.checkedCount,
                    note.totalCheckboxes
                )
            }

            val priority = note.highestPriority
            binding.cardPriority.isVisible = priority != Priority.NONE
            if (priority != Priority.NONE) {
                binding.cardPriority.text = priority.badge
                binding.cardPriority.contentDescription =
                    context.getString(R.string.priority_label, context.getString(priority.labelRes))
            }

            val deadline = note.nextDeadlineAt
            binding.cardDeadline.isVisible = deadline != null
            if (deadline != null) {
                val label = Dates.deadlineLabel(context, deadline, note.nextDeadlineAllDay)
                binding.cardDeadline.text = label
                binding.cardDeadline.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_clock_small, 0, 0, 0)
                binding.cardDeadline.compoundDrawablePadding = dp(3)
                binding.cardDeadline.setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (Dates.isPast(deadline)) R.color.deadline_overdue else R.color.deadline_normal
                    )
                )
                binding.cardDeadline.contentDescription =
                    context.getString(R.string.deadline_chip_description, label)
            }

            val titleForActions = note.title.ifBlank { context.getString(R.string.untitled_note) }
            binding.cardMore.contentDescription = context.getString(R.string.cd_more_actions, titleForActions)

            binding.card.setOnClickListener { callbacks.onOpenNote(note) }
            binding.cardMore.setOnClickListener { callbacks.onNoteMenu(note, it) }
        }

        private fun dp(value: Int): Int =
            (value * binding.root.resources.displayMetrics.density).toInt()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
        val binding = ItemNoteCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CardHolder(binding)
    }

    override fun onBindViewHolder(holder: CardHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<NoteSummary>() {
            override fun areItemsTheSame(oldItem: NoteSummary, newItem: NoteSummary): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: NoteSummary, newItem: NoteSummary): Boolean =
                oldItem == newItem
        }
    }
}

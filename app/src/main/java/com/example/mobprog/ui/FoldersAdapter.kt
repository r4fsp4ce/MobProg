package com.example.mobprog.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.mobprog.R
import com.example.mobprog.databinding.ItemFolderBinding
import com.example.mobprog.model.Folder

data class FolderRow(val folder: Folder, val noteCount: Int)

/** Folder list, also used as the picker when moving a note. */
class FoldersAdapter(
    private val inboxId: Long,
    private val onOpen: (Folder) -> Unit,
    private val onMore: (Folder, View) -> Unit
) : ListAdapter<FolderRow, FoldersAdapter.Holder>(DIFF) {

    inner class Holder(val binding: ItemFolderBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: FolderRow) {
            val context = binding.root.context
            binding.folderName.text = row.folder.name
            binding.folderCount.text = context.resources.getQuantityString(
                R.plurals.folder_note_count,
                row.noteCount,
                row.noteCount
            )
            // The Inbox cannot be renamed or deleted.
            binding.folderMore.isVisible = row.folder.id != inboxId
            binding.folderMore.contentDescription =
                context.getString(R.string.cd_more_actions, row.folder.name)
            binding.root.setOnClickListener { onOpen(row.folder) }
            binding.folderMore.setOnClickListener { onMore(row.folder, it) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FolderRow>() {
            override fun areItemsTheSame(oldItem: FolderRow, newItem: FolderRow): Boolean =
                oldItem.folder.id == newItem.folder.id

            override fun areContentsTheSame(oldItem: FolderRow, newItem: FolderRow): Boolean =
                oldItem == newItem
        }
    }
}

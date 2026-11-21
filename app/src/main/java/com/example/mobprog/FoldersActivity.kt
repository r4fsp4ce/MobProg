package com.example.mobprog

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.mobprog.data.FolderNameResult
import com.example.mobprog.data.StoreProvider
import com.example.mobprog.data.TodoStore
import com.example.mobprog.databinding.ActivityFoldersBinding
import com.example.mobprog.databinding.DialogFolderNameBinding
import com.example.mobprog.model.Folder
import com.example.mobprog.ui.FolderRow
import com.example.mobprog.ui.FoldersAdapter
import com.example.mobprog.util.Insets
import com.example.mobprog.util.Threads
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

/** Folder CRUD. In picker mode the chosen folder is returned in [EXTRA_FOLDER_ID]. */
class FoldersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFoldersBinding
    private lateinit var adapter: FoldersAdapter

    private val store: TodoStore get() = StoreProvider.get()
    private var pickMode = false
    private var inboxId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityFoldersBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.applySystemBars(binding.foldersRoot)

        pickMode = intent.getBooleanExtra(EXTRA_PICK_MODE, false)
        inboxId = store.inboxId()

        setSupportActionBar(binding.toolbar)
        binding.toolbar.title =
            getString(if (pickMode) R.string.folders_pick_title else R.string.folders_title)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = FoldersAdapter(
            inboxId = inboxId,
            onOpen = { folder -> returnFolder(folder) },
            onMore = { folder, anchor -> showFolderMenu(folder, anchor) }
        )
        binding.folderList.layoutManager = LinearLayoutManager(this)
        binding.folderList.adapter = adapter
        binding.addFolder.setOnClickListener { showFolderDialog(null) }

        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        Threads.load({ store.folders() to store.folderNoteCounts() }) { result ->
            val folders = result.first
            val counts = result.second
            val rows = folders.map { folder -> FolderRow(folder, counts[folder.id] ?: 0) }
            adapter.submitList(rows) { updateEmptyState(rows.isEmpty()) }
        }
    }

    private fun updateEmptyState(empty: Boolean) {
        binding.emptyState.root.isVisible = empty
        if (!empty) return
        binding.emptyState.emptyTitle.setText(R.string.folders_empty_title)
        binding.emptyState.emptyBody.setText(R.string.folders_empty_body)
        binding.emptyState.emptyAction.isVisible = false
    }

    private fun returnFolder(folder: Folder) {
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_FOLDER_ID, folder.id).putExtra(EXTRA_FOLDER_NAME, folder.name)
        )
        finish()
    }

    private fun showFolderMenu(folder: Folder, anchor: android.view.View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.folder_actions, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.folder_rename -> {
                    showFolderDialog(folder)
                    true
                }
                R.id.folder_delete -> {
                    confirmDelete(folder)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showFolderDialog(existing: Folder?) {
        val dialogBinding = DialogFolderNameBinding.inflate(layoutInflater)
        dialogBinding.nameInput.setText(existing?.name.orEmpty())

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(
                if (existing == null) R.string.dialog_new_folder else R.string.dialog_rename_folder
            )
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dialogBinding.nameInput.text?.toString().orEmpty()
                dialogBinding.nameLayout.error = null
                Threads.io {
                    val result = if (existing == null) {
                        store.createFolder(name)
                    } else {
                        store.renameFolder(existing.id, name)
                    }
                    Threads.post {
                        when (result) {
                            is FolderNameResult.Created -> {
                                dialog.dismiss()
                                snackbar(R.string.msg_folder_created)
                                load()
                            }
                            is FolderNameResult.Renamed -> {
                                dialog.dismiss()
                                snackbar(R.string.msg_folder_renamed)
                                load()
                            }
                            FolderNameResult.Blank -> dialogBinding.nameLayout.error =
                                getString(R.string.error_folder_name_required)
                            FolderNameResult.Duplicate -> dialogBinding.nameLayout.error =
                                getString(R.string.error_folder_name_duplicate)
                            FolderNameResult.NotFound -> dialog.dismiss()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(folder: Folder) {
        if (folder.id == inboxId) {
            toast(R.string.folders_inbox_locked)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_delete_folder_title)
            .setMessage(getString(R.string.dialog_delete_folder_message, folder.name))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                Threads.io {
                    store.deleteFolder(folder.id)
                    Threads.post {
                        snackbar(R.string.msg_folder_deleted)
                        load()
                    }
                }
            }
            .show()
    }

    private fun snackbar(messageRes: Int) {
        Snackbar.make(binding.foldersRoot, messageRes, Snackbar.LENGTH_SHORT).show()
    }

    private fun toast(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_PICK_MODE = "extra_pick_mode"
        const val EXTRA_FOLDER_ID = "extra_folder_id"
        const val EXTRA_FOLDER_NAME = "extra_folder_name"
    }
}

package com.example.mobprog

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.example.mobprog.alarm.AlarmScheduler
import com.example.mobprog.data.AppendUndoToken
import com.example.mobprog.data.StoreProvider
import com.example.mobprog.data.TodoStore
import com.example.mobprog.databinding.ActivityMainBinding
import com.example.mobprog.model.Folder
import com.example.mobprog.model.NoteFilter
import com.example.mobprog.model.NoteQuery
import com.example.mobprog.model.NoteSort
import com.example.mobprog.model.NoteSummary
import com.example.mobprog.model.NoteView
import com.example.mobprog.ui.FilterSheet
import com.example.mobprog.ui.NoteDragCallback
import com.example.mobprog.ui.NotesAdapter
import com.example.mobprog.util.Insets
import com.example.mobprog.util.Threads
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: NotesAdapter
    private lateinit var dragCallback: NoteDragCallback

    private val store: TodoStore get() = StoreProvider.get()

    private var currentView: NoteView = NoteView.NOTES
    private var currentFolderId: Long = -1L
    private var searchQuery: String = ""
    private var sort: NoteSort = NoteSort.UPDATED
    private var filter: NoteFilter = NoteFilter()
    private var listMode: Boolean = false
    private var pendingMoveNoteId: Long = -1L
    private var foldersById: Map<Long, Folder> = emptyMap()

    private val folderMenuIds = LinkedHashMap<Int, Long>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reloadTask = Runnable { loadNotes() }

    private val editorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data ?: return@registerForActivityResult
            when (data.getStringExtra(NoteEditorActivity.EXTRA_ACTION)) {
                NoteEditorActivity.ACTION_SAVED -> {
                    snackbar(R.string.msg_note_saved)
                    loadNotes()
                }
                NoteEditorActivity.ACTION_DELETED -> {
                    snackbar(R.string.msg_note_deleted)
                    loadNotes()
                }
                NoteEditorActivity.ACTION_DISCARDED -> {
                    snackbar(R.string.msg_discarded)
                    loadNotes()
                }
            }
        }

    private val manageFoldersLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val folderId = result.data?.getLongExtra(FoldersActivity.EXTRA_FOLDER_ID, -1L) ?: -1L
            if (folderId > 0L) {
                currentView = NoteView.FOLDER
                currentFolderId = folderId
                refreshDrawerSelection()
            }
            refreshFolders()
            loadNotes()
        }

    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val folderId = result.data?.getLongExtra(FoldersActivity.EXTRA_FOLDER_ID, -1L) ?: -1L
            val noteId = pendingMoveNoteId
            pendingMoveNoteId = -1L
            if (folderId <= 0 || noteId <= 0) return@registerForActivityResult
            Threads.io {
                store.moveNote(noteId, folderId)
                Threads.post {
                    snackbar(R.string.msg_note_moved)
                    loadNotes()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.applySystemBars(binding.drawerRoot)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.inflateMenu(R.menu.main_toolbar)

        restoreState(savedInstanceState)
        setupToolbar()
        setupList()
        setupDrawer()
        registerFilterListener()
        registerBackHandling()

        refreshFolders()
        loadNotes()
    }

    override fun onResume() {
        super.onResume()
        refreshFolders()
        loadNotes()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_VIEW, currentView.name)
        outState.putLong(STATE_FOLDER, currentFolderId)
        outState.putString(STATE_SEARCH, searchQuery)
        outState.putString(STATE_SORT, sort.name)
        outState.putStringArrayList(STATE_COLORS, ArrayList(filter.colors.map { it.key }))
        outState.putIntegerArrayList(STATE_PRIORITIES, ArrayList(filter.priorities.map { it.value }))
        outState.putBoolean(STATE_DEADLINE, filter.hasDeadline)
        outState.putString(STATE_STATUS, filter.status.name)
        outState.putBoolean(STATE_LIST_MODE, listMode)
    }

    // region setup

    private fun restoreState(state: Bundle?) {
        if (state == null) return
        currentView = runCatching { NoteView.valueOf(state.getString(STATE_VIEW) ?: "") }
            .getOrDefault(NoteView.NOTES)
        currentFolderId = state.getLong(STATE_FOLDER, -1L)
        searchQuery = state.getString(STATE_SEARCH).orEmpty()
        sort = runCatching { NoteSort.valueOf(state.getString(STATE_SORT) ?: "") }
            .getOrDefault(NoteSort.UPDATED)
        filter = NoteFilter(
            colors = state.getStringArrayList(STATE_COLORS).orEmpty()
                .map { com.example.mobprog.model.NoteColor.fromKey(it) }
                .toSet(),
            priorities = state.getIntegerArrayList(STATE_PRIORITIES).orEmpty()
                .map { com.example.mobprog.model.Priority.fromValue(it) }
                .toSet(),
            hasDeadline = state.getBoolean(STATE_DEADLINE, false),
            status = runCatching {
                com.example.mobprog.model.StatusFilter.valueOf(state.getString(STATE_STATUS) ?: "")
            }.getOrDefault(com.example.mobprog.model.StatusFilter.ANY)
        )
        listMode = state.getBoolean(STATE_LIST_MODE, false)
    }

    private fun setupList() {
        adapter = NotesAdapter(object : NotesAdapter.Callbacks {
            override fun onOpenNote(note: NoteSummary) {
                openEditor(note.id, note.folderId)
            }

            override fun onNoteMenu(note: NoteSummary, anchor: View) {
                showCardMenu(note, anchor)
            }
        })
        binding.noteGrid.adapter = adapter
        applyLayoutMode()
        dragCallback = NoteDragCallback(
            isEnabled = { currentView == NoteView.NOTES || currentView == NoteView.FOLDER },
            onDrop = { from, to -> requestAppend(from, to) }
        )
        ItemTouchHelper(dragCallback).attachToRecyclerView(binding.noteGrid)
    }

    private fun applyLayoutMode() {
        binding.noteGrid.layoutManager = if (listMode) {
            LinearLayoutManager(this)
        } else {
            val columns = if (resources.configuration.screenWidthDp >= 600) 3 else 2
            StaggeredGridLayoutManager(columns, StaggeredGridLayoutManager.VERTICAL)
        }
        val item = binding.toolbar.menu.findItem(R.id.action_toggle_view)
        item?.setIcon(if (listMode) R.drawable.ic_grid else R.drawable.ic_list)
        item?.setTitle(if (listMode) R.string.action_grid else R.string.action_list)
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            binding.drawerRoot.openDrawer(GravityCompat.START)
        }

        val searchItem = binding.toolbar.menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = getString(R.string.action_search)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                searchQuery = newText.orEmpty()
                reloadSoon()
                return true
            }
        })
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                searchQuery = ""
                loadNotes()
                return true
            }
        })
        if (searchQuery.isNotBlank()) {
            searchItem.expandActionView()
            searchView.setQuery(searchQuery, false)
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_add_note -> {
                    openEditor(-1L, defaultFolderId())
                    true
                }
                R.id.action_filter -> {
                    FilterSheet.newInstance(currentQuery()).show(supportFragmentManager, "filter")
                    true
                }
                R.id.action_toggle_view -> {
                    listMode = !listMode
                    applyLayoutMode()
                    true
                }
                R.id.action_empty_trash -> {
                    confirmEmptyTrash()
                    true
                }
                else -> false
            }
        }
        updateFilterIndicator()
    }

    private fun setupDrawer() {
        val menu = binding.navView.menu
        listOf(R.id.nav_notes, R.id.nav_reminders, R.id.nav_archive, R.id.nav_trash)
            .forEach { menu.findItem(it)?.isCheckable = true }

        binding.navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_notes -> selectView(NoteView.NOTES, -1L)
                R.id.nav_reminders -> selectView(NoteView.REMINDERS, -1L)
                R.id.nav_archive -> selectView(NoteView.ARCHIVE, -1L)
                R.id.nav_trash -> selectView(NoteView.TRASH, -1L)
                R.id.nav_manage_folders -> {
                    binding.drawerRoot.closeDrawers()
                    manageFoldersLauncher.launch(Intent(this, FoldersActivity::class.java))
                }
                R.id.nav_settings -> {
                    binding.drawerRoot.closeDrawers()
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                else -> {
                    val folderId = folderMenuIds[item.itemId]
                    if (folderId == null) return@setNavigationItemSelectedListener false
                    selectView(NoteView.FOLDER, folderId)
                }
            }
            binding.drawerRoot.closeDrawers()
            true
        }
    }

    private fun registerFilterListener() {
        supportFragmentManager.setFragmentResultListener(
            FilterSheet.RESULT_KEY,
            this
        ) { _, bundle ->
            sort = runCatching { NoteSort.valueOf(bundle.getString(FilterSheet.ARG_SORT) ?: "") }
                .getOrDefault(NoteSort.UPDATED)
            filter = FilterSheet.filterFrom(bundle)
            updateFilterIndicator()
            loadNotes()
        }
    }

    private fun registerBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.drawerRoot.isDrawerOpen(GravityCompat.START) ->
                        binding.drawerRoot.closeDrawers()
                    currentView != NoteView.NOTES || currentFolderId != -1L ->
                        selectView(NoteView.NOTES, -1L)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    // endregion

    // region data

    private fun currentQuery(): NoteQuery = NoteQuery(
        view = currentView,
        folderId = currentFolderId,
        search = searchQuery,
        sort = sort,
        filter = filter
    )

    private fun reloadSoon() {
        mainHandler.removeCallbacks(reloadTask)
        mainHandler.postDelayed(reloadTask, 250L)
    }

    private fun loadNotes() {
        val query = currentQuery()
        Threads.load({ store.notes(query) }) { notes -> render(notes) }
    }

    private fun render(notes: List<NoteSummary>) {
        val ordered = if (notes.any { it.pinned } && currentView != NoteView.TRASH) {
            notes.sortedByDescending { it.pinned }
        } else {
            notes
        }
        adapter.submitList(ordered) { updateEmptyState(ordered) }
        binding.toolbar.title = titleForCurrentView()
        binding.toolbar.menu.findItem(R.id.action_empty_trash)?.isVisible =
            currentView == NoteView.TRASH
    }

    private fun refreshFolders() {
        Threads.load({ store.folders() to store.folderNoteCounts() }) { result ->
            val folders = result.first
            foldersById = folders.associateBy { it.id }
            val menu = binding.navView.menu
            folderMenuIds.keys.forEach { menu.removeItem(it) }
            folderMenuIds.clear()
            folders.forEachIndexed { index, folder ->
                val itemId = FOLDER_ITEM_BASE + index
                val item = menu.add(0, itemId, 30 + index, folder.name)
                item.setIcon(R.drawable.ic_folder)
                item.isCheckable = true
                item.isChecked = currentView == NoteView.FOLDER && currentFolderId == folder.id
                folderMenuIds[itemId] = folder.id
            }
            if (currentView == NoteView.FOLDER && currentFolderId != -1L) {
                binding.toolbar.title = titleForCurrentView()
            }
        }
    }

    private fun selectView(view: NoteView, folderId: Long) {
        currentView = view
        currentFolderId = folderId
        binding.toolbar.title = titleForCurrentView()
        refreshDrawerSelection()
        updateFilterIndicator()
        loadNotes()
    }

    private fun refreshDrawerSelection() {
        val menu = binding.navView.menu
        menu.findItem(R.id.nav_notes)?.isChecked = currentView == NoteView.NOTES
        menu.findItem(R.id.nav_reminders)?.isChecked = currentView == NoteView.REMINDERS
        menu.findItem(R.id.nav_archive)?.isChecked = currentView == NoteView.ARCHIVE
        menu.findItem(R.id.nav_trash)?.isChecked = currentView == NoteView.TRASH
        folderMenuIds.forEach { (itemId, folderId) ->
            menu.findItem(itemId)?.isChecked =
                currentView == NoteView.FOLDER && currentFolderId == folderId
        }
    }

    private fun titleForCurrentView(): String = when (currentView) {
        NoteView.NOTES -> getString(R.string.nav_notes)
        NoteView.REMINDERS -> getString(R.string.nav_reminders)
        NoteView.ARCHIVE -> getString(R.string.nav_archive)
        NoteView.TRASH -> getString(R.string.nav_trash)
        NoteView.FOLDER -> foldersById[currentFolderId]?.name ?: getString(R.string.folders_title)
    }

    private fun defaultFolderId(): Long = when {
        currentView == NoteView.FOLDER && currentFolderId > 0 -> currentFolderId
        else -> store.inboxId()
    }

    private fun updateEmptyState(notes: List<NoteSummary>) {
        val empty = notes.isEmpty()
        binding.emptyState.root.isVisible = empty
        if (!empty) {
            binding.emptyState.emptyAction.isVisible = false
            return
        }

        val titleRes: Int
        val bodyRes: Int
        when {
            searchQuery.isNotBlank() || filter.isActive -> {
                titleRes = R.string.empty_search_title
                bodyRes = R.string.empty_search_body
            }
            currentView == NoteView.TRASH -> {
                titleRes = R.string.empty_trash_title
                bodyRes = R.string.empty_trash_body
            }
            currentView == NoteView.ARCHIVE -> {
                titleRes = R.string.empty_archive_title
                bodyRes = R.string.empty_archive_body
            }
            currentView == NoteView.REMINDERS -> {
                titleRes = R.string.empty_reminders_title
                bodyRes = R.string.empty_reminders_body
            }
            currentView == NoteView.FOLDER -> {
                titleRes = R.string.empty_folder_title
                bodyRes = R.string.empty_folder_body
            }
            else -> {
                titleRes = R.string.empty_notes_title
                bodyRes = R.string.empty_notes_body
            }
        }
        binding.emptyState.emptyTitle.setText(titleRes)
        binding.emptyState.emptyBody.setText(bodyRes)

        val actionNeeded = searchQuery.isNotBlank() || filter.isActive
        binding.emptyState.emptyAction.isVisible = actionNeeded
        if (actionNeeded) {
            binding.emptyState.emptyAction.setText(R.string.action_clear_filters)
            binding.emptyState.emptyAction.setOnClickListener { clearFiltersAndSearch() }
        }
    }

    private fun clearFiltersAndSearch() {
        filter = NoteFilter()
        searchQuery = ""
        binding.toolbar.menu.findItem(R.id.action_search)?.collapseActionView()
        updateFilterIndicator()
        loadNotes()
    }

    private fun updateFilterIndicator() {
        val item = binding.toolbar.menu.findItem(R.id.action_filter) ?: return
        val color = ContextCompat.getColor(
            this,
            if (filter.isActive) R.color.color_primary else R.color.icon_tint
        )
        item.icon?.mutate()?.setTint(color)
        item.title = if (filter.isActive) {
            getString(R.string.filter_active)
        } else {
            getString(R.string.action_filter)
        }
    }

    // endregion

    // region card actions

    private fun openEditor(noteId: Long, folderId: Long) {
        editorLauncher.launch(
            Intent(this, NoteEditorActivity::class.java)
                .putExtra(NoteEditorActivity.EXTRA_NOTE_ID, noteId)
                .putExtra(NoteEditorActivity.EXTRA_FOLDER_ID, folderId)
        )
    }

    private fun showCardMenu(note: NoteSummary, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.card_actions, popup.menu)
        val pinItem = popup.menu.findItem(R.id.card_pin)
        val archiveItem = popup.menu.findItem(R.id.card_archive)
        val moveItem = popup.menu.findItem(R.id.card_move)
        val deleteItem = popup.menu.findItem(R.id.card_delete)

        if (currentView == NoteView.TRASH) {
            pinItem.isVisible = false
            moveItem.isVisible = false
            archiveItem.setTitle(R.string.action_restore)
            deleteItem.setTitle(R.string.action_delete)
        } else {
            pinItem.setTitle(if (note.pinned) R.string.action_unpin else R.string.action_pin)
            archiveItem.setTitle(
                if (note.archived) R.string.action_unarchive else R.string.action_archive
            )
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.card_pin -> {
                    Threads.io {
                        store.setPinned(note.id, !note.pinned)
                        Threads.post {
                            snackbar(if (note.pinned) R.string.msg_note_unpinned else R.string.msg_note_pinned)
                            loadNotes()
                        }
                    }
                    true
                }
                R.id.card_archive -> {
                    if (currentView == NoteView.TRASH) {
                        Threads.io {
                            store.restoreNote(note.id)
                            Threads.post {
                                snackbar(R.string.msg_note_restored)
                                loadNotes()
                            }
                        }
                    } else {
                        Threads.io {
                            store.setArchived(note.id, !note.archived)
                            Threads.post {
                                snackbar(
                                    if (note.archived) R.string.msg_note_unarchived
                                    else R.string.msg_note_archived
                                )
                                loadNotes()
                            }
                        }
                    }
                    true
                }
                R.id.card_move -> {
                    pendingMoveNoteId = note.id
                    folderPickerLauncher.launch(
                        Intent(this, FoldersActivity::class.java)
                            .putExtra(FoldersActivity.EXTRA_PICK_MODE, true)
                    )
                    true
                }
                R.id.card_delete -> {
                    if (currentView == NoteView.TRASH) {
                        confirmDeleteForever(note)
                    } else {
                        confirmTrash(note)
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun confirmTrash(note: NoteSummary) {
        val message = if (note.title.isBlank()) {
            getString(R.string.dialog_delete_note_message_untitled)
        } else {
            getString(R.string.dialog_delete_note_message, note.title)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_delete_note_title)
            .setMessage(message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> trashWithUndo(note) }
            .show()
    }

    private fun trashWithUndo(note: NoteSummary) {
        Threads.io {
            store.note(note.id)?.let { AlarmScheduler.cancelNote(this, it) }
            store.trashNote(note.id)
            Threads.post {
                Snackbar.make(binding.mainContent, R.string.msg_note_deleted, Snackbar.LENGTH_LONG)
                    .setAction(R.string.action_undo) {
                        Threads.io {
                            store.restoreNote(note.id)
                            Threads.post {
                                snackbar(R.string.msg_note_restored)
                                loadNotes()
                            }
                        }
                    }
                    .show()
                loadNotes()
            }
        }
    }

    private fun confirmDeleteForever(note: NoteSummary) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_delete_forever_title)
            .setMessage(R.string.dialog_delete_forever_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                Threads.io {
                    store.deleteForever(note.id)
                    Threads.post { loadNotes() }
                }
            }
            .show()
    }

    private fun confirmEmptyTrash() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_empty_trash_title)
            .setMessage(R.string.dialog_empty_trash_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_empty_trash) { _, _ ->
                Threads.io {
                    store.emptyTrash()
                    Threads.post {
                        snackbar(R.string.msg_trash_emptied)
                        loadNotes()
                    }
                }
            }
            .show()
    }

    // endregion

    // region append

    private fun requestAppend(from: Int, to: Int) {
        val notes = adapter.currentList
        if (from !in notes.indices || to !in notes.indices) return
        val source = notes[from]
        val target = notes[to]
        if (source.id == target.id) return
        val sourceName = source.title.ifBlank { getString(R.string.untitled_note) }
        val targetName = target.title.ifBlank { getString(R.string.untitled_note) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_append_title)
            .setMessage(getString(R.string.dialog_append_message, sourceName, targetName))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.dialog_append_confirm) { _, _ ->
                appendNotes(source.id, target.id)
            }
            .show()
    }

    private fun appendNotes(sourceId: Long, targetId: Long) {
        Threads.io {
            val token = store.appendNotes(sourceId, targetId)
            val merged = store.note(targetId)
            Threads.post {
                if (token == null) {
                    Toast.makeText(this, R.string.msg_could_not_save, Toast.LENGTH_SHORT).show()
                    return@post
                }
                if (merged != null) AlarmScheduler.syncNote(this, merged)
                showCombined(token)
                loadNotes()
            }
        }
    }

    private fun showCombined(token: AppendUndoToken) {
        Snackbar.make(binding.mainContent, R.string.msg_notes_combined, Snackbar.LENGTH_LONG)
            .setAction(R.string.action_undo) {
                Threads.io {
                    store.undoAppend(token)
                    Threads.post { loadNotes() }
                }
            }
            .show()
    }

    // endregion

    private fun snackbar(messageRes: Int) {
        Snackbar.make(binding.mainContent, messageRes, Snackbar.LENGTH_SHORT).show()
    }

    companion object {
        private const val STATE_VIEW = "state_view"
        private const val STATE_FOLDER = "state_folder"
        private const val STATE_SEARCH = "state_search"
        private const val STATE_SORT = "state_sort"
        private const val STATE_COLORS = "state_colors"
        private const val STATE_PRIORITIES = "state_priorities"
        private const val STATE_DEADLINE = "state_deadline"
        private const val STATE_STATUS = "state_status"
        private const val STATE_LIST_MODE = "state_list_mode"
        private const val FOLDER_ITEM_BASE = 5000
    }
}

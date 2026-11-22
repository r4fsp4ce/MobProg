package com.example.mobprog.model

/** What the drawer is pointing at. */
enum class NoteView {
    NOTES,
    REMINDERS,
    ARCHIVE,
    TRASH,
    FOLDER
}

enum class NoteSort {
    UPDATED,
    CREATED,
    DEADLINE,
    PRIORITY,
    TITLE
}

enum class StatusFilter {
    ANY,
    OPEN,
    DONE
}

/** The filter sheet's state. */
data class NoteFilter(
    val colors: Set<NoteColor> = emptySet(),
    val priorities: Set<Priority> = emptySet(),
    val hasDeadline: Boolean = false,
    val status: StatusFilter = StatusFilter.ANY
) {
    val isActive: Boolean
        get() = hasCustomFilter || hasDeadline || status != StatusFilter.ANY

    /** Colour/priority picks made in the sheet; the quick chips are presets, not filters. */
    val hasCustomFilter: Boolean
        get() = colors.isNotEmpty() || priorities.isNotEmpty()

    fun cleared(): NoteFilter = NoteFilter()
}

/** Everything needed to ask the store for the cards to show. */
data class NoteQuery(
    val view: NoteView = NoteView.NOTES,
    val folderId: Long = -1L,
    val search: String = "",
    val sort: NoteSort = NoteSort.UPDATED,
    val filter: NoteFilter = NoteFilter()
) {
    /** Pinned notes are shown first, but they are not part of the store's ORDER BY. */
    fun matchesPinnedFirst(): Boolean = view != NoteView.TRASH && view != NoteView.REMINDERS
}

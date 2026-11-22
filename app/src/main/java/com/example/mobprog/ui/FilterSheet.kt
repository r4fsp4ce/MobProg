package com.example.mobprog.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.mobprog.R
import com.example.mobprog.databinding.SheetFilterBinding
import com.example.mobprog.model.NoteColor
import com.example.mobprog.model.NoteFilter
import com.example.mobprog.model.NoteQuery
import com.example.mobprog.model.NoteSort
import com.example.mobprog.model.Priority
import com.example.mobprog.model.StatusFilter
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** Filter and sort sheet. Sends its result through the fragment result API. */
class FilterSheet : BottomSheetDialogFragment() {

    private var binding: SheetFilterBinding? = null
    private val bind: SheetFilterBinding get() = requireNotNull(binding)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val viewBinding = SheetFilterBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = arguments ?: Bundle()
        restore(args)

        bind.filterApply.setOnClickListener {
            val bundle = read(cleared = false)
            parentFragmentManager.setFragmentResult(RESULT_KEY, bundle)
            dismiss()
        }
        bind.filterClear.setOnClickListener {
            parentFragmentManager.setFragmentResult(RESULT_KEY, read(cleared = true))
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private fun restore(args: Bundle) {
        val sort = runCatching { NoteSort.valueOf(args.getString(ARG_SORT) ?: "") }
            .getOrDefault(NoteSort.UPDATED)
        val selectedSort = when (sort) {
            NoteSort.UPDATED -> bind.sortUpdated
            NoteSort.CREATED -> bind.sortCreated
            NoteSort.DEADLINE -> bind.sortDeadline
            NoteSort.PRIORITY -> bind.sortPriority
            NoteSort.TITLE -> bind.sortTitle
        }
        selectedSort.isChecked = true

        val colors = args.getStringArrayList(ARG_COLORS).orEmpty().toSet()
        NoteColor.entries.forEach { color ->
            chipFor(color).isChecked = color.key in colors
        }

        val priorities = args.getIntegerArrayList(ARG_PRIORITIES).orEmpty().toSet()
        Priority.selectable.forEach { priority ->
            chipFor(priority).isChecked = priority.value in priorities
        }

        val status = runCatching { StatusFilter.valueOf(args.getString(ARG_STATUS) ?: "") }
            .getOrDefault(StatusFilter.ANY)
        when (status) {
            StatusFilter.ANY -> bind.chipStatusAny.isChecked = true
            StatusFilter.OPEN -> bind.chipStatusOpen.isChecked = true
            StatusFilter.DONE -> bind.chipStatusDone.isChecked = true
        }

        bind.switchDeadline.isChecked = args.getBoolean(ARG_HAS_DEADLINE, false)
    }

    private fun read(cleared: Boolean): Bundle {
        val colors = if (cleared) emptySet() else {
            NoteColor.entries.filter { chipFor(it).isChecked }.toSet()
        }
        val priorities = if (cleared) emptySet() else {
            Priority.selectable.filter { chipFor(it).isChecked }.toSet()
        }
        val status = when {
            cleared -> StatusFilter.ANY
            bind.chipStatusOpen.isChecked -> StatusFilter.OPEN
            bind.chipStatusDone.isChecked -> StatusFilter.DONE
            else -> StatusFilter.ANY
        }
        val sort = when (bind.sortGroup.checkedRadioButtonId) {
            R.id.sort_created -> NoteSort.CREATED
            R.id.sort_deadline -> NoteSort.DEADLINE
            R.id.sort_priority -> NoteSort.PRIORITY
            R.id.sort_title -> NoteSort.TITLE
            else -> if (cleared) NoteSort.UPDATED else currentSort()
        }
        return Bundle().apply {
            putString(ARG_SORT, sort.name)
            putStringArrayList(ARG_COLORS, ArrayList(colors.map { it.key }))
            putIntegerArrayList(ARG_PRIORITIES, ArrayList(priorities.map { it.value }))
            putBoolean(ARG_HAS_DEADLINE, if (cleared) false else bind.switchDeadline.isChecked)
            putString(ARG_STATUS, status.name)
            putBoolean(ARG_CLEARED, cleared)
        }
    }

    private fun currentSort(): NoteSort =
        runCatching { NoteSort.valueOf(arguments?.getString(ARG_SORT) ?: "") }
            .getOrDefault(NoteSort.UPDATED)

    private fun chipFor(color: NoteColor) = when (color) {
        NoteColor.DEFAULT -> bind.chipColorDefault
        NoteColor.RED -> bind.chipColorRed
        NoteColor.ORANGE -> bind.chipColorOrange
        NoteColor.YELLOW -> bind.chipColorYellow
        NoteColor.GREEN -> bind.chipColorGreen
        NoteColor.TEAL -> bind.chipColorTeal
        NoteColor.BLUE -> bind.chipColorBlue
        NoteColor.INDIGO -> bind.chipColorIndigo
        NoteColor.PURPLE -> bind.chipColorPurple
        NoteColor.PINK -> bind.chipColorPink
        NoteColor.BROWN -> bind.chipColorBrown
        NoteColor.GRAY -> bind.chipColorGray
    }

    private fun chipFor(priority: Priority) = when (priority) {
        Priority.HIGHEST -> bind.chipPriority1
        Priority.HIGH -> bind.chipPriority2
        else -> bind.chipPriority3
    }

    companion object {
        const val RESULT_KEY = "filter_result"
        const val ARG_SORT = "arg_sort"
        const val ARG_COLORS = "arg_colors"
        const val ARG_PRIORITIES = "arg_priorities"
        const val ARG_HAS_DEADLINE = "arg_has_deadline"
        const val ARG_STATUS = "arg_status"
        const val ARG_CLEARED = "arg_cleared"

        fun newInstance(query: NoteQuery): FilterSheet = FilterSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_SORT, query.sort.name)
                putStringArrayList(ARG_COLORS, ArrayList(query.filter.colors.map { it.key }))
                putIntegerArrayList(ARG_PRIORITIES, ArrayList(query.filter.priorities.map { it.value }))
                putBoolean(ARG_HAS_DEADLINE, query.filter.hasDeadline)
                putString(ARG_STATUS, query.filter.status.name)
            }
        }

        fun filterFrom(bundle: Bundle): NoteFilter = NoteFilter(
            colors = bundle.getStringArrayList(ARG_COLORS).orEmpty()
                .map { NoteColor.fromKey(it) }
                .toSet(),
            priorities = bundle.getIntegerArrayList(ARG_PRIORITIES).orEmpty()
                .map { Priority.fromValue(it) }
                .toSet(),
            hasDeadline = bundle.getBoolean(ARG_HAS_DEADLINE, false),
            status = runCatching { StatusFilter.valueOf(bundle.getString(ARG_STATUS) ?: "") }
                .getOrDefault(StatusFilter.ANY)
        )
    }
}

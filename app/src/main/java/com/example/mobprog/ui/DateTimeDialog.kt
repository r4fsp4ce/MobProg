package com.example.mobprog.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.mobprog.R
import com.example.mobprog.databinding.DialogDatetimeBinding
import com.example.mobprog.util.Dates
import java.text.DateFormatSymbols
import java.time.LocalDate

/**
 * Month dropdown + year dropdown + day grid + all-day circle and a 24h time field.
 * Kept as a plain view container so the quick-note overlay can host it inside a
 * WindowManager window instead of a Dialog.
 */
class DateTimePicker(
    private val context: Context,
    initialMillis: Long?,
    initialAllDay: Boolean
) {

    private val binding = DialogDatetimeBinding.inflate(LayoutInflater.from(context))
    private val months: List<String> = DateFormatSymbols.getInstance().months.take(12)
    private val years = mutableListOf<Int>()

    private var selected: LocalDate = LocalDate.now()
    private var allDay: Boolean = initialAllDay
    private var ignoreCallbacks = false

    var onPicked: ((Long, Boolean) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    val view: View get() = binding.root

    init {
        initialMillis?.let { selected = Dates.toLocalDate(it) }
        setUpSpinners()
        setUpTimeField(initialMillis)
        setUpAllDayToggle()
        buildGrid()

        binding.deadlineCancel.setOnClickListener { onCancel?.invoke() }
        binding.deadlineOk.setOnClickListener { confirm() }
    }

    private fun setUpSpinners() {
        val monthAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, months)
        monthAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.monthSpinner.adapter = monthAdapter

        val thisYear = LocalDate.now().year
        years.clear()
        years.addAll((thisYear - 1..thisYear + 4).toList())
        if (selected.year !in years) years.add(selected.year)
        years.sort()
        val yearAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, years)
        yearAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.yearSpinner.adapter = yearAdapter

        ignoreCallbacks = true
        binding.monthSpinner.setSelection(selected.monthValue - 1)
        binding.yearSpinner.setSelection(years.indexOf(selected.year).coerceAtLeast(0))
        ignoreCallbacks = false

        binding.monthSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (ignoreCallbacks) return
                changeMonth(position + 1)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.yearSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (ignoreCallbacks) return
                changeYear(years[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun changeMonth(month: Int) {
        val day = selected.dayOfMonth
        val firstOfMonth = selected.withDayOfMonth(1).withMonth(month)
        selected = firstOfMonth.withDayOfMonth(minOf(day, firstOfMonth.lengthOfMonth()))
        buildGrid()
        clearError()
    }

    private fun changeYear(year: Int) {
        val day = selected.dayOfMonth
        val firstOfMonth = selected.withDayOfMonth(1).withYear(year)
        selected = firstOfMonth.withDayOfMonth(minOf(day, firstOfMonth.lengthOfMonth()))
        buildGrid()
        clearError()
    }

    private fun buildGrid() {
        binding.dayGrid.removeAllViews()
        // java.time: Sunday = 7, so dayOfWeek.value % 7 gives Sunday = 0.
        val leadingBlanks = selected.withDayOfMonth(1).dayOfWeek.value % 7
        repeat(leadingBlanks) { binding.dayGrid.addView(blankCell()) }
        for (day in 1..selected.lengthOfMonth()) {
            binding.dayGrid.addView(dayCell(day))
        }
    }

    private fun blankCell(): View = View(context).apply {
        layoutParams = gridParams()
    }

    private fun dayCell(day: Int): TextView {
        val isSelected = day == selected.dayOfMonth
        val isToday = selected.withDayOfMonth(day) == LocalDate.now()
        return TextView(context).apply {
            layoutParams = gridParams()
            text = day.toString()
            textSize = 14f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setTextColor(
                if (isSelected) ContextCompat.getColor(context, R.color.color_on_primary)
                else ContextCompat.getColor(context, R.color.color_on_surface)
            )
            when {
                isSelected -> setBackgroundResource(R.drawable.bg_day_selected)
                isToday -> setBackgroundResource(R.drawable.bg_day_today)
            }
            contentDescription = context.getString(
                R.string.deadline_day_content_description,
                months[selected.monthValue - 1],
                day
            )
            setOnClickListener {
                selected = selected.withDayOfMonth(day)
                buildGrid()
                clearError()
            }
        }
    }

    private fun gridParams(): GridLayout.LayoutParams {
        val params = GridLayout.LayoutParams()
        params.width = 0
        params.height = dp(42)
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
        return params
    }

    private fun setUpTimeField(initialMillis: Long?) {
        val time = initialMillis?.let { Dates.toLocalDateTime(it) }
        val hour = time?.hour ?: 9
        val minute = time?.minute ?: 0
        binding.timeInput.setText(Dates.timeText(hour, minute))
        binding.timeInput.isEnabled = !allDay
    }

    private fun setUpAllDayToggle() {
        binding.allDayToggle.setOnClickListener {
            allDay = !allDay
            renderAllDayToggle()
            clearError()
        }
        renderAllDayToggle()
    }

    /** Hollow circle = all day, filled circle = a specific time. */
    private fun renderAllDayToggle() {
        binding.allDayToggle.setBackgroundResource(
            if (allDay) R.drawable.bg_day_selected else R.drawable.bg_day_today
        )
        binding.allDayToggle.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                context,
                if (allDay) R.color.color_on_primary else R.color.icon_tint
            )
        )
        binding.timeInput.isEnabled = !allDay
        binding.timeInput.alpha = if (allDay) 0.4f else 1f
    }

    private fun confirm() {
        val millis: Long
        if (allDay) {
            millis = Dates.endOfDay(selected)
        } else {
            val parsed = Dates.parseTime(binding.timeInput.text?.toString().orEmpty())
            if (parsed == null) {
                showError(R.string.msg_time_format)
                return
            }
            millis = Dates.toMillis(selected, parsed.first, parsed.second)
        }
        if (Dates.isPast(millis)) {
            showError(R.string.msg_deadline_in_past)
            return
        }
        onPicked?.invoke(millis, allDay)
    }

    private fun showError(messageRes: Int) {
        binding.deadlineError.isVisible = true
        binding.deadlineError.setText(messageRes)
    }

    private fun clearError() {
        binding.deadlineError.isVisible = false
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}

/** Dialog wrapper around [DateTimePicker], used by the editor. */
class DateTimeDialog(
    context: Context,
    initialMillis: Long?,
    initialAllDay: Boolean,
    onPicked: (Long, Boolean) -> Unit
) {

    private val dialog = Dialog(context)

    init {
        val picker = DateTimePicker(context, initialMillis, initialAllDay)
        picker.onPicked = { millis, allDay ->
            onPicked(millis, allDay)
            dialog.dismiss()
        }
        picker.onCancel = { dialog.dismiss() }

        val padding = (24 * context.resources.displayMetrics.density).toInt()
        val container = FrameLayout(context).apply {
            setPadding(padding, padding, padding, padding)
            addView(picker.view)
        }
        dialog.setContentView(container)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }
}

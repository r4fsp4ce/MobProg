package com.example.mobprog.util

import android.content.Context
import com.example.mobprog.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Date, time and countdown formatting for deadlines. Everything is local time. */
object Dates {

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

    fun zone(): ZoneId = ZoneId.systemDefault()

    fun toLocalDate(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone()).toLocalDate()

    fun toLocalDateTime(millis: Long): LocalDateTime =
        Instant.ofEpochMilli(millis).atZone(zone()).toLocalDateTime()

    fun toMillis(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone()).toInstant().toEpochMilli()

    /** End of the day, used for all-day deadlines (23:59). */
    fun endOfDay(date: LocalDate): Long = toMillis(date, 23, 59)

    /** "Today", "Tomorrow" or "Mon 5 May", with the time appended for timed deadlines. */
    fun deadlineLabel(context: Context, millis: Long, allDay: Boolean): String {
        val date = toLocalDate(millis)
        val today = LocalDate.now(zone())
        val day = when {
            date == today -> context.getString(R.string.deadline_today_all_day)
            date == today.plusDays(1) -> context.getString(R.string.deadline_tomorrow_all_day)
            else -> dayFormat.format(date)
        }
        if (allDay) return day
        val todayPrefix = when (date) {
            today -> context.getString(R.string.deadline_today, timeFormat.format(toLocalDateTime(millis)))
            today.plusDays(1) ->
                context.getString(R.string.deadline_tomorrow, timeFormat.format(toLocalDateTime(millis)))
            else -> context.getString(
                R.string.deadline_date_time,
                dayFormat.format(date),
                timeFormat.format(toLocalDateTime(millis))
            )
        }
        return todayPrefix
    }

    /** "Overdue", "in 2 h", "in 3 d"… */
    fun countdown(context: Context, millis: Long): String {
        val diff = millis - System.currentTimeMillis()
        if (diff < 0) return context.getString(R.string.countdown_overdue)
        val minutes = diff / 60_000
        return when {
            minutes < 1 -> context.getString(R.string.countdown_now)
            minutes < 60 -> context.getString(R.string.countdown_minutes, minutes)
            minutes < 60 * 24 -> context.getString(R.string.countdown_hours, minutes / 60)
            else -> context.getString(R.string.countdown_days, minutes / (60 * 24))
        }
    }

    fun timeText(hour: Int, minute: Int): String =
        String.format(Locale.getDefault(), "%02d:%02d", hour, minute)

    /** Parses HH:mm, returns null when the text is not a valid 24 hour time. */
    fun parseTime(text: String): Pair<Int, Int>? {
        val match = Regex("^(\\d{1,2}):(\\d{2})$").find(text.trim()) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    fun isPast(millis: Long): Boolean = millis < System.currentTimeMillis() - 1_000
}

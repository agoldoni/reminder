package it.agoldoni.reminder.platform

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun format(pattern: String, millis: Long): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))

private fun calendarAt(millis: Long): Calendar =
    Calendar.getInstance().apply { timeInMillis = millis }

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun formatDateTime(millis: Long): String = format("dd/MM/yyyy HH:mm", millis)

actual fun formatDate(millis: Long): String = format("dd/MM/yyyy", millis)

actual fun formatTime(millis: Long): String = format("HH:mm", millis)

actual fun formatFileDate(millis: Long): String =
    SimpleDateFormat("yyyyMMdd", Locale.ITALY).format(Date(millis))

actual fun startOfToday(): Long = calendarAt(nowMillis()).apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

actual fun withDateFrom(baseMillis: Long, dateMillis: Long): Long {
    val source = calendarAt(dateMillis)
    return calendarAt(baseMillis).apply {
        set(Calendar.YEAR, source.get(Calendar.YEAR))
        set(Calendar.MONTH, source.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, source.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

actual fun withTime(baseMillis: Long, hour: Int, minute: Int): Long =
    calendarAt(baseMillis).apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

actual fun hourOf(millis: Long): Int = calendarAt(millis).get(Calendar.HOUR_OF_DAY)

actual fun minuteOf(millis: Long): Int = calendarAt(millis).get(Calendar.MINUTE)

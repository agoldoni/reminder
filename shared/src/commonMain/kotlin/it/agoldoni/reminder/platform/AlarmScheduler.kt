package it.agoldoni.reminder.platform

import it.agoldoni.reminder.data.EventEntity

/** Programmazione degli allarmi: `AlarmManager` su Android, timer in-process su desktop. */
interface AlarmScheduler {
    fun schedule(event: EventEntity)
    fun cancel(eventId: Long)
}

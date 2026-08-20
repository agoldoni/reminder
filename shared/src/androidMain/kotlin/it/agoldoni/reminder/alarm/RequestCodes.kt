package it.agoldoni.reminder.alarm

/**
 * Spazi di requestCode disgiunti per i PendingIntent di un evento.
 *
 * Prima gli allarmi usavano `id`, gli snooze `id + minuti * 10_000` e le azioni delle notifiche
 * `notificationId * 10 + n`: intervalli che si sovrappongono (l'azione "Chiudi" dell'evento 1
 * collideva con l'allarme dell'evento 10). Ogni evento ha ora un blocco di [SLOTS] codici.
 */
internal object RequestCodes {

    private const val SLOTS = 8

    const val SNOOZE_SHORT_MINUTES = 5
    const val SNOOZE_LONG_MINUTES = 60

    fun alarm(eventId: Long): Int = slot(eventId, 0)

    fun snooze(eventId: Long, snoozeMinutes: Int): Int = slot(eventId, snoozeSlot(snoozeMinutes))

    fun dismissAction(eventId: Long): Int = slot(eventId, 3)

    fun snoozeAction(eventId: Long, snoozeMinutes: Int): Int =
        slot(eventId, 3 + snoozeSlot(snoozeMinutes))

    /**
     * Tutti i codici con cui può esistere un allarme dell'evento: principale, snooze pendenti e
     * gli equivalenti in uso prima degli slot. È l'elenco da annullare quando l'evento sparisce.
     */
    fun allAlarms(eventId: Long): List<Int> = listOf(
        alarm(eventId),
        snooze(eventId, SNOOZE_SHORT_MINUTES),
        snooze(eventId, SNOOZE_LONG_MINUTES),
        legacyAlarm(eventId),
        legacySnooze(eventId, SNOOZE_SHORT_MINUTES),
        legacySnooze(eventId, SNOOZE_LONG_MINUTES)
    )

    /** Codice in uso prima degli slot: serve solo ad annullare gli allarmi già programmati. */
    fun legacyAlarm(eventId: Long): Int = eventId.toInt()

    private fun legacySnooze(eventId: Long, snoozeMinutes: Int): Int =
        eventId.toInt() + snoozeMinutes * 10_000

    private fun snoozeSlot(snoozeMinutes: Int): Int = if (snoozeMinutes <= 5) 1 else 2

    private fun slot(eventId: Long, slot: Int): Int = (eventId * SLOTS + slot).toInt()
}

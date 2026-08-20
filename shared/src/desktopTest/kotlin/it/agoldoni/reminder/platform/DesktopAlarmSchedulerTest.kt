package it.agoldoni.reminder.platform

import it.agoldoni.reminder.data.EventEntity
import it.agoldoni.reminder.data.FakeEventDao
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MINUTE = 60_000L

class DesktopAlarmSchedulerTest {

    private class RecordingNotifier : EventNotifier {
        val shown = mutableListOf<Pair<EventEntity, Boolean>>()
        override fun show(event: EventEntity, overdue: Boolean) {
            shown += event to overdue
        }
    }

    private fun event(id: Long, minutiAllaScadenza: Long, anticipo: Int = 0) = EventEntity(
        id = id,
        title = "Evento $id",
        dateTimeMillis = nowMillis() + minutiAllaScadenza * MINUTE,
        advanceMinutes = anticipo
    )

    @Test
    fun `la notifica arriva all'ora della scadenza meno l'anticipo`() = runTest {
        val notifier = RecordingNotifier()
        val scheduler = DesktopAlarmScheduler(FakeEventDao(), backgroundScope) { notifier }

        scheduler.schedule(event(id = 1, minutiAllaScadenza = 10, anticipo = 5))

        advanceTimeBy(4 * MINUTE); runCurrent()
        assertTrue(notifier.shown.isEmpty(), "non deve notificare prima dei 5 minuti")

        advanceTimeBy(2 * MINUTE); runCurrent()
        assertEquals(1, notifier.shown.size)
        assertEquals(false, notifier.shown.single().second, "non è un recupero")
    }

    @Test
    fun `annullare impedisce la notifica`() = runTest {
        val notifier = RecordingNotifier()
        val scheduler = DesktopAlarmScheduler(FakeEventDao(), backgroundScope) { notifier }

        scheduler.schedule(event(id = 1, minutiAllaScadenza = 10))
        scheduler.cancel(1)

        advanceTimeBy(20 * MINUTE); runCurrent()
        assertTrue(notifier.shown.isEmpty())
    }

    @Test
    fun `riprogrammare lo stesso evento non produce due notifiche`() = runTest {
        val notifier = RecordingNotifier()
        val scheduler = DesktopAlarmScheduler(FakeEventDao(), backgroundScope) { notifier }

        val evento = event(id = 1, minutiAllaScadenza = 10)
        scheduler.schedule(evento)
        scheduler.schedule(evento.copy(dateTimeMillis = evento.dateTimeMillis + 5 * MINUTE))

        advanceTimeBy(30 * MINUTE); runCurrent()
        assertEquals(1, notifier.shown.size)
    }

    @Test
    fun `un evento gia scaduto non viene programmato`() = runTest {
        val notifier = RecordingNotifier()
        val scheduler = DesktopAlarmScheduler(FakeEventDao(), backgroundScope) { notifier }

        scheduler.schedule(event(id = 1, minutiAllaScadenza = -10))

        advanceTimeBy(60 * MINUTE); runCurrent()
        assertTrue(notifier.shown.isEmpty(), "gli scaduti passano da bootstrap, non da schedule")
    }

    @Test
    fun `bootstrap riprogramma i futuri e recupera gli scaduti`() = runTest {
        val futuro = event(id = 1, minutiAllaScadenza = 10)
        val scaduto = event(id = 2, minutiAllaScadenza = -30)
        val completato = event(id = 3, minutiAllaScadenza = -30).copy(completed = true)
        val notifier = RecordingNotifier()
        val scheduler = DesktopAlarmScheduler(
            FakeEventDao(listOf(futuro, scaduto, completato)), backgroundScope
        ) { notifier }

        scheduler.bootstrap()
        runCurrent()

        assertEquals(listOf(2L to true), notifier.shown.map { it.first.id to it.second })

        advanceTimeBy(11 * MINUTE); runCurrent()
        assertEquals(listOf(2L to true, 1L to false), notifier.shown.map { it.first.id to it.second })
    }

    @Test
    fun `l'azione Completa segna l'evento e annulla l'allarme`() = runTest {
        val evento = event(id = 1, minutiAllaScadenza = 10)
        val dao = FakeEventDao(listOf(evento))
        val notifier = RecordingNotifier()
        var azione: (suspend (NotificationAction, EventEntity) -> Unit)? = null
        val scheduler = DesktopAlarmScheduler(dao, backgroundScope) { handler ->
            azione = handler
            notifier
        }
        scheduler.schedule(evento)

        azione!!.invoke(NotificationAction.COMPLETE, evento)
        runCurrent()

        assertTrue(dao.events.single().completed, "l'evento deve risultare completato")
        advanceTimeBy(30 * MINUTE); runCurrent()
        assertTrue(notifier.shown.isEmpty(), "l'allarme dev'essere stato annullato")
    }

    @Test
    fun `l'azione Posticipa rimanda la notifica di cinque minuti`() = runTest {
        val evento = event(id = 1, minutiAllaScadenza = 10)
        val notifier = RecordingNotifier()
        var azione: (suspend (NotificationAction, EventEntity) -> Unit)? = null
        val scheduler = DesktopAlarmScheduler(FakeEventDao(listOf(evento)), backgroundScope) { handler ->
            azione = handler
            notifier
        }
        scheduler.schedule(evento)

        azione!!.invoke(NotificationAction.SNOOZE_SHORT, evento)
        runCurrent()

        advanceTimeBy(4 * MINUTE); runCurrent()
        assertTrue(notifier.shown.isEmpty(), "non prima dei 5 minuti di rinvio")

        advanceTimeBy(2 * MINUTE); runCurrent()
        assertEquals(1, notifier.shown.size)
    }
}

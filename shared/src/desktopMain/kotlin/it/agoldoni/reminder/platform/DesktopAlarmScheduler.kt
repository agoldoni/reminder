package it.agoldoni.reminder.platform

import it.agoldoni.reminder.data.EventDao
import it.agoldoni.reminder.data.EventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Allarmi desktop: niente `AlarmManager`, ma una coroutine in attesa per ogni evento.
 * Vale finché il processo è vivo; la persistenza fra sessioni è garantita da [bootstrap],
 * che riprogramma i futuri e recupera gli scaduti a ogni avvio.
 */
class DesktopAlarmScheduler(
    private val dao: EventDao,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    notifierFactory: (suspend (NotificationAction, EventEntity) -> Unit) -> EventNotifier =
        { onAction -> DesktopNotifier(scope, onAction) }
) : AlarmScheduler {

    private val pending = ConcurrentHashMap<Long, Job>()

    private val notifier: EventNotifier = notifierFactory { action, event -> handle(action, event) }

    override fun schedule(event: EventEntity) {
        cancel(event.id)
        val triggerAt = event.dateTimeMillis - event.advanceMinutes * 60_000L
        val wait = triggerAt - nowMillis()
        if (wait <= 0) return
        pending[event.id] = scope.launch {
            delay(wait)
            pending.remove(event.id)
            notifier.show(event)
        }
    }

    override fun cancel(eventId: Long) {
        pending.remove(eventId)?.cancel()
    }

    /** All'avvio: riprogramma gli eventi futuri e notifica quelli scaduti ad app spenta. */
    fun bootstrap() {
        scope.launch {
            val now = nowMillis()
            dao.getFutureEvents(now).forEach { schedule(it) }
            dao.getOverdueEvents(now).forEach { notifier.show(it, overdue = true) }
        }
    }

    private suspend fun handle(action: NotificationAction, event: EventEntity) {
        when (action) {
            NotificationAction.SNOOZE_SHORT -> snooze(event, SNOOZE_SHORT_MINUTES)
            NotificationAction.SNOOZE_LONG -> snooze(event, SNOOZE_LONG_MINUTES)
            NotificationAction.COMPLETE -> {
                cancel(event.id)
                dao.markCompleted(event.id, nowMillis())
            }
        }
    }

    private fun snooze(event: EventEntity, minutes: Int) {
        cancel(event.id)
        pending[event.id] = scope.launch {
            delay(minutes * 60_000L)
            pending.remove(event.id)
            notifier.show(event)
        }
    }

    private companion object {
        const val SNOOZE_SHORT_MINUTES = 5
        const val SNOOZE_LONG_MINUTES = 60
    }
}

package it.agoldoni.reminder.sync

import it.agoldoni.reminder.data.EventDao
import it.agoldoni.reminder.data.EventEntity
import it.agoldoni.reminder.platform.AlarmScheduler
import it.agoldoni.reminder.platform.nowMillis

/** Che cosa ha prodotto l'applicazione di un lotto di eventi remoti. */
data class MergeReport(
    val inserted: Int = 0,
    val updated: Int = 0,
    val ignored: Int = 0
) {
    val applied: Int get() = inserted + updated
}

/**
 * Ciò che si manda a un peer, con l'istante fino al quale il mittente garantisce di aver dato
 * tutto. [upTo] è letto **prima** della query: una scrittura che cade in mezzo finisce comunque
 * nel lotto e verrà rispedita al giro successivo — si preferisce un rinvio a una perdita.
 */
data class OutgoingBatch(val events: List<SyncEvent>, val upTo: Long)

/**
 * Il motore di replica: prende gli eventi che arrivano dall'altro dispositivo, decide con
 * [resolveMerge] che farne, e rimette in riga gli allarmi di ciò che ha toccato.
 *
 * Non sa nulla di socket né di crittografia: riceve una lista e restituisce un resoconto. È ciò
 * che permette di verificarne la convergenza senza mettere in mezzo la rete.
 */
class SyncEngine(
    private val events: EventDao,
    private val alarms: AlarmScheduler,
    private val now: () -> Long = ::nowMillis
) {

    /**
     * Ciò che va mandato a un peer fermo a [since]. L'estremo è incluso: vedi
     * `EventDao.changedSince`, il rinvio dell'ultimo millisecondo è voluto.
     */
    suspend fun changesSince(since: Long): OutgoingBatch {
        val upTo = now()
        return OutgoingBatch(events.changedSince(since).map { it.toSyncEvent() }, upTo)
    }

    /**
     * Applica un lotto in arrivo. Rieseguirlo sullo stesso lotto non cambia nulla: il confronto è
     * per `uuid` e la regola scarta ciò che non è più recente, quindi la seconda passata decide
     * `Keep` su tutto. È da qui che viene l'idempotenza, non da un controllo sui duplicati.
     */
    suspend fun apply(incoming: List<SyncEvent>): MergeReport {
        var inserted = 0
        var updated = 0
        var ignored = 0

        for (remote in incoming) {
            val local = events.getByUuid(remote.uuid)
            when (val decision = resolveMerge(local, remote.toEntity())) {
                is MergeDecision.Insert -> {
                    val id = events.insert(decision.event)
                    inserted++
                    reschedule(decision.event.copy(id = id))
                }

                is MergeDecision.Update -> {
                    events.update(decision.event)
                    updated++
                    reschedule(decision.event)
                }

                MergeDecision.Keep -> ignored++
            }
        }
        return MergeReport(inserted, updated, ignored)
    }

    /**
     * Un evento arrivato dalla rete cambia anche quando suona la sveglia. Cancellare prima di
     * riprogrammare non è ridondante: un evento diventato completato, cancellato o spostato
     * indietro deve perdere l'allarme che aveva, e `schedule` da solo non lo toglierebbe.
     */
    private fun reschedule(event: EventEntity) {
        alarms.cancel(event.id)
        if (!event.deleted && !event.completed) alarms.schedule(event)
    }
}

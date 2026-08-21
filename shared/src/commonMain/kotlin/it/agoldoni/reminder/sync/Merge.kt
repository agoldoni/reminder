package it.agoldoni.reminder.sync

import it.agoldoni.reminder.data.EventEntity

/** Che cosa fare di un evento arrivato dall'altro dispositivo. */
sealed interface MergeDecision {
    /** Sconosciuto qui: entra come riga nuova, con un id locale suo. */
    data class Insert(val event: EventEntity) : MergeDecision

    /** Il remoto è più recente: vince, ma l'id locale resta quello che era. */
    data class Update(val event: EventEntity) : MergeDecision

    /** Il locale è uguale o più recente: non si tocca niente. */
    data object Keep : MergeDecision
}

/**
 * La regola di convergenza: **last-write-wins** su `updatedAt`.
 *
 * A parità di `updatedAt` non si può guardare l'orologio per decidere, e scegliere «il remoto» o
 * «il locale» darebbe risultati opposti sui due dispositivi — che è esattamente il modo di non
 * convergere. Serve un criterio che dia lo stesso vincitore ovunque venga calcolato: si confronta
 * una firma testuale del contenuto e vince la maggiore. È arbitrario, ma è arbitrario allo stesso
 * modo sui due lati, ed entra in gioco solo per due modifiche diverse nello stesso millisecondo.
 *
 * Un tombstone non è un caso a parte: è un evento come gli altri, con `deleted` a `true`. È per
 * questo che una cancellazione non risorge — la riga rimane e continua a vincere sui dati più
 * vecchi, invece di sparire e lasciare che l'altro lato la reintroduca.
 *
 * **Sul clock skew.** Se un dispositivo ha l'orologio avanti, le sue modifiche vincono anche
 * quando sono anteriori. Con due dispositivi il danno è limitato a una modifica concorrente allo
 * stesso evento, un caso di bordo raro; risolverlo davvero richiederebbe orologi vettoriali, cioè
 * una struttura che questa applicazione non giustifica.
 */
fun resolveMerge(local: EventEntity?, remote: EventEntity): MergeDecision = when {
    local == null -> MergeDecision.Insert(remote.copy(id = 0))
    remote.updatedAt > local.updatedAt -> MergeDecision.Update(remote.copy(id = local.id))
    remote.updatedAt < local.updatedAt -> MergeDecision.Keep
    signature(remote) > signature(local) -> MergeDecision.Update(remote.copy(id = local.id))
    else -> MergeDecision.Keep
}

/** Tutto ciò che distingue due versioni dello stesso evento, in una forma confrontabile. */
private fun signature(event: EventEntity): String = listOf(
    if (event.deleted) "1" else "0",
    event.deletedAt?.toString().orEmpty(),
    if (event.completed) "1" else "0",
    event.dateTimeMillis.toString(),
    event.advanceMinutes.toString(),
    event.title,
    event.description.orEmpty(),
    event.origin
).joinToString(" ")

fun EventEntity.toSyncEvent(): SyncEvent = SyncEvent(
    uuid = uuid,
    title = title,
    description = description,
    dateTimeMillis = dateTimeMillis,
    advanceMinutes = advanceMinutes,
    completed = completed,
    updatedAt = updatedAt,
    deleted = deleted,
    deletedAt = deletedAt,
    origin = origin
)

/** L'id resta a zero: quello locale lo assegna il merge, non il mittente. */
fun SyncEvent.toEntity(): EventEntity = EventEntity(
    id = 0,
    title = title,
    description = description,
    dateTimeMillis = dateTimeMillis,
    advanceMinutes = advanceMinutes,
    completed = completed,
    uuid = uuid,
    updatedAt = updatedAt,
    deleted = deleted,
    deletedAt = deletedAt,
    origin = origin
)

package it.agoldoni.reminder.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** DAO in memoria per i test: stessa semantica delle query di [EventDao]. */
class FakeEventDao(initial: List<EventEntity> = emptyList()) : EventDao {

    private val state = MutableStateFlow(initial)
    private var nextId: Long = (initial.maxOfOrNull { it.id } ?: 0L) + 1

    val events: List<EventEntity> get() = state.value

    override fun getActiveSortedAsc(): Flow<List<EventEntity>> =
        state.map { list -> list.visible().filterNot { it.completed }.sortedBy { it.dateTimeMillis } }

    override fun getCompletedSortedDesc(): Flow<List<EventEntity>> =
        state.map { list -> list.visible().filter { it.completed }.sortedByDescending { it.dateTimeMillis } }

    override suspend fun getById(id: Long): EventEntity? =
        state.value.visible().firstOrNull { it.id == id }

    override suspend fun getByUuid(uuid: String): EventEntity? =
        state.value.firstOrNull { it.uuid == uuid }

    override suspend fun insert(event: EventEntity): Long {
        val id = if (event.id == 0L) nextId++ else event.id
        state.value = state.value + event.copy(id = id)
        return id
    }

    override suspend fun update(event: EventEntity) {
        state.value = state.value.map { if (it.id == event.id) event else it }
    }

    override suspend fun softDelete(id: Long, nowMillis: Long) {
        edit(id) { it.copy(deleted = true, deletedAt = nowMillis, updatedAt = nowMillis) }
    }

    override suspend fun markCompleted(id: Long, nowMillis: Long) =
        edit(id) { it.copy(completed = true, updatedAt = nowMillis) }

    override suspend fun markActive(id: Long, nowMillis: Long) =
        edit(id) { it.copy(completed = false, updatedAt = nowMillis) }

    override suspend fun getFutureEvents(nowMillis: Long): List<EventEntity> =
        state.value.visible().filter { !it.completed && it.notificationMillis > nowMillis }

    override suspend fun getOverdueEvents(nowMillis: Long): List<EventEntity> =
        state.value.visible().filter { !it.completed && it.notificationMillis <= nowMillis }
            .sortedBy { it.dateTimeMillis }

    override suspend fun getAll(): List<EventEntity> =
        state.value.visible().sortedBy { it.dateTimeMillis }

    override suspend fun getAllOpen(): List<EventEntity> =
        state.value.visible().filterNot { it.completed }.sortedBy { it.dateTimeMillis }

    override suspend fun changedSince(sinceMillis: Long): List<EventEntity> =
        state.value.filter { it.updatedAt > sinceMillis }.sortedBy { it.updatedAt }

    private fun edit(id: Long, transform: (EventEntity) -> EventEntity) {
        state.value = state.value.map { if (it.id == id) transform(it) else it }
    }

    /** Le letture dell'app non vedono i tombstone, esattamente come il filtro `deleted = 0`. */
    private fun List<EventEntity>.visible(): List<EventEntity> = filterNot { it.deleted }

    private val EventEntity.notificationMillis: Long
        get() = dateTimeMillis - advanceMinutes * 60_000L
}

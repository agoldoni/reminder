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
        state.map { list -> list.filterNot { it.completed }.sortedBy { it.dateTimeMillis } }

    override fun getCompletedSortedDesc(): Flow<List<EventEntity>> =
        state.map { list -> list.filter { it.completed }.sortedByDescending { it.dateTimeMillis } }

    override suspend fun getById(id: Long): EventEntity? = state.value.firstOrNull { it.id == id }

    override suspend fun insert(event: EventEntity): Long {
        val id = if (event.id == 0L) nextId++ else event.id
        state.value = state.value + event.copy(id = id)
        return id
    }

    override suspend fun update(event: EventEntity) {
        state.value = state.value.map { if (it.id == event.id) event else it }
    }

    override suspend fun delete(event: EventEntity) {
        state.value = state.value.filterNot { it.id == event.id }
    }

    override suspend fun markCompleted(id: Long) = setCompleted(id, completed = true)

    override suspend fun markActive(id: Long) = setCompleted(id, completed = false)

    override suspend fun getFutureEvents(nowMillis: Long): List<EventEntity> =
        state.value.filter { !it.completed && it.notificationMillis > nowMillis }

    override suspend fun getOverdueEvents(nowMillis: Long): List<EventEntity> =
        state.value.filter { !it.completed && it.notificationMillis <= nowMillis }
            .sortedBy { it.dateTimeMillis }

    override suspend fun getAll(): List<EventEntity> = state.value.sortedBy { it.dateTimeMillis }

    override suspend fun getAllOpen(): List<EventEntity> =
        state.value.filterNot { it.completed }.sortedBy { it.dateTimeMillis }

    private fun setCompleted(id: Long, completed: Boolean) {
        state.value = state.value.map { if (it.id == id) it.copy(completed = completed) else it }
    }

    private val EventEntity.notificationMillis: Long
        get() = dateTimeMillis - advanceMinutes * 60_000L
}

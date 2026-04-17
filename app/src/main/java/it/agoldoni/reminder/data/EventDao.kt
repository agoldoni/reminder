package it.agoldoni.reminder.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Query("SELECT * FROM events WHERE completed = 0 ORDER BY dateTimeMillis DESC")
    fun getActiveSortedDesc(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE completed = 1 ORDER BY dateTimeMillis DESC")
    fun getCompletedSortedDesc(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getById(id: Long): EventEntity?

    @Insert
    suspend fun insert(event: EventEntity): Long

    @Update
    suspend fun update(event: EventEntity)

    @Delete
    suspend fun delete(event: EventEntity)

    @Query("UPDATE events SET completed = 1 WHERE id = :id")
    suspend fun markCompleted(id: Long)

    @Query("UPDATE events SET completed = 0 WHERE id = :id")
    suspend fun markActive(id: Long)

    @Query("SELECT * FROM events WHERE completed = 0 AND dateTimeMillis - advanceMinutes * 60000 > :nowMillis")
    suspend fun getFutureEvents(nowMillis: Long): List<EventEntity>
}

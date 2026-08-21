package it.agoldoni.reminder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Dallo schema v3 la cancellazione è logica: le letture dell'app escludono i tombstone
 * (`deleted = 0`), mentre la sincronizzazione li vede tramite [changedSince] e [getByUuid].
 * Ogni scrittura riceve l'istante da registrare in `updatedAt`: il clock è del chiamante,
 * così i test possono lavorare a tempo virtuale.
 */
@Dao
interface EventDao {
    @Query("SELECT * FROM events WHERE deleted = 0 AND completed = 0 ORDER BY dateTimeMillis ASC")
    fun getActiveSortedAsc(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE deleted = 0 AND completed = 1 ORDER BY dateTimeMillis DESC")
    fun getCompletedSortedDesc(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE deleted = 0 AND id = :id")
    suspend fun getById(id: Long): EventEntity?

    /** Include i tombstone: la sincronizzazione deve poter riconoscere ciò che è stato cancellato. */
    @Query("SELECT * FROM events WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): EventEntity?

    @Insert
    suspend fun insert(event: EventEntity): Long

    @Update
    suspend fun update(event: EventEntity)

    /** Cancellazione logica: la riga resta come tombstone finché non è stata propagata ai peer. */
    @Query("UPDATE events SET deleted = 1, deletedAt = :nowMillis, updatedAt = :nowMillis WHERE id = :id")
    suspend fun softDelete(id: Long, nowMillis: Long)

    @Query("UPDATE events SET completed = 1, updatedAt = :nowMillis WHERE id = :id")
    suspend fun markCompleted(id: Long, nowMillis: Long)

    @Query("UPDATE events SET completed = 0, updatedAt = :nowMillis WHERE id = :id")
    suspend fun markActive(id: Long, nowMillis: Long)

    @Query("SELECT * FROM events WHERE deleted = 0 AND completed = 0 AND dateTimeMillis - advanceMinutes * 60000 > :nowMillis")
    suspend fun getFutureEvents(nowMillis: Long): List<EventEntity>

    @Query("SELECT * FROM events WHERE deleted = 0 AND completed = 0 AND dateTimeMillis - advanceMinutes * 60000 <= :nowMillis ORDER BY dateTimeMillis ASC")
    suspend fun getOverdueEvents(nowMillis: Long): List<EventEntity>

    @Query("SELECT * FROM events WHERE deleted = 0 ORDER BY dateTimeMillis ASC")
    suspend fun getAll(): List<EventEntity>

    @Query("SELECT * FROM events WHERE deleted = 0 AND completed = 0 ORDER BY dateTimeMillis ASC")
    suspend fun getAllOpen(): List<EventEntity>

    /**
     * Tutto ciò che è cambiato **da** [sinceMillis] in poi, tombstone compresi: è la sorgente del
     * `PUSH`. Il confronto include l'estremo di proposito: il watermark è il `updatedAt` più
     * recente già ricevuto, e nello stesso millisecondo può essercene un altro che al momento
     * dello scambio precedente non era ancora stato scritto. Escluderlo lo perderebbe per sempre;
     * rispedire ogni volta l'ultimo millisecondo costa un evento e il merge lo scarta da sé.
     */
    @Query("SELECT * FROM events WHERE updatedAt >= :sinceMillis ORDER BY updatedAt ASC")
    suspend fun changedSince(sinceMillis: Long): List<EventEntity>
}

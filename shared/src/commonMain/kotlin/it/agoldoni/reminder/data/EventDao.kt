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

    /**
     * Aggiornamento **condizionato**: scrive solo se la riga è ancora quella che il chiamante
     * credeva di modificare. Restituisce il numero di righe toccate — `1` fatto, `0` conflitto.
     *
     * Serve alle scritture che arrivano dalla web app, dove fra la lettura e il salvataggio può
     * passare mezzo minuto (il browser interroga ogni trenta secondi) e nel frattempo la stessa
     * riga può essere cambiata dal telefono. Leggere, confrontare e scrivere in tre passi non
     * basterebbe: ogni connessione è servita da una coroutine sua, quindi due richieste possono
     * superare entrambe il confronto e sovrascriversi. Qui la domanda è una sola, e in SQLite un
     * `UPDATE … WHERE` è atomico: il conteggio *è* l'esito del confronto.
     *
     * **Nella `SET` non ci sono `uuid` e `origin`**, e non è una dimenticanza: sono l'identità
     * dell'evento fra dispositivi, e questa è la strada per cui un JSON che *sembra* già un evento
     * potrebbe farli rigenerare. Non potendoli nominare, non si possono perdere.
     */
    @Query(
        """
        UPDATE events SET title = :title, description = :description,
            dateTimeMillis = :dateTimeMillis, advanceMinutes = :advanceMinutes,
            completed = :completed, updatedAt = :nowMillis
        WHERE id = :id AND deleted = 0 AND updatedAt = :attesoUpdatedAt
        """
    )
    suspend fun updateIfUnchanged(
        id: Long,
        title: String,
        description: String?,
        dateTimeMillis: Long,
        advanceMinutes: Int,
        completed: Boolean,
        attesoUpdatedAt: Long,
        nowMillis: Long
    ): Int

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

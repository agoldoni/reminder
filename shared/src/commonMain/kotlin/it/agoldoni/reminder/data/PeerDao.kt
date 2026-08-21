package it.agoldoni.reminder.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY displayName ASC")
    fun getAll(): Flow<List<PeerEntity>>

    /** Lettura una tantum: un giro di sincronizzazione non ha bisogno di restare in ascolto. */
    @Query("SELECT * FROM peers ORDER BY displayName ASC")
    suspend fun list(): List<PeerEntity>

    @Query("SELECT * FROM peers WHERE deviceId = :deviceId")
    suspend fun getById(deviceId: String): PeerEntity?

    /** Ri-associare un dispositivo già noto ne sostituisce il segreto invece di duplicarlo. */
    @Upsert
    suspend fun upsert(peer: PeerEntity)

    /** Dissociazione: senza la riga il peer torna sconosciuto e viene rifiutato. */
    @Query("DELETE FROM peers WHERE deviceId = :deviceId")
    suspend fun delete(deviceId: String)

    @Query("UPDATE peers SET lastHost = :host, lastPort = :port WHERE deviceId = :deviceId")
    suspend fun rememberAddress(deviceId: String, host: String, port: Int)

    /**
     * [watermark] è nel tempo del peer e serve al protocollo; [contactedAt] è l'ora locale ed è
     * l'unica che abbia senso mostrare. Si scrivono insieme perché descrivono lo stesso evento.
     */
    @Query("UPDATE peers SET lastSyncAt = :watermark, lastContactAt = :contactedAt WHERE deviceId = :deviceId")
    suspend fun rememberSync(deviceId: String, watermark: Long, contactedAt: Long)
}

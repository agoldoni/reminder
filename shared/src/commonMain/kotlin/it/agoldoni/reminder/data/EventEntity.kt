package it.agoldoni.reminder.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import it.agoldoni.reminder.platform.newUuid
import it.agoldoni.reminder.platform.nowMillis

@Entity(
    tableName = "events",
    indices = [Index(value = ["uuid"], unique = true)]
)
data class EventEntity(
    /**
     * Id locale. Resta la chiave primaria anche dopo l'arrivo di [uuid]: è il requestCode dei
     * `PendingIntent` su Android e l'id delle notifiche, e cambiarlo romperebbe gli allarmi.
     */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String? = null,
    val dateTimeMillis: Long,
    val advanceMinutes: Int = 0,
    val completed: Boolean = false,
    /** Identità globale: è su questa, non su [id], che due dispositivi riconoscono lo stesso evento. */
    val uuid: String = newUuid(),
    /** Ultima modifica: è il criterio con cui il merge risolve i conflitti (last-write-wins). */
    val updatedAt: Long = nowMillis(),
    /** Tombstone: la riga sopravvive alla cancellazione per poterla propagare ai peer. */
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
    /** Dispositivo su cui l'evento è nato; non cambia con le modifiche successive. */
    val origin: String = ""
)

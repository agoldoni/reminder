package it.agoldoni.reminder.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Un dispositivo associato. La chiave è il `deviceId` dell'altro, non un id locale: è l'identità
 * che l'altro dichiara nell'`HELLO` e l'unica cosa che resta stabile mentre l'indirizzo IP cambia.
 *
 * [sharedSecret] è il segreto stabilito durante il pairing, in esadecimale. Sta in chiaro nel
 * database: è protetto dai permessi del file (sandbox dell'app su Android, cartella dell'utente su
 * desktop) e non da una cifratura a riposo, che richiederebbe un portachiavi di piattaforma
 * diverso per ogni sistema.
 */
@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val sharedSecret: String,
    /** Ultimo indirizzo a cui il peer ha risposto: il primo tentativo riparte da lì. */
    val lastHost: String? = null,
    val lastPort: Int? = null,
    val pairedAt: Long,
    /**
     * Watermark della replica: fin dove si era arrivati con questo peer, **nel tempo del peer**.
     * Non è una data da mostrare — con orologi diversi indica un istante che qui non è mai
     * esistito. Il nome della colonna resta `lastSyncAt` perché rinominarla richiederebbe di
     * ricreare la tabella: SQLite sa fare `RENAME COLUMN` solo dalla 3.25, e l'API 26 si ferma
     * alla 3.18.
     */
    @ColumnInfo(name = "lastSyncAt") val watermark: Long = 0,
    /**
     * Quando questo dispositivo ha sincronizzato con successo l'ultima volta, **in ora locale**.
     * È il valore da mostrare all'utente: [watermark] appartiene all'orologio dell'altro.
     */
    val lastContactAt: Long = 0
)

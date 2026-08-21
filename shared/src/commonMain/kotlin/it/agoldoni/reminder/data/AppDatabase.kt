package it.agoldoni.reminder.data

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(entities = [EventEntity::class, PeerEntity::class], version = 4, exportSchema = true)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun peerDao(): PeerDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE events ADD COLUMN completed INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v2 → v3: identità globale, timestamp di modifica e tombstone, cioè tutto ciò che serve
         * alla sincronizzazione. Gli eventi già presenti sono per definizione nati su questo
         * dispositivo, quindi [deviceId] finisce nel loro `origin`.
         *
         * L'indice unico su `uuid` si crea **dopo** il popolamento: appena aggiunta, la colonna
         * vale `''` su tutte le righe e l'indice le rifiuterebbe come duplicate.
         *
         * La migrazione non è reversibile: una release precedente non apre un database v3.
         */
        fun migration2to3(deviceId: String) = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE events ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
                connection.execSQL("ALTER TABLE events ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE events ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE events ADD COLUMN deletedAt INTEGER")
                connection.execSQL("ALTER TABLE events ADD COLUMN origin TEXT NOT NULL DEFAULT ''")

                // randomblob() non è deterministica: viene rivalutata riga per riga, quindi ogni
                // evento riceve un uuid distinto nella forma canonica di java.util.UUID.
                connection.execSQL(
                    """
                    UPDATE events SET uuid = lower(
                        substr(hex(randomblob(4)), 1, 8) || '-' ||
                        substr(hex(randomblob(2)), 1, 4) || '-4' ||
                        substr(hex(randomblob(2)), 2, 3) || '-' ||
                        substr('89ab', (random() & 3) + 1, 1) ||
                        substr(hex(randomblob(2)), 2, 3) || '-' ||
                        substr(hex(randomblob(6)), 1, 12)
                    )
                    """.trimIndent()
                )
                // Nessuno storico delle modifiche da recuperare: la data dell'evento è la stima
                // meno arbitraria del momento in cui è stato scritto.
                connection.execSQL("UPDATE events SET updatedAt = dateTimeMillis")
                connection.execSQL("UPDATE events SET origin = '${deviceId.replace("'", "''")}'")

                connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_events_uuid ON events (uuid)")
            }
        }

        /**
         * v3 → v4: la tabella dei dispositivi associati. Puramente additiva — gli eventi non si
         * toccano — e a database nuovo resta vuota: senza associazioni la sincronizzazione non
         * parte e l'app si comporta come prima.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `peers` (" +
                        "`deviceId` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`sharedSecret` TEXT NOT NULL, " +
                        "`lastHost` TEXT, " +
                        "`lastPort` INTEGER, " +
                        "`pairedAt` INTEGER NOT NULL, " +
                        "`lastSyncAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`deviceId`))"
                )
            }
        }
    }
}

/** Generato da Room per ciascuna piattaforma. */
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

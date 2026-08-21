package it.agoldoni.reminder.data

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(entities = [EventEntity::class], version = 3, exportSchema = true)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

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
    }
}

/** Generato da Room per ciascuna piattaforma. */
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

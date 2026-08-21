package it.agoldoni.reminder.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import it.agoldoni.reminder.platform.createAppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TC-06 — migrazioni di schema su SQLite vero. Gira sulla JVM desktop invece che su emulatore:
 * il driver bundled e quello di sistema eseguono lo stesso SQL, e i test strumentati richiedono
 * un emulatore che qui non è sempre disponibile.
 *
 * Aprire il database con [createAppDatabase] non verifica solo i dati: Room confronta lo schema
 * risultante con quello atteso e fallisce se la migrazione non lo ha ricostruito esattamente.
 */
class SchemaMigrationTest {

    private lateinit var directory: File
    private lateinit var dbFile: File

    private val deviceId = "dispositivo-di-prova"

    @BeforeTest
    fun setUp() {
        directory = File.createTempFile("promemoria-migrazione", "").let { temp ->
            temp.delete()
            temp.apply { mkdirs() }
        }
        dbFile = File(directory, "reminder.db")
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    /** Database allo schema v2, com'era prima della sincronizzazione. */
    private fun createV2Database(vararg rows: String) {
        BundledSQLiteDriver().open(dbFile.absolutePath).use { connection ->
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `events` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`description` TEXT, " +
                    "`dateTimeMillis` INTEGER NOT NULL, " +
                    "`advanceMinutes` INTEGER NOT NULL, " +
                    "`completed` INTEGER NOT NULL)"
            )
            rows.forEach(connection::execSQL)
            connection.execSQL("PRAGMA user_version = 2")
        }
    }

    @Test
    fun `gli eventi v2 sopravvivono alla migrazione`() = runTest {
        createV2Database(
            "INSERT INTO events VALUES (1, 'Dentista', 'Studio in centro', 1800000000000, 30, 0)",
            "INSERT INTO events VALUES (2, 'Bollette', NULL, 1700000000000, 0, 1)"
        )

        val database = createAppDatabase(dbFile, deviceId)
        try {
            val dao = database.eventDao()
            val eventi = dao.getAll()
            assertEquals(2, eventi.size, "nessun evento deve andare perso")

            val dentista = eventi.first { it.id == 1L }
            assertEquals("Dentista", dentista.title)
            assertEquals("Studio in centro", dentista.description)
            assertEquals(1800000000000L, dentista.dateTimeMillis)
            assertEquals(30, dentista.advanceMinutes)
            assertEquals(false, dentista.completed)

            val bollette = eventi.first { it.id == 2L }
            assertNull(bollette.description)
            assertTrue(bollette.completed, "lo stato di completamento non deve cambiare")
        } finally {
            database.close()
        }
    }

    @Test
    fun `la migrazione popola le colonne della sincronizzazione`() = runTest {
        createV2Database(
            "INSERT INTO events VALUES (1, 'Dentista', NULL, 1800000000000, 30, 0)",
            "INSERT INTO events VALUES (2, 'Bollette', NULL, 1700000000000, 0, 1)"
        )

        val database = createAppDatabase(dbFile, deviceId)
        try {
            val eventi = database.eventDao().getAll()

            val uuidCanonico = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
            eventi.forEach { evento ->
                assertTrue(
                    uuidCanonico.matches(evento.uuid),
                    "uuid non nella forma canonica: ${evento.uuid}"
                )
                // Nessuno storico da recuperare: la data dell'evento è il valore iniziale scelto.
                assertEquals(evento.dateTimeMillis, evento.updatedAt)
                assertEquals(deviceId, evento.origin, "le righe preesistenti sono nate qui")
                assertEquals(false, evento.deleted)
                assertNull(evento.deletedAt)
            }

            assertEquals(
                eventi.size,
                eventi.map { it.uuid }.toSet().size,
                "ogni evento deve ricevere un uuid diverso"
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `l'indice unico su uuid è attivo dopo la migrazione`() = runTest {
        createV2Database("INSERT INTO events VALUES (1, 'Dentista', NULL, 1800000000000, 30, 0)")

        val database = createAppDatabase(dbFile, deviceId)
        try {
            val dao = database.eventDao()
            val esistente = assertNotNull(dao.getById(1L))

            assertFailsWith<Exception>("un uuid duplicato deve essere rifiutato") {
                dao.insert(
                    EventEntity(
                        title = "Copia",
                        dateTimeMillis = 1800000000000L,
                        uuid = esistente.uuid
                    )
                )
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `la migrazione 3 a 4 aggiunge i dispositivi associati senza toccare gli eventi`() = runTest {
        createV2Database("INSERT INTO events VALUES (1, 'Dentista', NULL, 1800000000000, 30, 0)")

        val database = createAppDatabase(dbFile, deviceId)
        try {
            val dao = database.eventDao()
            assertEquals(1, dao.getAll().size, "la tabella nuova non tocca gli eventi")

            // Il database parte senza associazioni: finché la tabella è vuota la
            // sincronizzazione non ha nessuno con cui parlare e l'app si comporta come prima.
            val peers = database.peerDao()
            assertTrue(peers.getAll().first().isEmpty())

            peers.upsert(
                PeerEntity(
                    deviceId = "id-computer",
                    displayName = "Computer",
                    sharedSecret = "ab".repeat(32),
                    pairedAt = 1_800_000_000_000L
                )
            )
            val salvato = assertNotNull(peers.getById("id-computer"))
            assertEquals("Computer", salvato.displayName)
            assertEquals(0L, salvato.watermark)
            assertEquals(0L, salvato.lastContactAt)
            assertNull(salvato.lastHost)
        } finally {
            database.close()
        }
    }

    /**
     * Prima della v5 la schermata mostrava `lastSyncAt` come data dell'ultimo allineamento, ma
     * quello è il watermark del protocollo e vive nell'orologio dell'**altro** dispositivo: con
     * orologi diversi si annunciava un istante mai esistito qui.
     */
    @Test
    fun `dalla v5 il momento del contatto è distinto dal watermark`() = runTest {
        createV2Database()

        val database = createAppDatabase(dbFile, deviceId)
        try {
            val peers = database.peerDao()
            peers.upsert(
                PeerEntity(
                    deviceId = "id-computer",
                    displayName = "Computer",
                    sharedSecret = "ab".repeat(32),
                    pairedAt = 1_800_000_000_000L
                )
            )

            // Il peer ha l'orologio avanti di un'ora: il watermark lo riflette, il contatto no.
            peers.rememberSync("id-computer", watermark = 1_803_600_000_000L, contactedAt = 1_800_000_500_000L)

            val salvato = assertNotNull(peers.getById("id-computer"))
            assertEquals(1_803_600_000_000L, salvato.watermark, "il watermark resta quello del peer")
            assertEquals(
                1_800_000_500_000L,
                salvato.lastContactAt,
                "ciò che si mostra all'utente è l'ora locale, non quella dell'altro"
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `dissociare elimina le credenziali`() = runTest {
        createV2Database()

        val database = createAppDatabase(dbFile, deviceId)
        try {
            val peers = database.peerDao()
            peers.upsert(
                PeerEntity(
                    deviceId = "id-computer",
                    displayName = "Computer",
                    sharedSecret = "ab".repeat(32),
                    pairedAt = 1_800_000_000_000L
                )
            )
            peers.rememberAddress("id-computer", "192.168.1.10", 8765)

            peers.delete("id-computer")

            // Senza la riga il peer torna sconosciuto: è ciò che fa rifiutare le sessioni
            // successive, e il segreto non resta da nessuna parte.
            assertNull(peers.getById("id-computer"))
            assertTrue(peers.getAll().first().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `la cancellazione lascia un tombstone invisibile all'app ma leggibile dalla sync`() = runTest {
        createV2Database("INSERT INTO events VALUES (1, 'Dentista', NULL, 1800000000000, 30, 0)")

        val database = createAppDatabase(dbFile, deviceId)
        try {
            val dao = database.eventDao()
            val evento = assertNotNull(dao.getById(1L))

            dao.softDelete(evento.id, nowMillis = 1_900_000_000_000L)

            assertTrue(dao.getAll().isEmpty(), "l'app non deve più vedere l'evento")
            assertNull(dao.getById(evento.id))

            val tombstone = assertNotNull(
                dao.getByUuid(evento.uuid),
                "la sincronizzazione deve poter propagare la cancellazione"
            )
            assertTrue(tombstone.deleted)
            assertEquals(1_900_000_000_000L, tombstone.deletedAt)
            assertEquals(1_900_000_000_000L, tombstone.updatedAt)

            assertEquals(
                listOf(evento.uuid),
                dao.changedSince(evento.updatedAt).map { it.uuid },
                "changedSince deve includere i tombstone"
            )
        } finally {
            database.close()
        }
    }
}

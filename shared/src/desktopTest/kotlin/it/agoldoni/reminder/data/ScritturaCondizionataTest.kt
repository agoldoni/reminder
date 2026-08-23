package it.agoldoni.reminder.data

import it.agoldoni.reminder.platform.createAppDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TC-12 e la verifica bloccante T-01 — l'aggiornamento condizionato su SQLite vero.
 *
 * La domanda a cui questo file risponde è una sola e va risposta **prima** di scriverci sopra le
 * scritture della web app: Room restituisce davvero il numero di righe toccate da un
 * `@Query("UPDATE …")`, o restituisce qualcos'altro? Se non lo facesse, il controllo ottimistico
 * andrebbe costruito in un altro modo e cambierebbero le firme di tutto ciò che viene dopo.
 *
 * Gira sulla JVM desktop e non su emulatore per la stessa ragione di [SchemaMigrationTest]: il
 * conteggio viene da `changes()` di SQLite, che il driver bundled e quello di sistema eseguono
 * identico, e il codice che lo raccoglie è quello generato da Room in `commonMain` — lo stesso
 * sui due target.
 */
class ScritturaCondizionataTest {

    private lateinit var directory: File
    private lateinit var database: AppDatabase
    private lateinit var dao: EventDao

    @BeforeTest
    fun setUp() {
        directory = File.createTempFile("promemoria-scrittura", "").let { temp ->
            temp.delete()
            temp.apply { mkdirs() }
        }
        database = createAppDatabase(File(directory, "reminder.db"), "dispositivo-di-prova")
        dao = database.eventDao()
    }

    @AfterTest
    fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    private suspend fun inserisci(
        titolo: String = "Dentista",
        quando: Long = 1_800_000_000_000L,
        aggiornato: Long = 1_000L,
        completato: Boolean = false
    ): EventEntity {
        val id = dao.insert(
            EventEntity(
                title = titolo,
                description = "Studio in centro",
                dateTimeMillis = quando,
                advanceMinutes = 15,
                completed = completato,
                updatedAt = aggiornato,
                origin = "dispositivo-di-prova"
            )
        )
        return assertNotNull(dao.getById(id))
    }

    private suspend fun aggiorna(
        riga: EventEntity,
        titolo: String = riga.title,
        descrizione: String? = riga.description,
        quando: Long = riga.dateTimeMillis,
        anticipo: Int = riga.advanceMinutes,
        completato: Boolean = riga.completed,
        atteso: Long = riga.updatedAt,
        adesso: Long = 2_000L
    ): Int = dao.updateIfUnchanged(
        id = riga.id,
        title = titolo,
        description = descrizione,
        dateTimeMillis = quando,
        advanceMinutes = anticipo,
        completed = completato,
        attesoUpdatedAt = atteso,
        nowMillis = adesso
    )

    // --- La verifica bloccante ---------------------------------------------------------------

    @Test
    fun `la riga ferma dove il chiamante l'aveva letta si aggiorna, e il conteggio dice uno`() =
        runTest {
            val riga = inserisci()

            assertEquals(1, aggiorna(riga, titolo = "Dentista — chiedere la ricevuta"))

            val dopo = assertNotNull(dao.getById(riga.id))
            assertEquals("Dentista — chiedere la ricevuta", dopo.title)
            assertEquals(2_000L, dopo.updatedAt, "updatedAt deve avanzare all'istante passato")
        }

    @Test
    fun `una riga gia' andata avanti non si tocca, e il conteggio dice zero`() = runTest {
        val riga = inserisci(aggiornato = 1_000L)

        // Il telefono la modifica mentre la scheda del browser è ferma su quel che aveva letto.
        dao.markCompleted(riga.id, 1_500L)

        assertEquals(0, aggiorna(riga, titolo = "titolo dal browser", atteso = 1_000L))

        val dopo = assertNotNull(dao.getById(riga.id))
        assertEquals("Dentista", dopo.title, "il conflitto non deve aver scritto niente")
        assertEquals(1_500L, dopo.updatedAt)
        assertTrue(dopo.completed, "la modifica del telefono deve essere sopravvissuta")
    }

    /**
     * Il caso che rende inutile un `Mutex` nel livello web: **la stessa** chiamata due volte, come
     * farebbero due schede del browser ferme sullo stesso valore. La seconda non deve passare.
     */
    @Test
    fun `la stessa scrittura ripetuta passa una volta sola`() = runTest {
        val riga = inserisci()

        assertEquals(1, aggiorna(riga, titolo = "primo", adesso = 2_000L))
        assertEquals(0, aggiorna(riga, titolo = "secondo", adesso = 3_000L))

        assertEquals("primo", assertNotNull(dao.getById(riga.id)).title)
    }

    /**
     * **Questo non prova l'atomicità, e non deve far credere di provarla.** Le due coroutine
     * possono benissimo essere eseguite una dopo l'altra, nel qual caso il test passerebbe anche
     * senza alcuna garanzia. La prova vera è quella qui sopra, che è deterministica; questo resta
     * perché costa niente e perché fallirebbe se Room aprisse due connessioni indipendenti.
     */
    @Test
    fun `due scritture simultanee sulla stessa riga ne fanno passare una sola`() = runTest {
        val riga = inserisci()

        val esiti = listOf("uno", "due")
            .map { titolo -> async { aggiorna(riga, titolo = titolo) } }
            .awaitAll()

        assertEquals(
            listOf(0, 1),
            esiti.sorted(),
            "una sola delle due deve aver toccato la riga: esiti $esiti"
        )
    }

    // --- Che cosa la `WHERE` esclude ------------------------------------------------------------

    @Test
    fun `un tombstone non si aggiorna`() = runTest {
        val riga = inserisci(aggiornato = 1_000L)
        dao.softDelete(riga.id, 1_200L)

        // Anche dichiarando l'updatedAt giusto: una riga cancellata non è più modificabile.
        assertEquals(0, aggiorna(riga, titolo = "risorto", atteso = 1_200L))
    }

    @Test
    fun `un id inesistente non tocca niente`() = runTest {
        val riga = inserisci()
        assertEquals(0, aggiorna(riga.copy(id = riga.id + 999)))
    }

    // --- L'identità fra dispositivi -------------------------------------------------------------

    @Test
    fun `uuid e origin sopravvivono all'aggiornamento`() = runTest {
        val riga = inserisci()

        assertEquals(1, aggiorna(riga, titolo = "altro titolo", quando = 1_900_000_000_000L))

        val dopo = assertNotNull(dao.getById(riga.id))
        assertEquals(riga.uuid, dopo.uuid, "l'identità vista dagli altri dispositivi non cambia")
        assertEquals(riga.origin, dopo.origin, "il dispositivo di nascita non cambia")
        assertEquals(false, dopo.deleted)
    }

    @Test
    fun `completare e riaprire passano dalla stessa strada`() = runTest {
        val riga = inserisci(aggiornato = 1_000L)

        assertEquals(1, aggiorna(riga, completato = true, atteso = 1_000L, adesso = 2_000L))
        val completata = assertNotNull(dao.getById(riga.id))
        assertTrue(completata.completed)
        assertEquals("Dentista", completata.title, "completare non tocca gli altri campi")

        assertEquals(1, aggiorna(completata, completato = false, adesso = 3_000L))
        assertEquals(false, assertNotNull(dao.getById(riga.id)).completed)
    }

    @Test
    fun `una descrizione svuotata diventa nulla sul serio`() = runTest {
        val riga = inserisci()

        assertEquals(1, aggiorna(riga, descrizione = null))

        assertEquals(null, assertNotNull(dao.getById(riga.id)).description)
    }
}

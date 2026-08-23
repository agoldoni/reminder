package it.agoldoni.reminder.web

import it.agoldoni.reminder.data.EventEntity
import it.agoldoni.reminder.data.FakeEventDao
import it.agoldoni.reminder.sync.RecordingAlarmScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TC-06/07/08/09/10 — il cuore della feature, **senza rete**.
 *
 * `ScrittureWeb` riceve un testo e restituisce un esito: gli allarmi si verificano con un finto
 * programmatore che registra le chiamate, come si fa per `SyncEngine`. Mettere in mezzo un socket
 * aggiungerebbe solo modi di fallire che non hanno a che vedere con ciò che si sta provando.
 */
class ScrittureWebTest {

    private val dao = FakeEventDao()
    private val alarms = RecordingAlarmScheduler()
    private var orologio = 5_000L
    private val scritture = ScrittureWeb(dao, alarms, "questo-telefono") { orologio }

    private fun creazione(
        titolo: String = "Dentista",
        descrizione: String? = "Portare la tessera",
        quando: Long = 1_800_000_000_000L,
        anticipo: Int = 15
    ) = """{"titolo":${virgolette(titolo)},"descrizione":${virgolette(descrizione)},""" +
        """"dateTimeMillis":$quando,"advanceMinutes":$anticipo}"""

    private fun modificaJson(
        titolo: String = "Dentista",
        descrizione: String? = null,
        quando: Long = 1_800_000_000_000L,
        anticipo: Int = 15,
        completato: Boolean = false,
        atteso: Long? = 1_000L
    ) = buildString {
        append("""{"titolo":${virgolette(titolo)},"descrizione":${virgolette(descrizione)},""")
        append(""""dateTimeMillis":$quando,"advanceMinutes":$anticipo,"completato":$completato""")
        atteso?.let { append(""","attesoUpdatedAt":$it""") }
        append("}")
    }

    private fun virgolette(v: String?) =
        v?.let { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" } ?: "null"

    private suspend fun esistente(
        titolo: String = "Dentista",
        quando: Long = 1_800_000_000_000L,
        anticipo: Int = 15,
        completato: Boolean = false,
        aggiornato: Long = 1_000L
    ): EventEntity {
        val id = dao.insert(
            EventEntity(
                title = titolo,
                description = "note",
                dateTimeMillis = quando,
                advanceMinutes = anticipo,
                completed = completato,
                updatedAt = aggiornato,
                uuid = "uuid-stabile",
                origin = "altro-dispositivo"
            )
        )
        return assertNotNull(dao.getById(id))
    }

    private fun riga() = dao.events.first()

    // --- Creazione ------------------------------------------------------------------------------

    @Test
    fun `creare inserisce l'evento e programma la sveglia`() = runTest {
        assertIs<EsitoScrittura.Fatta>(scritture.crea(creazione())).let {
            assertTrue(it.creato, "una creazione deve poter diventare un 201")
            assertEquals(orologio, it.updatedAt)
        }

        assertEquals(1, dao.events.size)
        assertEquals("Dentista", riga().title)
        assertEquals("Portare la tessera", riga().description)
        assertEquals(15, riga().advanceMinutes)
        assertEquals(orologio, riga().updatedAt)

        assertEquals(listOf(riga().id), alarms.scheduled.map { it.id })
    }

    @Test
    fun `un evento creato dal browser nasce con l'identita' del telefono`() = runTest {
        scritture.crea(creazione())

        // Il browser non è un dispositivo di questo sistema: l'evento nasce **qui**.
        assertEquals("questo-telefono", riga().origin)
        assertTrue(riga().uuid.isNotEmpty(), "l'uuid lo genera il valore predefinito dell'entità")
        assertEquals(false, riga().completed)
        assertEquals(false, riga().deleted)
    }

    @Test
    fun `un evento con data nel passato si crea lo stesso`() = runTest {
        // L'app lo permette e lo mostra come scaduto: la pagina non deve essere più severa.
        assertIs<EsitoScrittura.Fatta>(scritture.crea(creazione(quando = 1_000L)))
        assertEquals(1, dao.events.size)
        // Su Android `schedule` uscirà da sé senza mettere niente: la decisione è del programmatore.
        assertEquals(1, alarms.scheduled.size)
    }

    // --- Modifica -------------------------------------------------------------------------------

    @Test
    fun `modificare cambia i campi e rimette la sveglia sull'ora nuova`() = runTest {
        val prima = esistente(quando = 1_800_000_000_000L)
        orologio = 6_000L

        assertIs<EsitoScrittura.Fatta>(
            scritture.modifica(prima.id, modificaJson(titolo = "Dentista alle 16", quando = 1_900_000_000_000L))
        ).let { assertTrue(!it.creato, "una modifica non è una creazione") }

        assertEquals("Dentista alle 16", riga().title)
        assertEquals(1_900_000_000_000L, riga().dateTimeMillis)
        assertEquals(6_000L, riga().updatedAt)

        // Cancellare **e poi** riprogrammare: senza il cancel resterebbe anche la sveglia vecchia.
        assertEquals(listOf(prima.id), alarms.cancelled)
        assertEquals(listOf(1_900_000_000_000L), alarms.scheduled.map { it.dateTimeMillis })
    }

    @Test
    fun `uuid e origin sopravvivono a una modifica dal browser`() = runTest {
        val prima = esistente()
        scritture.modifica(prima.id, modificaJson(titolo = "altro"))

        assertEquals("uuid-stabile", riga().uuid, "l'identità vista dagli altri dispositivi")
        assertEquals("altro-dispositivo", riga().origin, "il dispositivo di nascita non cambia")
        // E anche l'evento passato al programmatore deve avere l'identità giusta, non una nuova.
        assertEquals("uuid-stabile", alarms.scheduled.single().uuid)
    }

    @Test
    fun `completare toglie la sveglia e non ne rimette una`() = runTest {
        val prima = esistente()

        scritture.modifica(prima.id, modificaJson(completato = true))

        assertTrue(riga().completed)
        assertEquals(listOf(prima.id), alarms.cancelled)
        assertTrue(alarms.scheduled.isEmpty(), "un promemoria fatto non deve suonare")
    }

    @Test
    fun `l'esito dice che cosa ha scritto, e serve proprio ad annullare`() = runTest {
        // Un evento completato esce dalla lista degli aperti: il suo `updatedAt` nuovo non è in
        // nessuna risposta successiva, e senza quello il browser non avrebbe che cosa dichiarare
        // nel controllo ottimistico dell'annullamento.
        val prima = esistente(aggiornato = 1_000L)
        orologio = 7_000L

        val esito = assertIs<EsitoScrittura.Fatta>(
            scritture.modifica(prima.id, modificaJson(completato = true))
        )
        assertEquals(prima.id, esito.id)
        assertEquals(7_000L, esito.updatedAt)

        // Ed è davvero ciò che serve: l'annullamento con quel valore passa.
        orologio = 8_000L
        assertIs<EsitoScrittura.Fatta>(
            scritture.modifica(prima.id, modificaJson(completato = false, atteso = esito.updatedAt))
        )
        assertEquals(false, riga().completed)
        assertEquals(1, alarms.scheduled.size, "riaprendolo la sveglia torna")
    }

    @Test
    fun `completare non tocca gli altri campi`() = runTest {
        val prima = esistente(titolo = "Dentista", quando = 1_800_000_000_000L, anticipo = 30)

        scritture.modifica(
            prima.id,
            modificaJson(titolo = "Dentista", quando = 1_800_000_000_000L, anticipo = 30, completato = true)
        )

        assertEquals("Dentista", riga().title)
        assertEquals(1_800_000_000_000L, riga().dateTimeMillis)
        assertEquals(30, riga().advanceMinutes)
    }

    @Test
    fun `annullare la completazione rimette la sveglia`() = runTest {
        val prima = esistente(completato = true, aggiornato = 1_000L)

        scritture.modifica(prima.id, modificaJson(completato = false))

        assertEquals(false, riga().completed)
        assertEquals(listOf(prima.id), alarms.cancelled)
        assertEquals(1, alarms.scheduled.size, "riaprendolo deve tornare a suonare")
    }

    // --- Conflitti ------------------------------------------------------------------------------

    @Test
    fun `un attesoUpdatedAt sorpassato e' un conflitto, e non scrive niente`() = runTest {
        val prima = esistente(titolo = "dal telefono", aggiornato = 2_000L)

        assertEquals(
            EsitoScrittura.Conflitto,
            scritture.modifica(prima.id, modificaJson(titolo = "dal browser", atteso = 1_000L))
        )

        assertEquals("dal telefono", riga().title)
        assertEquals(2_000L, riga().updatedAt)
        assertTrue(
            alarms.scheduled.isEmpty() && alarms.cancelled.isEmpty(),
            "una scrittura rifiutata non deve toccare le sveglie"
        )
    }

    @Test
    fun `un attesoUpdatedAt mancante non passa lo stesso`() = runTest {
        val prima = esistente()
        assertIs<EsitoScrittura.NonLeggibile>(
            scritture.modifica(prima.id, modificaJson(atteso = null))
        )
        assertEquals("Dentista", riga().title)
    }

    @Test
    fun `la stessa modifica ripetuta passa una volta sola`() = runTest {
        val prima = esistente(aggiornato = 1_000L)

        assertIs<EsitoScrittura.Fatta>(scritture.modifica(prima.id, modificaJson(titolo = "primo")))
        assertEquals(
            EsitoScrittura.Conflitto,
            scritture.modifica(prima.id, modificaJson(titolo = "secondo")),
            "la seconda dichiara un updatedAt che non c'è più"
        )
        assertEquals("primo", riga().title)
    }

    @Test
    fun `un id inesistente o cancellato e' assente, non un conflitto`() = runTest {
        assertEquals(EsitoScrittura.Assente, scritture.modifica(999, modificaJson()))

        val prima = esistente()
        dao.softDelete(prima.id, 3_000L)
        assertEquals(
            EsitoScrittura.Assente,
            scritture.modifica(prima.id, modificaJson(atteso = 3_000L)),
            "un tombstone non torna indietro dal browser"
        )
        assertTrue(alarms.scheduled.isEmpty())
    }

    // --- Validazione ----------------------------------------------------------------------------

    @Test
    fun `l'anticipo accetta i cinque valori dell'app e nessun altro`() = runTest {
        for (ammesso in ANTICIPI_AMMESSI) {
            assertIs<EsitoScrittura.Fatta>(
                scritture.crea(creazione(anticipo = ammesso)),
                "$ammesso è uno dei valori dell'editor dell'app"
            )
        }
        assertEquals(ANTICIPI_AMMESSI.size, dao.events.size)

        // Un valore fuori elenco farebbe **lanciare l'editor dell'app** all'apertura, perché
        // `EventEditScreen` lo cerca con `first { … }` e non con `firstOrNull`.
        for (fuori in listOf(1, 7, 10, 45, 120, -5)) {
            val esito = assertIs<EsitoScrittura.NonValida>(
                scritture.crea(creazione(anticipo = fuori)),
                "$fuori non doveva essere accettato"
            )
            assertEquals("advanceMinutes", esito.campo)
        }
        assertEquals(ANTICIPI_AMMESSI.size, dao.events.size, "nessun rifiuto deve aver scritto")
    }

    @Test
    fun `un titolo vuoto o di soli spazi non passa`() = runTest {
        for (titolo in listOf("", " ", "\t", "\n  \n")) {
            val esito = assertIs<EsitoScrittura.NonValida>(scritture.crea(creazione(titolo = titolo)))
            assertEquals("titolo", esito.campo)
        }
        assertTrue(dao.events.isEmpty())
    }

    @Test
    fun `un titolo oltre il limite non passa, uno lungo esatto si'`() = runTest {
        assertIs<EsitoScrittura.NonValida>(
            scritture.crea(creazione(titolo = "a".repeat(MAX_TITOLO + 1)))
        )
        assertIs<EsitoScrittura.Fatta>(scritture.crea(creazione(titolo = "a".repeat(MAX_TITOLO))))
    }

    @Test
    fun `titolo e descrizione si normalizzano come nell'editor dell'app`() = runTest {
        // `EventEditViewModel.save()` fa `trim()` sul titolo e `trim().ifBlank { null }` sulla
        // descrizione. Due strade che normalizzano diverso divergono, e si scopre confrontando
        // le due viste dello stesso evento.
        scritture.crea(creazione(titolo = "  Dentista  ", descrizione = "   "))

        assertEquals("Dentista", riga().title)
        assertNull(riga().description, "una descrizione di soli spazi è assente, non vuota")
    }

    @Test
    fun `una descrizione assente resta assente`() = runTest {
        scritture.crea(creazione(descrizione = null))
        assertNull(riga().description)
    }

    @Test
    fun `una descrizione oltre il limite non passa`() = runTest {
        val esito = assertIs<EsitoScrittura.NonValida>(
            scritture.crea(creazione(descrizione = "a".repeat(MAX_DESCRIZIONE + 1)))
        )
        assertEquals("descrizione", esito.campo)
        assertTrue(dao.events.isEmpty())
    }

    @Test
    fun `una data fuori dall'intervallo non passa`() = runTest {
        for (quando in listOf(-1L, MAX_DATA + 1, Long.MAX_VALUE)) {
            val esito = assertIs<EsitoScrittura.NonValida>(
                scritture.crea(creazione(quando = quando)),
                "$quando non doveva essere accettata"
            )
            assertEquals("dateTimeMillis", esito.campo)
        }
        assertTrue(dao.events.isEmpty())
    }

    @Test
    fun `la validazione vale anche in modifica, e non scrive niente`() = runTest {
        val prima = esistente(titolo = "originale")

        assertIs<EsitoScrittura.NonValida>(scritture.modifica(prima.id, modificaJson(anticipo = 7)))
        assertIs<EsitoScrittura.NonValida>(scritture.modifica(prima.id, modificaJson(titolo = "  ")))

        assertEquals("originale", riga().title)
        assertTrue(alarms.scheduled.isEmpty() && alarms.cancelled.isEmpty())
    }

    // --- Corpi storti ---------------------------------------------------------------------------

    @Test
    fun `un corpo che non e' il JSON atteso non fa lanciare`() = runTest {
        val corpi = listOf(
            "", "{", "non json", "[]", "null", "42",
            """{"titolo":"solo il titolo"}""",
            """{"titolo":"x","dateTimeMillis":"non un numero","advanceMinutes":0}""",
            """{"dateTimeMillis":1,"advanceMinutes":0}"""
        )
        for (corpo in corpi) {
            assertIs<EsitoScrittura.NonLeggibile>(scritture.crea(corpo), "«$corpo» doveva essere illeggibile")
            assertIs<EsitoScrittura.NonLeggibile>(scritture.modifica(1, corpo))
        }
        assertTrue(dao.events.isEmpty())
        assertTrue(alarms.scheduled.isEmpty() && alarms.cancelled.isEmpty())
    }

    @Test
    fun `un campo che il server non conosce viene ignorato, non rifiutato`() = runTest {
        // Un client più nuovo del server non deve rompersi su un campo in più.
        assertIs<EsitoScrittura.Fatta>(
            scritture.crea(
                """{"titolo":"Spesa","dateTimeMillis":9000000,"advanceMinutes":0,"ricorrenza":"ogni giorno"}"""
            )
        )
        assertEquals("Spesa", riga().title)
    }

    @Test
    fun `uuid e origin nel corpo non hanno nessun effetto`() = runTest {
        // Sono l'identità fra dispositivi: non escono verso un browser e non entrano da lì.
        val prima = esistente()
        scritture.modifica(
            prima.id,
            """{"titolo":"x","dateTimeMillis":9000000,"advanceMinutes":0,"attesoUpdatedAt":1000,""" +
                """"uuid":"iniettato","origin":"iniettato","deleted":true,"id":999}"""
        )
        assertEquals("uuid-stabile", riga().uuid)
        assertEquals("altro-dispositivo", riga().origin)
        assertEquals(false, riga().deleted)
        assertEquals(prima.id, riga().id)
    }
}

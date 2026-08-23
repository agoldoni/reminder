package it.agoldoni.reminder.web

import it.agoldoni.reminder.data.EventEntity
import it.agoldoni.reminder.data.FakeEventDao
import it.agoldoni.reminder.sync.RecordingAlarmScheduler
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CHI = "192.168.1.7"

/**
 * TC-04…TC-11 — l'attesa lunga vista dal `Router`.
 *
 * `FakeEventDao` va bene come sorgente perché `getActiveSortedAsc()` è un `MutableStateFlow.map`:
 * emette a ogni scrittura, esattamente come farà l'`InvalidationTracker` di Room. Il `drop(1)`
 * riproduce `emitInitialState = false`, che è come il flusso vero viene cablato in `ReminderApp`.
 */
class AttesaLungaTest {

    private val dao = FakeEventDao()
    private val alarms = RecordingAlarmScheduler()
    private val token = AccessToken()
    private val buoni = token.rigenera()

    private fun TestScope.impianto(
        attesaMillis: Long = 25_000L,
        massimo: Int = ATTESE_MASSIME
    ) = Router(
        scritture = ScrittureWeb(dao, alarms, "questo-telefono") { 5_000L },
        token = token,
        dao = dao,
        cambiamenti = Cambiamenti(
            dao.getActiveSortedAsc().drop(1), backgroundScope, attesaMillis, massimo
        )
    )

    private fun richiesta(
        attendi: Boolean = false,
        ifNoneMatch: String? = null,
        t: String = buoni.lettura
    ) = HttpRequest(
        method = "GET",
        path = "/api/eventi",
        query = buildMap {
            put("t", t)
            if (attendi) put("attendi", "1")
        },
        headers = buildMap { ifNoneMatch?.let { put("if-none-match", it) } }
    )

    private fun evento(titolo: String, quando: Long = 9_000L, completato: Boolean = false) =
        EventEntity(
            title = titolo,
            dateTimeMillis = quando,
            completed = completato,
            updatedAt = 1_000L,
            origin = "questo-telefono"
        )

    private fun HttpResponse.etag() = extra["ETag"]

    private fun HttpResponse.testo() = String(body, Charsets.UTF_8)

    // --- Il ritmo di prima, che non deve cambiare ------------------------------------------------

    @Test
    fun `senza attendi la lettura e' identica a prima`() = runTest {
        val router = impianto()
        dao.insert(evento("uno"))

        val prima = router.gestisci(richiesta(), CHI)
        assertEquals(200, prima.status)

        val quando = testScheduler.currentTime
        val seconda = router.gestisci(richiesta(ifNoneMatch = prima.etag()), CHI)
        assertEquals(304, seconda.status)
        assertEquals(prima.etag(), seconda.etag())
        assertEquals(quando, testScheduler.currentTime, "un client vecchio non deve aspettare nulla")
    }

    // --- L'attesa ---------------------------------------------------------------------------------

    /** Chi si era perso un giro non deve aspettarne un altro: la risposta ce l'ha già. */
    @Test
    fun `con l'impronta gia' diversa si risponde subito`() = runTest {
        val router = impianto()
        dao.insert(evento("uno"))

        val quando = testScheduler.currentTime
        val risposta = router.gestisci(richiesta(attendi = true, ifNoneMatch = "\"vecchia\""), CHI)
        assertEquals(200, risposta.status)
        assertEquals(quando, testScheduler.currentTime, "non doveva sospendere")
    }

    @Test
    fun `l'attesa si sveglia su una scrittura e porta il corpo nuovo`() = runTest {
        val router = impianto()
        dao.insert(evento("uno"))
        val etag = router.gestisci(richiesta(), CHI).etag()

        var risposta: HttpResponse? = null
        backgroundScope.launch {
            risposta = router.gestisci(richiesta(attendi = true, ifNoneMatch = etag), CHI)
        }
        runCurrent()
        assertNull(risposta, "senza scritture deve restare appesa")

        dao.insert(evento("due", quando = 10_000L))
        runCurrent()

        assertEquals(200, risposta?.status)
        assertNotEquals(etag, risposta?.etag())
        assertTrue(risposta!!.testo().contains("due"), risposta!!.testo())
    }

    /**
     * **Il presidio di US-006.** La scrittura qui non passa da `ScrittureWeb` né dal `Router`: va
     * dritta al DAO, come farebbero l'editor dell'app, lo snooze di una notifica o `SyncEngine`.
     * Se un giorno il segnale tornasse a nascere dai punti di scrittura invece che dal database,
     * è questo test a cadere.
     */
    @Test
    fun `si sveglia anche su una scrittura che non passa dalla web app`() = runTest {
        val router = impianto()
        dao.insert(evento("uno"))
        val etag = router.gestisci(richiesta(), CHI).etag()

        var risposta: HttpResponse? = null
        backgroundScope.launch {
            risposta = router.gestisci(richiesta(attendi = true, ifNoneMatch = etag), CHI)
        }
        runCurrent()

        dao.markCompleted(id = 1L, nowMillis = 7_000L)
        runCurrent()

        assertEquals(200, risposta?.status)
        assertTrue(risposta!!.testo().contains("\"eventi\":[]"), risposta!!.testo())
    }

    @Test
    fun `senza cambiamenti l'attesa scade in 304`() = runTest {
        val router = impianto(attesaMillis = 25_000L)
        dao.insert(evento("uno"))
        val etag = router.gestisci(richiesta(), CHI).etag()

        val quando = testScheduler.currentTime
        val risposta = router.gestisci(richiesta(attendi = true, ifNoneMatch = etag), CHI)
        assertEquals(304, risposta.status)
        assertEquals(etag, risposta.etag())
        assertEquals(25_000L, testScheduler.currentTime - quando)
    }

    /**
     * Sulla tabella `events` si scrive anche per cose che i promemoria aperti non le vedono. Un
     * `200` su quel segnale manderebbe il browser a ridisegnare per niente — e siccome il client
     * riparte subito, in un ciclo stretto.
     */
    @Test
    fun `un cambiamento che non tocca gli aperti non risveglia`() = runTest {
        val router = impianto(attesaMillis = 25_000L)
        dao.insert(evento("uno"))
        val etag = router.gestisci(richiesta(), CHI).etag()

        var risposta: HttpResponse? = null
        backgroundScope.launch {
            risposta = router.gestisci(richiesta(attendi = true, ifNoneMatch = etag), CHI)
        }
        runCurrent()

        dao.insert(evento("un completato", quando = 2_000L, completato = true))
        runCurrent()
        assertNull(risposta, "l'elenco degli aperti non è cambiato: non c'era niente da dire")

        testScheduler.advanceTimeBy(25_001L)
        assertEquals(304, risposta?.status)
    }

    // --- Il tetto ---------------------------------------------------------------------------------

    @Test
    fun `oltre il tetto si risponde subito, non si sbaglia`() = runTest {
        val router = impianto(attesaMillis = 25_000L, massimo = 1)
        dao.insert(evento("uno"))
        val etag = router.gestisci(richiesta(), CHI).etag()

        backgroundScope.launch { router.gestisci(richiesta(attendi = true, ifNoneMatch = etag), CHI) }
        runCurrent()

        val quando = testScheduler.currentTime
        val respinta = router.gestisci(richiesta(attendi = true, ifNoneMatch = etag), CHI)
        assertEquals(304, respinta.status, "degrada al polling, non a un errore")
        assertEquals(etag, respinta.etag())
        assertEquals(quando, testScheduler.currentTime, "e lo dice subito")
    }

    // --- Due token ---------------------------------------------------------------------------------

    /**
     * `permessi` sta dentro il corpo, quindi due token producono impronte diverse. Il contatore è
     * invece **uno solo**: si svegliano insieme e ciascuno riceve il proprio corpo.
     */
    @Test
    fun `due token si svegliano insieme e ricevono ciascuno il proprio corpo`() = runTest {
        val router = impianto()
        dao.insert(evento("uno"))

        val etagLettura = router.gestisci(richiesta(t = buoni.lettura), CHI).etag()
        val etagScrittura = router.gestisci(richiesta(t = buoni.scrittura), CHI).etag()
        assertNotEquals(etagLettura, etagScrittura)

        var inLettura: HttpResponse? = null
        var inScrittura: HttpResponse? = null
        backgroundScope.launch {
            inLettura = router.gestisci(
                richiesta(attendi = true, ifNoneMatch = etagLettura, t = buoni.lettura), CHI
            )
        }
        backgroundScope.launch {
            inScrittura = router.gestisci(
                richiesta(attendi = true, ifNoneMatch = etagScrittura, t = buoni.scrittura), CHI
            )
        }
        runCurrent()

        dao.insert(evento("due", quando = 10_000L))
        runCurrent()

        assertEquals(200, inLettura?.status)
        assertEquals(200, inScrittura?.status)
        assertTrue(inLettura!!.testo().contains("\"permessi\":\"lettura\""), inLettura!!.testo())
        assertTrue(inScrittura!!.testo().contains("\"permessi\":\"scrittura\""), inScrittura!!.testo())
    }
}

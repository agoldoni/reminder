package it.agoldoni.reminder.web

import it.agoldoni.reminder.data.EventEntity
import it.agoldoni.reminder.data.FakeEventDao
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val CHI = "192.168.1.7"

private fun richiesta(
    percorso: String,
    metodo: String = "GET",
    token: String? = null,
    ifNoneMatch: String? = null
) = HttpRequest(
    method = metodo,
    path = percorso,
    query = token?.let { mapOf("t" to it) } ?: emptyMap(),
    headers = ifNoneMatch?.let { mapOf("if-none-match" to it) } ?: emptyMap()
)

/** TC-05/06/07/09 — smistamento, controllo d'accesso e richieste condizionali. */
class RouterTest {

    private val dao = FakeEventDao()
    private val token = AccessToken()
    private val router = Router(dao, token)
    private val buono = token.rigenera()

    private fun get(
        percorso: String,
        t: String? = buono,
        ifNoneMatch: String? = null,
        chi: String = CHI
    ) = runBlocking { router.gestisci(richiesta(percorso, token = t, ifNoneMatch = ifNoneMatch), chi) }

    // --- Metodi e percorsi ---------------------------------------------------------------------

    @Test
    fun `ogni metodo diverso da GET riceve 405`() {
        for (metodo in listOf("POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")) {
            val risposta = runBlocking {
                router.gestisci(richiesta("/", metodo = metodo, token = buono), CHI)
            }
            assertEquals(405, risposta.status, "il metodo $metodo doveva ricevere 405")
        }
    }

    @Test
    fun `un percorso sconosciuto riceve 404`() {
        assertEquals(404, get("/segreto").status)
        assertEquals(404, get("/api/altro").status)
        assertEquals(404, get("/index.html").status) // si serve da `/`, non dal nome del file
    }

    @Test
    fun `tutti i percorsi dell'elenco chiuso sono serviti davvero`() {
        // Se un asset è dichiarato ma non finisce nell'artefatto, il router risponde 500: è il
        // guasto che darebbe una pagina bianca, e va visto qui e non sul telefono.
        for (percorso in StaticAssets.percorsi) {
            val risposta = get(percorso)
            assertEquals(200, risposta.status, "$percorso non è servito (500 = manca nell'artefatto)")
            assertTrue(risposta.body.isNotEmpty(), "$percorso è vuoto")
        }
    }

    @Test
    fun `la pagina e gli asset hanno il tipo giusto`() {
        assertTrue(get("/").contentType!!.startsWith("text/html"))
        assertTrue(get("/app.css").contentType!!.startsWith("text/css"))
        assertTrue(get("/app.js").contentType!!.startsWith("text/javascript"))
        assertEquals("application/manifest+json", get("/manifest.json").contentType)
        assertEquals("image/png", get("/icona-192.png").contentType)
    }

    // --- Controllo d'accesso -------------------------------------------------------------------

    @Test
    fun `senza token la pagina e i dati sono negati`() {
        assertEquals(403, get("/", t = null).status)
        assertEquals(403, get("/api/eventi", t = null).status)
    }

    @Test
    fun `token assente e token sbagliato producono la stessa identica risposta`() {
        val senza = get("/api/eventi", t = null, chi = "10.0.0.1")
        val sbagliato = get("/api/eventi", t = "sbagliato", chi = "10.0.0.2")
        assertEquals(senza, sbagliato, "distinguerli direbbe a chi sonda quando ha la forma giusta")
        assertEquals(403, senza.status)
        assertTrue(senza.body.isEmpty())
    }

    @Test
    fun `gli asset senza dati personali non pretendono il token`() {
        // È una scelta, non una dimenticanza: il manifest e le icone il browser li chiede fuori
        // dal contesto della pagina, dove il token non c'è.
        assertEquals(200, get("/app.css", t = null).status)
        assertEquals(200, get("/app.js", t = null).status)
        assertEquals(200, get("/manifest.json", t = null).status)
        assertEquals(200, get("/icona-192.png", t = null).status)
    }

    @Test
    fun `superata la soglia risponde 403 anche al token giusto`() {
        repeat(10) { get("/api/eventi", t = "sbagliato", chi = "10.0.0.9") }
        assertEquals(403, get("/api/eventi", chi = "10.0.0.9").status)
        // E l'utente da un altro indirizzo continua a passare.
        assertEquals(200, get("/api/eventi", chi = "10.0.0.1").status)
    }

    // --- Dati ----------------------------------------------------------------------------------

    @Test
    fun `il JSON contiene i soli promemoria aperti, in ordine di data`() {
        runBlocking {
            dao.insert(evento(id = 1, titolo = "terzo", quando = 3_000))
            dao.insert(evento(id = 2, titolo = "primo", quando = 1_000))
            dao.insert(evento(id = 3, titolo = "secondo", quando = 2_000))
            dao.insert(evento(id = 4, titolo = "fatto", quando = 500, completato = true))
            dao.insert(evento(id = 5, titolo = "cancellato", quando = 600, cancellato = true))
        }
        val corpo = get("/api/eventi").body.decodeToString()
        assertTrue("primo" in corpo && "secondo" in corpo && "terzo" in corpo, corpo)
        assertTrue("fatto" !in corpo, "un completato non deve comparire")
        assertTrue("cancellato" !in corpo, "un tombstone non deve comparire")
        assertTrue(
            corpo.indexOf("primo") < corpo.indexOf("secondo") &&
                corpo.indexOf("secondo") < corpo.indexOf("terzo"),
            "l'ordine deve essere quello dell'app: $corpo"
        )
    }

    @Test
    fun `il JSON non porta fuori l'identita' degli eventi fra dispositivi`() {
        runBlocking { dao.insert(evento(id = 1, titolo = "spesa", quando = 1_000)) }
        val corpo = get("/api/eventi").body.decodeToString()
        assertTrue("uuid" !in corpo, "uuid non ha ragione di uscire verso un browser: $corpo")
        assertTrue("origin" !in corpo, "origin non ha ragione di uscire verso un browser: $corpo")
        assertTrue("\"id\":1" in corpo, "l'identificatore stabile deve esserci: $corpo")
        assertTrue("\"versione\":1" in corpo, "la busta deve dichiarare la versione: $corpo")
    }

    @Test
    fun `l'orario di notifica arriva gia' calcolato`() {
        runBlocking { dao.insert(evento(id = 1, titolo = "x", quando = 10_000_000, anticipo = 30)) }
        val corpo = get("/api/eventi").body.decodeToString()
        assertTrue("\"notificationMillis\":${10_000_000 - 30 * 60_000}" in corpo, corpo)
        // Nessuna data formattata: la formattazione è del browser.
        assertTrue("/20" !in corpo, "il JSON non deve contenere date formattate: $corpo")
    }

    @Test
    fun `una descrizione assente non rompe il formato`() {
        runBlocking { dao.insert(evento(id = 1, titolo = "senza note", quando = 1_000)) }
        assertEquals(200, get("/api/eventi").status)
    }

    // --- Richieste condizionali ----------------------------------------------------------------

    @Test
    fun `a dati invariati l'impronta e' stabile e la seconda richiesta riceve 304`() {
        runBlocking { dao.insert(evento(id = 1, titolo = "spesa", quando = 1_000)) }
        val prima = get("/api/eventi")
        val impronta = prima.extra["ETag"]!!
        assertEquals(impronta, get("/api/eventi").extra["ETag"], "l'impronta deve essere stabile")

        val seconda = get("/api/eventi", ifNoneMatch = impronta)
        assertEquals(304, seconda.status)
        assertTrue(seconda.body.isEmpty(), "un 304 non porta corpo")
        assertEquals(impronta, seconda.extra["ETag"])
    }

    @Test
    fun `un'impronta diversa o assente riceve il corpo`() {
        runBlocking { dao.insert(evento(id = 1, titolo = "spesa", quando = 1_000)) }
        assertEquals(200, get("/api/eventi", ifNoneMatch = "\"altra\"").status)
        assertEquals(200, get("/api/eventi").status)
    }

    @Test
    fun `l'impronta cambia a ogni modifica, anche quando completare fa scendere updatedAt`() {
        // È il caso per cui l'impronta si calcola sul corpo e non su `max(updatedAt)`: completando
        // il più recente, il massimo fra i rimasti *scende*, e un client che confrontasse quello
        // non si accorgerebbe di niente.
        runBlocking {
            dao.insert(evento(id = 1, titolo = "vecchio", quando = 1_000, aggiornato = 100))
            dao.insert(evento(id = 2, titolo = "nuovo", quando = 2_000, aggiornato = 900))
        }
        val prima = get("/api/eventi").extra["ETag"]!!

        runBlocking { dao.markCompleted(2, 950) }
        val dopo = get("/api/eventi").extra["ETag"]!!
        assertNotEquals(prima, dopo, "completare un promemoria deve cambiare l'impronta")

        runBlocking { dao.insert(evento(id = 3, titolo = "aggiunto", quando = 3_000)) }
        assertNotEquals(dopo, get("/api/eventi").extra["ETag"], "aggiungere deve cambiare l'impronta")

        val terzo = get("/api/eventi").extra["ETag"]!!
        runBlocking { dao.softDelete(3, 1_000) }
        assertNotEquals(terzo, get("/api/eventi").extra["ETag"], "eliminare deve cambiare l'impronta")
    }

    private fun evento(
        id: Long,
        titolo: String,
        quando: Long,
        anticipo: Int = 0,
        completato: Boolean = false,
        cancellato: Boolean = false,
        aggiornato: Long = 1_000
    ) = EventEntity(
        id = id,
        title = titolo,
        dateTimeMillis = quando,
        advanceMinutes = anticipo,
        completed = completato,
        deleted = cancellato,
        updatedAt = aggiornato,
        uuid = "uuid-$id",
        origin = "dispositivo-di-prova"
    )
}

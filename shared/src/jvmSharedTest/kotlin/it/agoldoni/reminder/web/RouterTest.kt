package it.agoldoni.reminder.web

import it.agoldoni.reminder.data.EventEntity
import it.agoldoni.reminder.data.FakeEventDao
import it.agoldoni.reminder.sync.RecordingAlarmScheduler
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CHI = "192.168.1.7"

private const val JSON = "application/json"

private fun richiesta(
    percorso: String,
    metodo: String = "GET",
    token: String? = null,
    ifNoneMatch: String? = null,
    corpo: String = "",
    contentType: String? = if (corpo.isEmpty()) null else JSON
) = HttpRequest(
    method = metodo,
    path = percorso,
    query = token?.let { mapOf("t" to it) } ?: emptyMap(),
    headers = buildMap {
        ifNoneMatch?.let { put("if-none-match", it) }
        contentType?.let { put("content-type", it) }
    },
    body = corpo
)

/** TC-05/06/07/09/13/14/15/16 — smistamento, controllo d'accesso, richieste condizionali, scritture. */
class RouterTest {

    private val dao = FakeEventDao()
    private val alarms = RecordingAlarmScheduler()
    private val token = AccessToken()
    private var orologio = 5_000L

    private val router = Router(
        scritture = ScrittureWeb(dao, alarms, "questo-telefono") { orologio },
        token = token,
        dao = dao
    )

    private val buoni = token.rigenera()
    private val lettura get() = buoni.lettura
    private val scrittura get() = buoni.scrittura

    private fun get(
        percorso: String,
        t: String? = lettura,
        ifNoneMatch: String? = null,
        chi: String = CHI
    ) = runBlocking { router.gestisci(richiesta(percorso, token = t, ifNoneMatch = ifNoneMatch), chi) }

    private fun invia(
        percorso: String,
        metodo: String,
        corpo: String,
        t: String? = scrittura,
        contentType: String? = JSON,
        chi: String = CHI
    ) = runBlocking {
        router.gestisci(
            richiesta(percorso, metodo = metodo, token = t, corpo = corpo, contentType = contentType),
            chi
        )
    }

    private fun crea(
        corpo: String = """{"titolo":"Spesa","dateTimeMillis":9000000,"advanceMinutes":15}""",
        t: String? = scrittura,
        contentType: String? = JSON
    ) = invia("/api/eventi", "POST", corpo, t, contentType)

    private fun modifica(
        id: Long,
        corpo: String,
        t: String? = scrittura,
        contentType: String? = JSON
    ) = invia("/api/eventi/$id", "PUT", corpo, t, contentType)

    // --- Metodi e percorsi ---------------------------------------------------------------------

    @Test
    fun `i metodi che questo server non conosce ricevono sempre 405 o 404`() {
        for (metodo in listOf("DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE")) {
            assertEquals(405, get405("/", metodo), "il metodo $metodo su / doveva ricevere 405")
            assertEquals(
                405,
                get405("/api/eventi", metodo),
                "il metodo $metodo su /api/eventi doveva ricevere 405"
            )
        }
    }

    private fun get405(percorso: String, metodo: String) = runBlocking {
        router.gestisci(richiesta(percorso, metodo = metodo, token = scrittura), CHI)
    }.status

    @Test
    fun `POST e PUT sono ammessi solo dove hanno senso, con l'Allow giusto`() {
        // POST sta su /api/eventi e da nessun'altra parte.
        assertEquals(405, get405("/", "POST"))
        assertEquals("GET", runBlocking {
            router.gestisci(richiesta("/", metodo = "POST", token = scrittura), CHI)
        }.extra["Allow"])

        // PUT vuole un id: sulla collezione non ha significato.
        val senzaId = runBlocking {
            router.gestisci(richiesta("/api/eventi", metodo = "PUT", token = scrittura), CHI)
        }
        assertEquals(405, senzaId.status)
        assertEquals("GET, POST", senzaId.extra["Allow"])

        // E POST su un singolo evento nemmeno.
        val suUnId = runBlocking {
            router.gestisci(richiesta("/api/eventi/1", metodo = "POST", token = scrittura), CHI)
        }
        assertEquals(405, suUnId.status)
        assertEquals("PUT", suUnId.extra["Allow"])
    }

    @Test
    fun `un percorso sconosciuto riceve 404`() {
        assertEquals(404, get("/segreto").status)
        assertEquals(404, get("/api/altro").status)
        assertEquals(404, get("/index.html").status) // si serve da `/`, non dal nome del file
    }

    @Test
    fun `un id che non e' un numero non e' una risorsa`() {
        // 404 e non 400: al server non interessa perché il client l'abbia scritto così.
        assertEquals(404, modifica0("/api/eventi/abc").status)
        assertEquals(404, modifica0("/api/eventi/").status)
    }

    private fun modifica0(percorso: String) =
        invia(percorso, "PUT", """{"titolo":"x","dateTimeMillis":1,"advanceMinutes":0,"attesoUpdatedAt":1}""")

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

    @Test
    fun `il token di scrittura apre anche la pagina`() {
        assertEquals(200, get("/", t = scrittura).status)
        assertEquals(200, get("/api/eventi", t = scrittura).status)
    }

    @Test
    fun `col token di lettura una scrittura e' negata, e il database non cambia`() {
        // È **questo** il criterio, non il fatto che la pagina nasconda i comandi: nascondere
        // non è proteggere.
        assertEquals(403, crea(t = lettura).status)
        assertTrue(dao.events.isEmpty(), "un 403 non deve aver creato niente")

        val id = runBlocking { dao.insert(evento(id = 1, titolo = "originale", quando = 1_000)) }
        assertEquals(
            403,
            modifica(id, """{"titolo":"forzato","dateTimeMillis":1,"advanceMinutes":0,"attesoUpdatedAt":1000}""", t = lettura).status
        )
        assertEquals("originale", dao.events.first().title, "un 403 non deve aver modificato niente")
        assertTrue(alarms.scheduled.isEmpty() && alarms.cancelled.isEmpty())
    }

    @Test
    fun `una scrittura senza token e' negata come le altre`() {
        assertEquals(403, crea(t = null).status)
        assertEquals(403, crea(t = "sbagliato").status)
        assertTrue(dao.events.isEmpty())
    }

    // --- Si scrive anche ad app chiusa ---------------------------------------------------------

    @Test
    fun `la scrittura non dipende dallo stato dell'app`() {
        // Una prima stesura la permetteva solo con l'app in primo piano. La verifica sul
        // dispositivo dice che ad app chiusa funziona — database e sveglie, con l'Activity
        // distrutta e il processo tenuto vivo dal solo servizio in primo piano — e rifiutarla
        // sarebbe stato rispondere una bugia. Qui non c'è nessuno stato da simulare: è il punto.
        assertEquals(201, crea().status)
        assertEquals(1, dao.events.size)
    }

    @Test
    fun `il payload non dichiara piu' una disponibilita' che non esiste`() {
        val corpo = get("/api/eventi", t = scrittura).body.decodeToString()
        assertTrue("scritturaDisponibile" !in corpo, "campo rimosso con il cancello: $corpo")
    }

    // --- Corpo e tipo --------------------------------------------------------------------------

    @Test
    fun `un content-type che non e' JSON riceve 415`() {
        // Un modulo HTML cross-origin può mandare solo questi: pretendere JSON fa scattare il
        // preflight, che quel modulo non supera.
        for (tipo in listOf(
            "application/x-www-form-urlencoded",
            "multipart/form-data; boundary=x",
            "text/plain",
            null
        )) {
            assertEquals(415, crea(contentType = tipo).status, "tipo «$tipo» non doveva passare")
        }
        assertTrue(dao.events.isEmpty())
    }

    @Test
    fun `il charset accanto al tipo non da' fastidio`() {
        assertEquals(201, crea(contentType = "application/json; charset=utf-8").status)
    }

    @Test
    fun `un JSON che non si capisce riceve 400`() {
        for (corpo in listOf("", "{", "non json", "[]", """{"titolo":"solo questo"}""")) {
            assertEquals(400, crea(corpo = corpo).status, "«$corpo» doveva essere 400")
        }
    }

    @Test
    fun `un campo fuori regola riceve 422 e dice quale`() {
        val risposta = crea(
            corpo = """{"titolo":"Spesa","dateTimeMillis":9000000,"advanceMinutes":7}"""
        )
        assertEquals(422, risposta.status)
        assertTrue(
            "advanceMinutes" in risposta.body.decodeToString(),
            "chi ha già il token merita di sapere quale campo: ${risposta.body.decodeToString()}"
        )
        assertTrue(dao.events.isEmpty())
    }

    // --- Le scritture --------------------------------------------------------------------------

    @Test
    fun `creare risponde 201 con la lista aggiornata e la sua impronta`() {
        val risposta = crea()
        assertEquals(201, risposta.status)

        val corpo = risposta.body.decodeToString()
        assertTrue("Spesa" in corpo, "la risposta deve portare la lista: $corpo")
        assertEquals(get("/api/eventi", t = scrittura).extra["ETag"], risposta.extra["ETag"])

        // E l'impronta è già quella giusta: la richiesta condizionale successiva riceve 304.
        assertEquals(
            304,
            get("/api/eventi", t = scrittura, ifNoneMatch = risposta.extra["ETag"]).status
        )
    }

    @Test
    fun `modificare risponde 200 e cambia davvero la riga`() {
        val id = runBlocking { dao.insert(evento(id = 1, titolo = "vecchio", quando = 1_000, aggiornato = 900)) }

        val risposta = modifica(
            id,
            """{"titolo":"nuovo","dateTimeMillis":9000000,"advanceMinutes":30,"completato":false,"attesoUpdatedAt":900}"""
        )
        assertEquals(200, risposta.status)
        assertEquals("nuovo", dao.events.first().title)
        assertEquals(30, dao.events.first().advanceMinutes)
    }

    @Test
    fun `una scrittura riuscita dichiara in un header che cosa ha scritto`() {
        // Nel corpo cambierebbe l'impronta, e chi ha appena salvato non riceverebbe più 304.
        val creata = crea()
        val scritto = creata.extra["X-Promemoria-Scritto"]
        assertNotNull(scritto, "senza questo il browser non può annullare una completazione")
        assertEquals("${dao.events.first().id}:$orologio", scritto)

        // E il 409 non lo porta: non ha scritto niente.
        val id = runBlocking { dao.insert(evento(id = 9, titolo = "x", quando = 1_000, aggiornato = 900)) }
        val conflitto = modifica(
            id,
            """{"titolo":"y","dateTimeMillis":9000000,"advanceMinutes":0,"attesoUpdatedAt":1}"""
        )
        assertEquals(409, conflitto.status)
        assertNull(conflitto.extra["X-Promemoria-Scritto"])
    }

    @Test
    fun `un attesoUpdatedAt sorpassato riceve 409, con il valore vero nel corpo`() {
        val id = runBlocking { dao.insert(evento(id = 1, titolo = "dal telefono", quando = 1_000, aggiornato = 900)) }

        val risposta = modifica(
            id,
            """{"titolo":"dal browser","dateTimeMillis":9000000,"advanceMinutes":0,"attesoUpdatedAt":100}"""
        )
        assertEquals(409, risposta.status)
        assertEquals("dal telefono", dao.events.first().title, "il conflitto non scrive")
        assertTrue(
            "dal telefono" in risposta.body.decodeToString(),
            "chi ha perso il confronto deve vedere il valore vero, e ce l'ha già qui"
        )
        assertEquals("\"" + risposta.extra["ETag"]!!.trim('"') + "\"", risposta.extra["ETag"])
    }

    @Test
    fun `un attesoUpdatedAt mancante riceve 400, non passa lo stesso`() {
        val id = runBlocking { dao.insert(evento(id = 1, titolo = "x", quando = 1_000, aggiornato = 900)) }
        assertEquals(
            400,
            modifica(id, """{"titolo":"y","dateTimeMillis":9000000,"advanceMinutes":0}""").status
        )
        assertEquals("x", dao.events.first().title)
    }

    @Test
    fun `un id inesistente o cancellato riceve 404`() {
        assertEquals(
            404,
            modifica(999, """{"titolo":"x","dateTimeMillis":1,"advanceMinutes":0,"attesoUpdatedAt":1}""").status
        )

        val id = runBlocking { dao.insert(evento(id = 1, titolo = "sparito", quando = 1_000)) }
        runBlocking { dao.softDelete(id, 2_000) }
        assertEquals(
            404,
            modifica(id, """{"titolo":"risorto","dateTimeMillis":1,"advanceMinutes":0,"attesoUpdatedAt":2000}""").status
        )
    }

    // --- Il payload ----------------------------------------------------------------------------

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
        assertTrue("\"versione\":2" in corpo, "la busta deve dichiarare la versione: $corpo")
    }

    @Test
    fun `updatedAt esce, perche' il controllo ottimistico non ha altro su cui poggiare`() {
        runBlocking { dao.insert(evento(id = 1, titolo = "spesa", quando = 1_000, aggiornato = 7_777)) }
        assertTrue("\"updatedAt\":7777" in get("/api/eventi").body.decodeToString())
    }

    @Test
    fun `i permessi nel payload riflettono il token usato`() {
        assertTrue("\"permessi\":\"lettura\"" in get("/api/eventi", t = lettura).body.decodeToString())
        assertTrue("\"permessi\":\"scrittura\"" in get("/api/eventi", t = scrittura).body.decodeToString())
    }

    @Test
    fun `i due token vedono impronte diverse, e non si disturbano`() {
        // `permessi` sta dentro il corpo, su cui si calcola l'impronta. È innocuo: ogni browser
        // confronta l'impronta con la propria, e le due sessioni non si vedono.
        runBlocking { dao.insert(evento(id = 1, titolo = "spesa", quando = 1_000)) }
        assertNotEquals(
            get("/api/eventi", t = lettura).extra["ETag"],
            get("/api/eventi", t = scrittura).extra["ETag"]
        )
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
        assertNull(dao.events.first().description)
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

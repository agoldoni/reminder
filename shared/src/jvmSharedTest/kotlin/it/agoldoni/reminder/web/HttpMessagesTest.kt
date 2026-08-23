package it.agoldoni.reminder.web

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun leggi(grezza: String): RichiestaLetta =
    leggiRichiesta(ByteArrayInputStream(grezza.toByteArray(Charsets.ISO_8859_1)))

private fun ok(grezza: String): HttpRequest =
    assertIs<RichiestaLetta.Ok>(leggi(grezza), "atteso Ok per:\n$grezza").request

/**
 * Richiesta costruita **a byte**, perché sul corpo si prova proprio ciò che non è testo ASCII:
 * UTF-8 multibyte, sequenze non valide, e una lunghezza dichiarata che non coincide con quella
 * vera. Con una `String` quei casi si perderebbero nella conversione prima di arrivare al parser.
 */
private fun grezzaConCorpo(
    metodo: String = "POST",
    corpo: ByteArray = ByteArray(0),
    dichiarata: String? = corpo.size.toString(),
    intestazioniInPiu: String = ""
): ByteArray {
    val testa = StringBuilder("$metodo /api/eventi?t=abc HTTP/1.1\r\nHost: x\r\n")
    dichiarata?.let { testa.append("Content-Length: ").append(it).append("\r\n") }
    testa.append(intestazioniInPiu).append("\r\n")
    return testa.toString().toByteArray(Charsets.ISO_8859_1) + corpo
}

private fun leggiByte(grezzi: ByteArray): RichiestaLetta = leggiRichiesta(ByteArrayInputStream(grezzi))

private fun malformata(grezzi: ByteArray, perche: String): String =
    assertIs<RichiestaLetta.Malformata>(leggiByte(grezzi), perche).motivo

/**
 * TC-01 — il parser non deve lanciare fuori per nessun input, per quanto costruito male.
 *
 * È il punto in cui la rete tocca il codice: qui arriva ciò che manda chiunque, non ciò che manda
 * il nostro JavaScript. Ogni caso storto deve diventare una risposta, non un'eccezione che risale
 * fino al ciclo di ascolto.
 */
class HttpMessagesTest {

    @Test
    fun `una richiesta ben formata si legge in metodo, percorso, query e header`() {
        val r = ok(
            "GET /api/eventi?t=abc123&x=1 HTTP/1.1\r\n" +
                "Host: 192.168.1.42:9888\r\n" +
                "If-None-Match: \"impronta\"\r\n" +
                "\r\n"
        )
        assertEquals("GET", r.method)
        assertEquals("/api/eventi", r.path)
        assertEquals("abc123", r.query["t"])
        assertEquals("1", r.query["x"])
        assertEquals("\"impronta\"", r.header("if-none-match"))
    }

    @Test
    fun `i nomi degli header non distinguono maiuscole e minuscole`() {
        val r = ok("GET / HTTP/1.1\r\nIf-None-Match: abc\r\n\r\n")
        assertEquals("abc", r.header("IF-NONE-MATCH"))
        assertEquals("abc", r.header("if-none-match"))
    }

    @Test
    fun `una riga terminata da solo LF si legge come le altre`() {
        // curl e telnet mandano CRLF, ma non tutti i client lo fanno.
        assertEquals("/", ok("GET / HTTP/1.1\nHost: x\n\n").path)
    }

    @Test
    fun `un client che chiude senza mandare niente non e' una richiesta malformata`() {
        // Distinzione che conta: qui non c'è nessuno a cui rispondere 400.
        assertIs<RichiestaLetta.Chiusa>(leggi(""))
    }

    @Test
    fun `una richiesta troncata a meta' e' malformata`() {
        assertIs<RichiestaLetta.Malformata>(leggi("GET / HTTP/1.1\r\nHost: x\r\n"))
    }

    @Test
    fun `una riga di richiesta oltre il limite e' malformata e non riempie la memoria`() {
        val lunga = "GET /" + "a".repeat(Http.MAX_LINEA * 2) + " HTTP/1.1\r\n\r\n"
        assertIs<RichiestaLetta.Malformata>(leggi(lunga))
    }

    @Test
    fun `un header oltre il limite e' malformato`() {
        val grezza = "GET / HTTP/1.1\r\nX-Grosso: " + "a".repeat(Http.MAX_LINEA * 2) + "\r\n\r\n"
        assertIs<RichiestaLetta.Malformata>(leggi(grezza))
    }

    @Test
    fun `troppi header sono malformati`() {
        val header = (1..Http.MAX_HEADER + 5).joinToString("") { "X-$it: v\r\n" }
        assertIs<RichiestaLetta.Malformata>(leggi("GET / HTTP/1.1\r\n$header\r\n"))
    }

    @Test
    fun `un header senza due punti e' malformato`() {
        assertIs<RichiestaLetta.Malformata>(leggi("GET / HTTP/1.1\r\nsenza-due-punti\r\n\r\n"))
    }

    @Test
    fun `una riga di richiesta non in tre parti e' malformata`() {
        assertIs<RichiestaLetta.Malformata>(leggi("GET /\r\n\r\n"))
        assertIs<RichiestaLetta.Malformata>(leggi("GET / HTTP/1.1 extra\r\n\r\n"))
        assertIs<RichiestaLetta.Malformata>(leggi("\r\n"))
    }

    @Test
    fun `una versione non dichiarata e' malformata`() {
        assertIs<RichiestaLetta.Malformata>(leggi("GET / QUALCOSA/1.1\r\n\r\n"))
    }

    @Test
    fun `un metodo che non e' fatto di sole maiuscole e' malformato`() {
        assertIs<RichiestaLetta.Malformata>(leggi("g3t / HTTP/1.1\r\n\r\n"))
    }

    @Test
    fun `byte non ASCII nella richiesta non fanno lanciare niente`() {
        val grezzi = byteArrayOf(0xC3.toByte(), 0xA8.toByte(), 0x00, 0xFF.toByte(), 13, 10)
        // Non interessa quale esito: interessa che sia un esito e non un'eccezione.
        assertIs<RichiestaLetta.Malformata>(leggiRichiesta(ByteArrayInputStream(grezzi)))
    }

    // --- TC-04: percorsi costruiti per uscire dall'elenco --------------------------------------

    @Test
    fun `i percorsi che tentano di risalire sono respinti prima di arrivare al router`() {
        for (tentativo in listOf(
            "/../CLAUDE.md",
            "/web/../../etc/passwd",
            "/..%2fetc%2fpasswd",
            "/%2e%2e/segreto",
            "/app.css/../../x",
            "/\\..\\x",
            "//app.css",
            "/%00",
            "senza-barra-iniziale"
        )) {
            assertIs<RichiestaLetta.Malformata>(
                leggi("GET $tentativo HTTP/1.1\r\n\r\n"),
                "doveva essere respinto: $tentativo"
            )
        }
    }

    @Test
    fun `la decodifica percentuale avviene una volta sola`() {
        // `%252e` decodificato due volte diventerebbe `.`; una volta sola resta `%2e` letterale.
        val r = ok("GET /%252e%252e/x HTTP/1.1\r\n\r\n")
        assertEquals("/%2e%2e/x", r.path)
        assertTrue(!r.path.contains(".."), "la doppia decodifica farebbe passare la risalita")
    }

    @Test
    fun `una sequenza percentuale incompleta rende il percorso malformato`() {
        assertIs<RichiestaLetta.Malformata>(leggi("GET /app%2 HTTP/1.1\r\n\r\n"))
        assertIs<RichiestaLetta.Malformata>(leggi("GET /app%zz HTTP/1.1\r\n\r\n"))
    }

    @Test
    fun `nella query vince la prima occorrenza di una chiave`() {
        // Altrimenti `?t=buono&t=cattivo` lascerebbe scegliere all'ultimo arrivato.
        assertEquals("buono", ok("GET /?t=buono&t=cattivo HTTP/1.1\r\n\r\n").query["t"])
    }

    @Test
    fun `una query vuota o senza valori non fa lanciare niente`() {
        assertEquals(emptyMap(), ok("GET /? HTTP/1.1\r\n\r\n").query)
        assertEquals("", ok("GET /?t HTTP/1.1\r\n\r\n").query["t"])
        assertNull(ok("GET / HTTP/1.1\r\n\r\n").query["t"])
    }

    // --- TC-07: header di sicurezza su ogni risposta -------------------------------------------

    @Test
    fun `ogni risposta porta gli header di sicurezza e si chiude`() {
        for (risposta in listOf(
            HttpResponse.testo(200, "ciao"),
            HttpResponse.vuota(403),
            HttpResponse.vuota(304, mapOf("ETag" to "\"x\"")),
            HttpResponse.testo(405, "")
        )) {
            val uscita = ByteArrayOutputStream()
            scriviRisposta(uscita, risposta)
            val testo = uscita.toString(Charsets.ISO_8859_1.name())
            assertTrue("Referrer-Policy: no-referrer" in testo, "manca Referrer-Policy in:\n$testo")
            assertTrue("Cache-Control: no-store" in testo, "manca Cache-Control in:\n$testo")
            assertTrue("X-Content-Type-Options: nosniff" in testo, "manca nosniff in:\n$testo")
            assertTrue("Connection: close" in testo, "manca Connection: close in:\n$testo")
            assertTrue("Content-Length: ${risposta.body.size}" in testo, "Content-Length sbagliata")
        }
    }

    @Test
    fun `un 403 non dice niente di piu' di quanto serva`() {
        val uscita = ByteArrayOutputStream()
        scriviRisposta(uscita, HttpResponse.vuota(403))
        val testo = uscita.toString(Charsets.ISO_8859_1.name())
        assertTrue(testo.startsWith("HTTP/1.1 403 Forbidden"), testo)
        assertTrue(testo.endsWith("\r\n\r\n"), "il corpo deve essere vuoto")
    }

    @Test
    fun `gli header aggiuntivi finiscono nella risposta`() {
        val uscita = ByteArrayOutputStream()
        scriviRisposta(uscita, HttpResponse.vuota(304, mapOf("ETag" to "\"abc\"")))
        assertTrue("ETag: \"abc\"" in uscita.toString(Charsets.ISO_8859_1.name()))
    }

    // --- Il corpo (feature 004) -----------------------------------------------------------------
    //
    // È la superficie nuova su una porta esposta, e la difesa che sostituisce non c'è più: prima
    // il corpo non si leggeva affatto. Ogni test qui sotto sta in piedi da solo, ma il criterio è
    // uno: **niente dev'essere ambiguo sulla lunghezza**.

    @Test
    fun `un corpo dichiarato si legge per intero`() {
        val corpo = """{"titolo":"Dentista"}""".toByteArray()
        val r = assertIs<RichiestaLetta.Ok>(leggiByte(grezzaConCorpo(corpo = corpo))).request
        assertEquals("""{"titolo":"Dentista"}""", r.body)
        assertEquals("POST", r.method)
        assertEquals("abc", r.query["t"], "la query resta leggibile con un corpo dietro")
    }

    @Test
    fun `si leggono esattamente i byte dichiarati, non uno di piu'`() {
        // Il client ne manda venti e ne dichiara cinque: i quindici in eccesso non sono corpo, e
        // trattarli come tale vorrebbe dire lasciare che sia il mittente a decidere dove finisce.
        val r = assertIs<RichiestaLetta.Ok>(
            leggiByte(grezzaConCorpo(corpo = "0123456789abcdefghij".toByteArray(), dichiarata = "5"))
        ).request
        assertEquals("01234", r.body)
    }

    @Test
    fun `un corpo piu' corto del dichiarato e' malformato`() {
        // Il client ha chiuso a metà: non si lavora su mezzo JSON.
        malformata(
            grezzaConCorpo(corpo = "0123".toByteArray(), dichiarata = "100"),
            "un corpo troncato non va accettato"
        )
    }

    @Test
    fun `un POST senza content-length e' malformato`() {
        malformata(
            grezzaConCorpo(corpo = "{}".toByteArray(), dichiarata = null),
            "senza lunghezza dichiarata non c'è modo non ambiguo di sapere dove finisce"
        )
    }

    @Test
    fun `un content-length non numerico e' malformato`() {
        for (valore in listOf("abc", "-5", "+5", "5 5", "5,5", "0x10", " ", "5.0")) {
            malformata(
                grezzaConCorpo(corpo = "{}".toByteArray(), dichiarata = valore),
                "«$valore» non è una lunghezza"
            )
        }
    }

    @Test
    fun `un content-length fuori scala non fa lanciare`() {
        // Più grande di un Int: deve diventare un 400, non un'eccezione di conversione.
        malformata(
            grezzaConCorpo(corpo = "{}".toByteArray(), dichiarata = "99999999999999999999"),
            "una lunghezza assurda è una richiesta malformata"
        )
    }

    @Test
    fun `un corpo oltre il limite e' respinto sulla dichiarazione, senza leggerlo`() {
        // Si manda **un byte solo** dichiarandone milioni: se il limite fosse controllato leggendo,
        // questo test si bloccherebbe o passerebbe per il motivo sbagliato.
        malformata(
            grezzaConCorpo(corpo = ByteArray(1), dichiarata = "${Http.MAX_CORPO + 1}"),
            "il limite si applica al valore dichiarato"
        )
        // E il valore esatto del limite passa: il confine è dove è scritto che sia.
        assertIs<RichiestaLetta.Ok>(
            leggiByte(grezzaConCorpo(corpo = ByteArray(Http.MAX_CORPO) { 'a'.code.toByte() }))
        )
    }

    @Test
    fun `transfer-encoding non si interpreta, si rifiuta`() {
        for (valore in listOf("chunked", "identity", "gzip, chunked")) {
            malformata(
                grezzaConCorpo(
                    corpo = "4\r\nciao\r\n0\r\n\r\n".toByteArray(),
                    dichiarata = null,
                    intestazioniInPiu = "Transfer-Encoding: $valore\r\n"
                ),
                "«$valore» dichiara la lunghezza dentro il flusso: non si sa gestire"
            )
        }
    }

    @Test
    fun `due content-length in disaccordo sono malformati`() {
        // La forma classica del request smuggling. Qui non c'è keep-alive, quindi non ci sarebbe
        // una richiesta successiva da contaminare — ma la mappa degli header sceglierebbe in
        // silenzio quale credere, ed è la scelta silenziosa a non dover esistere.
        malformata(
            grezzaConCorpo(
                corpo = "0123456789".toByteArray(),
                dichiarata = "10",
                intestazioniInPiu = "Content-Length: 3\r\n"
            ),
            "due lunghezze sono una di troppo"
        )
    }

    @Test
    fun `due content-length identici sono malformati lo stesso`() {
        // Non è pignoleria: distinguere «ripetuto uguale» da «ripetuto diverso» aggiungerebbe un
        // ramo a un controllo che vale proprio perché non ne ha.
        malformata(
            grezzaConCorpo(
                corpo = "0123456789".toByteArray(),
                dichiarata = "10",
                intestazioniInPiu = "Content-Length: 10\r\n"
            ),
            "un content-length ripetuto è comunque ambiguo"
        )
    }

    @Test
    fun `un GET puo' dichiarare un corpo vuoto ma non uno vero`() {
        // Alcuni client mandano `Content-Length: 0` su GET: è innocuo e va accettato.
        assertEquals("", assertIs<RichiestaLetta.Ok>(
            leggiByte(grezzaConCorpo(metodo = "GET", dichiarata = "0"))
        ).request.body)

        // Un corpo vero su GET no: accettarlo e ignorarlo lascerebbe byte non letti sul socket.
        malformata(
            grezzaConCorpo(metodo = "GET", corpo = "{}".toByteArray()),
            "un GET con un corpo vero non è una richiesta a cui si sappia rispondere"
        )
    }

    @Test
    fun `un POST con corpo vuoto e' lecito`() {
        // Sarà il router a dire che quel JSON non si capisce: qui la richiesta è ben formata.
        assertEquals("", assertIs<RichiestaLetta.Ok>(
            leggiByte(grezzaConCorpo(dichiarata = "0"))
        ).request.body)
    }

    @Test
    fun `il corpo si decodifica UTF-8, accenti compresi`() {
        val testo = """{"titolo":"Riunione col perché — è già lunedì 12:30 ☕"}"""
        val r = assertIs<RichiestaLetta.Ok>(
            leggiByte(grezzaConCorpo(corpo = testo.toByteArray(Charsets.UTF_8)))
        ).request
        assertEquals(testo, r.body, "la lunghezza si conta in byte, il testo si legge in caratteri")
    }

    @Test
    fun `byte non validi UTF-8 nel corpo non fanno lanciare`() {
        val r = assertIs<RichiestaLetta.Ok>(
            leggiByte(grezzaConCorpo(corpo = byteArrayOf(0x7b, 0xC3.toByte(), 0x28, 0x7d)))
        ).request
        assertTrue(r.body.isNotEmpty(), "un corpo storto diventa testo storto, non un'eccezione")
    }

    @Test
    fun `il corpo non altera la lettura di percorso e header`() {
        val r = assertIs<RichiestaLetta.Ok>(
            leggiByte(
                grezzaConCorpo(
                    metodo = "PUT",
                    corpo = "{}".toByteArray(),
                    intestazioniInPiu = "Content-Type: application/json\r\nIf-None-Match: \"abc\"\r\n"
                )
            )
        ).request
        assertEquals("PUT", r.method)
        assertEquals("/api/eventi", r.path)
        assertEquals("application/json", r.header("content-type"))
        assertEquals("\"abc\"", r.header("If-None-Match"), "i nomi restano insensibili al caso")
    }

    @Test
    fun `i codici di stato nuovi hanno la loro descrizione`() {
        for ((codice, atteso) in listOf(
            201 to "Created",
            409 to "Conflict",
            415 to "Unsupported Media Type",
            422 to "Unprocessable Content",
            503 to "Service Unavailable"
        )) {
            val uscita = ByteArrayOutputStream()
            scriviRisposta(uscita, HttpResponse.vuota(codice))
            assertTrue(
                uscita.toString(Charsets.ISO_8859_1.name()).startsWith("HTTP/1.1 $codice $atteso"),
                "il codice $codice non si presenta come «$atteso»"
            )
        }
    }
}

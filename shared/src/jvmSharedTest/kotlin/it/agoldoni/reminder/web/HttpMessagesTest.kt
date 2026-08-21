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
}

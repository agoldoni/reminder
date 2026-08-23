package it.agoldoni.reminder.web

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TC-08/09/10 — il server su socket veri, legato a **porta 0** perché il sistema ne scelga una
 * libera: un test che pretende una porta fissa fallisce sulla macchina di chi ne ha già una
 * occupata, e fallisce per la ragione sbagliata.
 */
class HttpServerTest {

    private val scope = CoroutineScope(SupervisorJob())
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop()
        scope.cancel()
    }

    private fun avvia(gestisci: suspend (HttpRequest, String) -> HttpResponse): Int {
        val istanza = HttpServer(scope, gestisci = gestisci)
        server = istanza
        return istanza.start(requestedPort = 0)
    }

    /** Manda i byte grezzi e restituisce la risposta intera, così si vede anche ciò che è storto. */
    private fun parla(porta: Int, grezza: String): String =
        Socket(InetAddress.getLoopbackAddress(), porta).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(grezza.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }

    @Test
    fun `un giro completo con un client vero`() {
        val porta = avvia { richiesta, _ -> HttpResponse.testo(200, "ciao ${richiesta.path}") }
        val risposta = parla(porta, "GET /prova HTTP/1.1\r\nHost: x\r\n\r\n")
        assertTrue(risposta.startsWith("HTTP/1.1 200 OK"), risposta)
        assertTrue(risposta.endsWith("ciao /prova"), risposta)
    }

    @Test
    fun `una richiesta malformata riceve 400 e non abbatte il ciclo`() {
        val porta = avvia { _, _ -> HttpResponse.testo(200, "ok") }
        assertTrue(parla(porta, "spazzatura\r\n\r\n").startsWith("HTTP/1.1 400"))
        // La prova vera è questa: dopo la spazzatura il server serve ancora.
        assertTrue(parla(porta, "GET / HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 200"))
    }

    @Test
    fun `una connessione chiusa a meta' non abbatte il ciclo`() {
        val porta = avvia { _, _ -> HttpResponse.testo(200, "ok") }
        Socket(InetAddress.getLoopbackAddress(), porta).use { socket ->
            socket.getOutputStream().write("GET / HTTP".toByteArray())
            socket.getOutputStream().flush()
        } // chiusa senza finire la richiesta
        assertTrue(parla(porta, "GET / HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 200"))
    }

    @Test
    fun `un gestore che lancia produce 500 e lascia vivo il ciclo`() {
        val porta = avvia { richiesta, _ ->
            if (richiesta.path == "/rotto") error("difetto nel gestore") else HttpResponse.testo(200, "ok")
        }
        assertTrue(parla(porta, "GET /rotto HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 500"))
        assertTrue(parla(porta, "GET / HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 200"))
    }

    @Test
    fun `un client che apre e non manda niente non riceve risposta e non blocca gli altri`() {
        val porta = avvia { _, _ -> HttpResponse.testo(200, "ok") }
        val muto = Socket(InetAddress.getLoopbackAddress(), porta)
        try {
            assertTrue(parla(porta, "GET / HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 200"))
        } finally {
            muto.close()
        }
    }

    @Test
    fun `stop libera davvero la porta`() {
        val istanza = HttpServer(scope) { _, _ -> HttpResponse.testo(200, "ok") }
        server = istanza
        val porta = istanza.start(requestedPort = 0)
        istanza.stop()
        server = null
        // Se il socket fosse rimasto appeso, questo `bind` fallirebbe.
        ServerSocket(porta).use { assertEquals(porta, it.localPort) }
    }

    @Test
    fun `cento cicli di avvio e arresto non lasciano socket appesi`() {
        repeat(100) {
            val istanza = HttpServer(scope) { _, _ -> HttpResponse.testo(200, "ok") }
            istanza.start(requestedPort = 0)
            istanza.stop()
        }
        // Se ne fosse rimasto appeso anche solo uno per giro, si esaurirebbero i descrittori.
        val istanza = HttpServer(scope) { _, _ -> HttpResponse.testo(200, "ok") }
        server = istanza
        val porta = istanza.start(requestedPort = 0)
        assertTrue(parla(porta, "GET / HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 200"))
    }

    @Test
    fun `a server fermo la connessione viene rifiutata, non accettata e chiusa`() {
        // È il criterio di US-002: da fuori il servizio non deve sembrare presente ma muto,
        // deve sembrare assente.
        val istanza = HttpServer(scope) { _, _ -> HttpResponse.testo(200, "ok") }
        val porta = istanza.start(requestedPort = 0)
        istanza.stop()
        try {
            Socket(InetAddress.getLoopbackAddress(), porta).use { }
            fail("la connessione doveva essere rifiutata")
        } catch (atteso: IOException) {
            // È questo il comportamento voluto.
        }
    }

    @Test
    fun `start due volte non apre un secondo socket`() {
        val istanza = HttpServer(scope) { _, _ -> HttpResponse.testo(200, "ok") }
        server = istanza
        val prima = istanza.start(requestedPort = 0)
        assertEquals(prima, istanza.start(requestedPort = 0))
        assertEquals(prima, istanza.port)
    }

    /**
     * TC-12 — **una richiesta che resta appesa non blocca le altre.**
     *
     * In teoria non può: il ciclo di `accept` lancia una coroutine per connessione, ed è la ragione
     * per cui l'attesa lunga della feature 005 è entrata in questa architettura senza toccarla. In
     * pratica è esattamente il genere di garanzia che si scopre falsa il giorno in cui qualcuno la
     * dà per buona e mette un `runBlocking` nel posto sbagliato.
     *
     * Niente attese a tempo: la richiesta appesa si sblocca quando lo dice il test.
     */
    @Test
    fun `una connessione appesa non blocca le altre`() {
        val arrivata = CountDownLatch(1)
        val sbloccami = CompletableDeferred<Unit>()
        val porta = avvia { richiesta, _ ->
            if (richiesta.path == "/appesa") {
                arrivata.countDown()
                sbloccami.await()
                HttpResponse.testo(200, "finalmente")
            } else {
                HttpResponse.testo(200, "subito")
            }
        }

        Socket(InetAddress.getLoopbackAddress(), porta).use { appesa ->
            appesa.soTimeout = 5_000
            appesa.getOutputStream().write("GET /appesa HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            appesa.getOutputStream().flush()
            assertTrue(arrivata.await(5, TimeUnit.SECONDS), "la richiesta appesa non è arrivata")

            // Il momento della verità: la prima è ferma dentro il gestore, la seconda deve passare.
            assertTrue(parla(porta, "GET /veloce HTTP/1.1\r\n\r\n").endsWith("subito"))

            sbloccami.complete(Unit)
            val risposta = appesa.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(risposta.endsWith("finalmente"), risposta)
        }
    }
}

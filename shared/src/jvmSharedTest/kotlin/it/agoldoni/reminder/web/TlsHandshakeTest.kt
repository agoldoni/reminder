package it.agoldoni.reminder.web

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * TC-09/10/11/12 — **la prova centrale della feature 003**.
 *
 * Tutti gli altri test guardano il certificato dall'interno: questo lo mette davanti a
 * un'implementazione TLS vera e le chiede di negoziare. È l'unica prova che dice qualcosa di
 * definitivo sul codificatore DER scritto a mano, perché confrontare i byte prodotti con byte
 * attesi congelerebbe gli errori invece di trovarli — mentre un handshake JSSE riuscito significa
 * che il certificato è valido per definizione operativa.
 *
 * Il resto del test guarda l'altra metà del problema: che il ciclo di ascolto **sopravviva** a chi
 * non riesce a parlargli. Su una porta esposta in rete arriva di tutto, e sopra TLS «di tutto»
 * comprende scanner che parlano in chiaro e client che rifiutano il certificato.
 */
class TlsHandshakeTest {

    private val scope = CoroutineScope(SupervisorJob())
    private var server: HttpServer? = null
    private val cartella = File.createTempFile("promemoria-handshake", "").let {
        it.delete()
        File(it.absolutePath)
    }

    @AfterTest
    fun pulisci() {
        server?.stop()
        scope.cancel()
        cartella.listFiles()?.forEach { it.delete() }
        cartella.delete()
    }

    private val identita: TlsIdentity by lazy {
        CertificateStore(cartella).caricaOCrea { listOf(InetAddress.getLoopbackAddress()) }
    }

    private fun avvia(): Int {
        val istanza = HttpServer(scope, apriSocket = identita::apriSocket) { richiesta, _ ->
            HttpResponse.testo(200, "ciao ${richiesta.path}")
        }
        server = istanza
        return istanza.start(requestedPort = 0)
    }

    /** Si fida **solo** del certificato passato: è la posizione di un browser che l'ha accettato. */
    private fun clienteCheSiFida(certificato: X509Certificate): SSLContext {
        val archivio = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("server", certificato)
        }
        val fiducia = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(archivio) }
        return SSLContext.getInstance("TLS").apply { init(null, fiducia.trustManagers, null) }
    }

    /**
     * Si fida di **un altro** certificato, non del nostro: è la posizione di un browser appena
     * arrivato, che ha un archivio pieno di autorità pubbliche fra cui la nostra non c'è.
     *
     * Un archivio *vuoto* sembrerebbe più diretto e non lo è: PKIX lo rifiuta con
     * `InvalidAlgorithmParameterException` prima ancora di aprire la connessione, e il test
     * proverebbe che una configurazione impossibile è impossibile invece che il rifiuto vero.
     */
    private fun clienteCheSiFidaDiAltri(): SSLContext {
        val estraneo = SelfSignedCertificate.genera(
            "Qualcun altro",
            listOf(InetAddress.getLoopbackAddress()),
            1_755_000_000_000L,
            3650
        )
        return clienteCheSiFida(estraneo.certificato)
    }

    private fun parla(
        porta: Int,
        contesto: SSLContext,
        verificaIdentita: Boolean = false
    ): String {
        val socket = contesto.socketFactory.createSocket("127.0.0.1", porta) as SSLSocket
        return socket.use {
            it.soTimeout = 10_000
            if (verificaIdentita) {
                it.sslParameters = it.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            }
            it.outputStream.write("GET /prova HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            it.outputStream.flush()
            it.inputStream.readBytes().toString(Charsets.ISO_8859_1)
        }
    }

    @Test
    fun `un giro completo cifrato con un client che si fida`() {
        val porta = avvia()
        val risposta = parla(porta, clienteCheSiFida(identita.certificato))

        assertTrue(risposta.startsWith("HTTP/1.1 200 OK"), risposta)
        assertTrue(risposta.endsWith("ciao /prova"), risposta)
    }

    @Test
    fun `la verifica dell'identità sull'indirizzo passa`() {
        // `endpointIdentificationAlgorithm = "HTTPS"` è **lo stesso controllo che fa un browser**:
        // confronta l'indirizzo digitato con il subjectAltName. Se il nostro `[7] IMPLICIT` fosse
        // codificato male, il certificato si caricherebbe lo stesso e questo fallirebbe.
        val porta = avvia()
        val risposta = parla(porta, clienteCheSiFida(identita.certificato), verificaIdentita = true)

        assertTrue(risposta.startsWith("HTTP/1.1 200 OK"), risposta)
    }

    @Test
    fun `si negozia un protocollo moderno e una suite ECDSA`() {
        val porta = avvia()
        val socket = clienteCheSiFida(identita.certificato)
            .socketFactory.createSocket("127.0.0.1", porta) as SSLSocket

        socket.use {
            it.startHandshake()
            assertTrue(
                it.session.protocol in setOf("TLSv1.2", "TLSv1.3"),
                "protocollo negoziato: ${it.session.protocol}"
            )
            assertTrue(
                "ECDSA" in it.session.cipherSuite || it.session.protocol == "TLSv1.3",
                "con una chiave P-256 la suite deve essere ECDSA: ${it.session.cipherSuite}"
            )
        }
    }

    @Test
    fun `TLS 1_0 e 1_1 non sono negoziabili`() {
        // Verificato sul dispositivo che l'elenco predefinito di Android li comprende: se
        // `apriSocket` smettesse di restringerli, questo test se ne accorgerebbe.
        val porta = avvia()
        val contesto = clienteCheSiFida(identita.certificato)
        val socket = contesto.socketFactory.createSocket("127.0.0.1", porta) as SSLSocket

        assertFailsWith<IOException> {
            socket.use {
                it.enabledProtocols = arrayOf("TLSv1")
                it.startHandshake()
            }
        }
    }

    @Test
    fun `un client che non si fida fallisce, e il server continua a servire`() {
        val porta = avvia()

        assertFailsWith<SSLHandshakeException> {
            parla(porta, clienteCheSiFidaDiAltri())
        }

        // La prova vera è questa: il rifiuto riguarda una connessione, non il ciclo.
        val risposta = parla(porta, clienteCheSiFida(identita.certificato))
        assertTrue(risposta.startsWith("HTTP/1.1 200 OK"), risposta)
    }

    @Test
    fun `un client in chiaro contro la porta cifrata non ottiene contenuto`() {
        val porta = avvia()

        val inChiaro = Socket(InetAddress.getLoopbackAddress(), porta).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET /prova HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            runCatching { socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1) }
                .getOrDefault("")
        }

        assertTrue("ciao" !in inChiaro, "non deve uscire nulla di applicativo: $inChiaro")
        assertTrue("HTTP/1.1 200" !in inChiaro, "e nemmeno una risposta HTTP: $inChiaro")

        val risposta = parla(porta, clienteCheSiFida(identita.certificato))
        assertTrue(risposta.startsWith("HTTP/1.1 200 OK"), risposta)
    }

    @Test
    fun `cento handshake falliti non impediscono il centounesimo riuscito`() {
        val porta = avvia()
        val nonSiFida = clienteCheSiFidaDiAltri()

        repeat(100) {
            runCatching { parla(porta, nonSiFida) }
        }

        val risposta = parla(porta, clienteCheSiFida(identita.certificato))
        assertTrue(risposta.startsWith("HTTP/1.1 200 OK"), risposta)
    }

    @Test
    fun `stop libera davvero la porta`() {
        val porta = avvia()
        parla(porta, clienteCheSiFida(identita.certificato))

        server?.stop()

        val errore = assertFailsWith<IOException> {
            Socket(InetAddress.getLoopbackAddress(), porta).close()
        }
        assertEquals(true, errore.message?.isNotEmpty(), "connessione rifiutata, non accettata")
    }
}

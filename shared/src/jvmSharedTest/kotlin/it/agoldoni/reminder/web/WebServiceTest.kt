package it.agoldoni.reminder.web

import it.agoldoni.reminder.data.FakeEventDao
import it.agoldoni.reminder.platform.AppSettings
import it.agoldoni.reminder.sync.RecordingAlarmScheduler
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class SettingsDiProva(web: Boolean = false) : AppSettings {
    private val _sync = MutableStateFlow(false)
    override val syncEnabled: StateFlow<Boolean> = _sync.asStateFlow()
    override fun setSyncEnabled(enabled: Boolean) { _sync.value = enabled }

    private val _web = MutableStateFlow(web)
    override val webEnabled: StateFlow<Boolean> = _web.asStateFlow()
    override fun setWebEnabled(enabled: Boolean) { _web.value = enabled }
}

/** Registra le richieste al custode: su Android è il servizio in primo piano. */
private class CustodeDiProva(private val rifiuta: Boolean = false) : ProcessKeeper {
    val richieste = mutableListOf<Boolean>()
    val vivo: Boolean get() = richieste.lastOrNull() == true
    override fun keepAlive(active: Boolean) {
        richieste += active
        if (rifiuta && active) error("il sistema ha rifiutato il servizio in primo piano")
    }
}

/** TC-11/12/13/14 — l'interruttore, chi tiene viva la porta e la sorte del token. */
class WebServiceTest {

    private val scope = CoroutineScope(SupervisorJob())

    /**
     * Chiave e certificato del server vivono qui. Una cartella per esecuzione, così un test non
     * eredita l'identità di un altro — e la stessa cartella fra due `servizio()` dello stesso
     * test, perché è proprio quella continuità che il servizio deve garantire.
     */
    private val cartella = File.createTempFile("promemoria-web", "").let {
        it.delete()
        File(it.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        cartella.listFiles()?.forEach { it.delete() }
        cartella.delete()
    }

    private fun servizio(
        settings: AppSettings = SettingsDiProva(),
        custode: ProcessKeeper = CustodeDiProva()
    ) = WebService(
        dao = FakeEventDao(),
        alarms = RecordingAlarmScheduler(),
        deviceId = "telefono-di-prova",
        settings = settings,
        scope = scope,
        // Porta a zero: la sceglie il sistema, così il test non dipende da una porta libera.
        port = 0,
        cartellaCertificato = cartella,
        indirizzoLocale = { "192.168.1.42" },
        keeper = custode
    )

    private fun raggiungibile(porta: Int): Boolean = try {
        Socket(InetAddress.getLoopbackAddress(), porta).use { true }
    } catch (rifiutata: IOException) {
        false
    }

    @Test
    fun `a interruttore spento non si apre nessun socket e non si disturba il custode`() {
        val custode = CustodeDiProva()
        val web = servizio(custode = custode)
        web.resume()
        assertFalse(web.status.value.listening)
        assertNull(web.status.value.port)
        assertNull(web.status.value.tokenLettura)
        assertNull(web.status.value.urlLettura)
        assertTrue(custode.richieste.isEmpty())
    }

    @Test
    fun `accendere apre la porta, pubblica l'indirizzo e ingaggia il custode`() {
        val custode = CustodeDiProva()
        val web = servizio(custode = custode)
        web.enable()

        val stato = web.status.value
        assertTrue(stato.enabled)
        assertTrue(stato.listening)
        assertNotNull(stato.port)
        assertEquals("192.168.1.42", stato.host)
        assertNotNull(stato.tokenLettura)
        assertEquals("https://192.168.1.42:${stato.port}/?t=${stato.tokenLettura}", stato.urlLettura)
        assertTrue(raggiungibile(stato.port!!))
        assertTrue(custode.vivo, "senza custode la porta morirebbe appena l'app va in background")
        web.disable()
    }

    @Test
    fun `la porta non dipende piu' dall'app in primo piano`() {
        // È il cambio rispetto alla prima versione: nessun `onBackground` la chiude, perché è il
        // custode a tenere vivo il processo.
        val web = servizio()
        web.enable()
        val porta = web.status.value.port!!
        val token = web.status.value.tokenLettura
        repeat(5) { web.resume() } // come se l'app andasse e venisse dal primo piano
        assertTrue(web.status.value.listening)
        assertEquals(porta, web.status.value.port, "la porta non si è mai chiusa")
        assertEquals(token, web.status.value.tokenLettura, "e il token non è cambiato")
        assertTrue(raggiungibile(porta))
        web.disable()
    }

    @Test
    fun `resume su una porta gia' aperta non richiama il custode`() {
        // Altrimenti si innescherebbe un andirivieni con chi il custode lo ha appena avviato.
        val custode = CustodeDiProva()
        val web = servizio(custode = custode)
        web.enable()
        val richiesteDopoAccensione = custode.richieste.size
        repeat(3) { web.resume() }
        assertEquals(richiesteDopoAccensione, custode.richieste.size)
        web.disable()
    }

    @Test
    fun `spegnere chiude la porta, congeda il custode e invalida il token`() {
        val custode = CustodeDiProva()
        val web = servizio(custode = custode)
        web.enable()
        val porta = web.status.value.port!!
        val vecchio = web.status.value.tokenLettura!!

        web.disable()
        assertFalse(web.status.value.enabled)
        assertFalse(web.status.value.listening)
        assertNull(web.status.value.tokenLettura)
        assertFalse(custode.vivo, "la notifica non deve sopravvivere alla porta")
        assertFalse(raggiungibile(porta), "a porta chiusa la connessione va rifiutata")

        web.enable()
        assertNotEquals(vecchio, web.status.value.tokenLettura, "un indirizzo copiato prima non deve valere")
        web.disable()
    }

    @Test
    fun `il custode si congeda prima che la porta si chiuda`() {
        // L'ordine inverso lascerebbe per un istante una notifica che dichiara aperta una porta
        // già chiusa.
        val custode = CustodeDiProva()
        val web = servizio(custode = custode)
        web.enable()
        web.disable()
        assertEquals(listOf(true, false), custode.richieste)
    }

    @Test
    fun `all'avvio con l'interruttore gia' acceso la porta si riapre da sola`() {
        val custode = CustodeDiProva()
        val web = servizio(SettingsDiProva(web = true), custode)
        assertTrue(web.status.value.enabled)
        web.resume()
        assertTrue(web.status.value.listening)
        assertNotNull(web.status.value.tokenLettura, "un token serve anche quando nessuno ha premuto nulla")
        assertTrue(custode.vivo)
        web.disable()
    }

    @Test
    fun `un custode che rifiuta lascia la porta aperta ma lo dice`() {
        // Da Android 12 un servizio in primo piano avviato mentre l'app non è davanti viene
        // respinto. Non è un motivo per chiudere la porta, ma l'utente deve sapere che non
        // reggerà a schermo spento invece di scoprirlo quando il browser smette di rispondere.
        val web = servizio(custode = CustodeDiProva(rifiuta = true))
        web.enable()
        assertTrue(web.status.value.listening, "finché l'app è aperta funziona lo stesso")
        val messaggio = web.status.value.lastMessage
        assertNotNull(messaggio)
        assertTrue(messaggio.contains("solo con l'app in primo piano"), messaggio)
        web.disable()
    }

    @Test
    fun `una porta occupata diventa un messaggio in italiano, non un'eccezione`() {
        val occupante = java.net.ServerSocket(0)
        try {
            val custode = CustodeDiProva()
            val web = WebService(
                dao = FakeEventDao(),
                alarms = RecordingAlarmScheduler(),
                deviceId = "telefono-di-prova",
                settings = SettingsDiProva(),
                scope = scope,
                port = occupante.localPort,
                cartellaCertificato = cartella,
                indirizzoLocale = { "192.168.1.42" },
                keeper = custode
            )
            web.enable() // non deve lanciare
            assertFalse(web.status.value.listening)
            assertFalse(custode.vivo, "niente notifica per una porta che non si è aperta")
            val messaggio = web.status.value.lastMessage
            assertNotNull(messaggio, "l'utente deve poter leggere perché non ha funzionato")
            assertTrue(messaggio.startsWith("Impossibile aprire la porta"), messaggio)
        } finally {
            occupante.close()
        }
    }

    @Test
    fun `il servizio inerte del desktop non fa niente e lo dichiara`() {
        assertFalse(WebServerNonDisponibile.supported)
        WebServerNonDisponibile.enable()
        WebServerNonDisponibile.resume()
        assertFalse(WebServerNonDisponibile.status.value.enabled)
        assertNull(WebServerNonDisponibile.status.value.urlLettura)
    }

    @Test
    fun `l'indirizzo non si mostra finche' non si ascolta davvero`() {
        val stato = WebStatus(
            enabled = true,
            listening = false,
            host = "1.2.3.4",
            port = 9888,
            tokenLettura = "abc",
            tokenScrittura = "xyz"
        )
        assertNull(stato.urlLettura, "un indirizzo mostrato mentre non si ascolta manda contro un muro")
        assertNull(stato.urlScrittura, "vale per tutti e due")
    }

    @Test
    fun `l'impronta si mostra solo mentre si ascolta, e non cambia spegnendo e riaccendendo`() {
        val web = servizio()
        assertNull(web.status.value.impronta, "a porta chiusa non c'è niente da confrontare")

        web.enable()
        val prima = web.status.value.impronta
        assertNotNull(prima)
        assertEquals(95, prima.length, "32 coppie esadecimali e 31 separatori")

        web.disable()
        assertNull(web.status.value.impronta)

        // Il punto: spegnere invalida il **token**, non l'identità del server. Se il certificato
        // cambiasse qui, l'utente si ritroverebbe l'avviso del browser a ogni riaccensione.
        web.enable()
        assertEquals(prima, web.status.value.impronta)
        web.disable()
    }

    @Test
    fun `un servizio ricostruito sulla stessa cartella presenta lo stesso certificato`() {
        // È il caso reale: il processo dell'app viene ucciso e `Application.onCreate()` ricostruisce
        // tutto da capo.
        val primo = servizio().apply { enable() }
        val impronta = primo.status.value.impronta
        primo.disable()

        val dopoIlRiavvio = servizio().apply { enable() }
        assertEquals(impronta, dopoIlRiavvio.status.value.impronta)
        dopoIlRiavvio.disable()
    }

    @Test
    fun `se il certificato non si puo' preparare, la porta resta chiusa e lo dice`() {
        // Cartella impossibile da creare: al posto della cartella c'è un file.
        cartella.mkdirs()
        val ostacolo = File(cartella, "ostacolo").apply { writeText("non sono una cartella") }
        val web = WebService(
            dao = FakeEventDao(),
            alarms = RecordingAlarmScheduler(),
            deviceId = "telefono-di-prova",
            settings = SettingsDiProva(),
            scope = scope,
            port = 0,
            cartellaCertificato = File(ostacolo, "sotto"),
            indirizzoLocale = { "192.168.1.42" }
        )

        web.enable()

        assertFalse(web.status.value.listening, "senza certificato non si apre nulla")
        assertNull(web.status.value.urlLettura)
        val messaggio = web.status.value.lastMessage
        assertNotNull(messaggio)
        assertTrue(
            "certificato" in messaggio,
            "il messaggio deve nominare la causa vera, non la porta: $messaggio"
        )
    }

    /**
     * Si fida di chiunque. In un test va bene e altrove no: qui serve a mettersi nella posizione
     * dell'utente che ha scavalcato l'avviso del browser, che è esattamente ciò che accetta
     * qualunque certificato. Il rifiuto vero è provato da `TlsHandshakeTest`.
     */
    private fun clienteCheScavalcaLAvviso(): javax.net.ssl.SSLContext {
        val permissivo = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) = Unit
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) = Unit
            override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
        }
        return javax.net.ssl.SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf(permissivo), java.security.SecureRandom()) }
    }

    private fun chiedi(porta: Int, percorso: String): String {
        val socket = clienteCheScavalcaLAvviso().socketFactory
            .createSocket("127.0.0.1", porta) as javax.net.ssl.SSLSocket
        return socket.use {
            it.soTimeout = 10_000
            it.outputStream.write("GET $percorso HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            it.outputStream.flush()
            it.inputStream.readBytes().toString(Charsets.ISO_8859_1)
        }
    }

    @Test
    fun `un giro completo sopra TLS, dal token alla pagina`() {
        // La prova che lega tutto: non un gestore finto come in `TlsHandshakeTest`, ma il servizio
        // vero — token, smistamento, asset — raggiunto cifrato come lo raggiungerebbe un browser.
        val web = servizio()
        web.enable()
        val stato = web.status.value
        val porta = stato.port!!
        val token = stato.tokenLettura!!

        try {
            val pagina = chiedi(porta, "/?t=$token")
            assertTrue(pagina.startsWith("HTTP/1.1 200 OK"), pagina.take(120))
            assertTrue("<!DOCTYPE html>" in pagina || "<html" in pagina, pagina.take(200))

            val dati = chiedi(porta, "/api/eventi?t=$token")
            assertTrue(dati.startsWith("HTTP/1.1 200 OK"), dati.take(120))
            assertTrue("application/json" in dati, dati.take(200))

            // Il token continua a decidere chi entra: TLS cifra, non autorizza.
            val senzaToken = chiedi(porta, "/api/eventi")
            assertTrue(senzaToken.startsWith("HTTP/1.1 403"), senzaToken.take(120))
        } finally {
            web.disable()
        }
    }
}

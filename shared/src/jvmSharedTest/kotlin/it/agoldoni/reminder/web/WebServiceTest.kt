package it.agoldoni.reminder.web

import it.agoldoni.reminder.data.FakeEventDao
import it.agoldoni.reminder.platform.AppSettings
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

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun servizio(
        settings: AppSettings = SettingsDiProva(),
        custode: ProcessKeeper = CustodeDiProva()
    ) = WebService(
        dao = FakeEventDao(),
        settings = settings,
        scope = scope,
        // Porta a zero: la sceglie il sistema, così il test non dipende da una porta libera.
        port = 0,
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
        assertNull(web.status.value.token)
        assertNull(web.status.value.url)
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
        assertNotNull(stato.token)
        assertEquals("http://192.168.1.42:${stato.port}/?t=${stato.token}", stato.url)
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
        val token = web.status.value.token
        repeat(5) { web.resume() } // come se l'app andasse e venisse dal primo piano
        assertTrue(web.status.value.listening)
        assertEquals(porta, web.status.value.port, "la porta non si è mai chiusa")
        assertEquals(token, web.status.value.token, "e il token non è cambiato")
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
        val vecchio = web.status.value.token!!

        web.disable()
        assertFalse(web.status.value.enabled)
        assertFalse(web.status.value.listening)
        assertNull(web.status.value.token)
        assertFalse(custode.vivo, "la notifica non deve sopravvivere alla porta")
        assertFalse(raggiungibile(porta), "a porta chiusa la connessione va rifiutata")

        web.enable()
        assertNotEquals(vecchio, web.status.value.token, "un indirizzo copiato prima non deve valere")
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
        assertNotNull(web.status.value.token, "un token serve anche quando nessuno ha premuto nulla")
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
                settings = SettingsDiProva(),
                scope = scope,
                port = occupante.localPort,
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
        assertNull(WebServerNonDisponibile.status.value.url)
    }

    @Test
    fun `l'indirizzo non si mostra finche' non si ascolta davvero`() {
        val stato = WebStatus(enabled = true, listening = false, host = "1.2.3.4", port = 9888, token = "abc")
        assertNull(stato.url, "un indirizzo mostrato mentre non si ascolta manda contro un muro")
    }
}

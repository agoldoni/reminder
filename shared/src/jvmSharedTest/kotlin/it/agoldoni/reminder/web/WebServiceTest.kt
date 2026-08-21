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
import kotlin.test.fail

private class SettingsDiProva(web: Boolean = false) : AppSettings {
    private val _sync = MutableStateFlow(false)
    override val syncEnabled: StateFlow<Boolean> = _sync.asStateFlow()
    override fun setSyncEnabled(enabled: Boolean) { _sync.value = enabled }

    private val _web = MutableStateFlow(web)
    override val webEnabled: StateFlow<Boolean> = _web.asStateFlow()
    override fun setWebEnabled(enabled: Boolean) { _web.value = enabled }
}

/** TC-11/12/13/14 — l'interruttore, il ciclo di vita e la sorte del token. */
class WebServiceTest {

    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun servizio(settings: AppSettings = SettingsDiProva()) = WebService(
        dao = FakeEventDao(),
        settings = settings,
        scope = scope,
        // Porta a zero: la sceglie il sistema, così il test non dipende da una porta libera.
        port = 0,
        indirizzoLocale = { "192.168.1.42" }
    )

    private fun raggiungibile(porta: Int): Boolean = try {
        Socket(InetAddress.getLoopbackAddress(), porta).use { true }
    } catch (rifiutata: IOException) {
        false
    }

    @Test
    fun `a interruttore spento non si apre nessun socket`() {
        val web = servizio()
        web.onForeground()
        assertFalse(web.status.value.listening)
        assertNull(web.status.value.port)
        assertNull(web.status.value.token)
        assertNull(web.status.value.url)
    }

    @Test
    fun `accendere con l'app davanti apre la porta e pubblica l'indirizzo completo`() {
        val web = servizio()
        web.onForeground()
        web.enable()

        val stato = web.status.value
        assertTrue(stato.enabled)
        assertTrue(stato.listening)
        assertNotNull(stato.port)
        assertEquals("192.168.1.42", stato.host)
        assertNotNull(stato.token)
        assertEquals("http://192.168.1.42:${stato.port}/?t=${stato.token}", stato.url)
        assertTrue(raggiungibile(stato.port!!))
        web.disable()
    }

    @Test
    fun `accendere ad app in background non apre niente, ma resta acceso`() {
        val web = servizio()
        web.enable() // nessun onForeground: l'app non è davanti
        assertTrue(web.status.value.enabled, "l'interruttore resta acceso")
        assertFalse(web.status.value.listening, "ma non si ascolta")
        assertNull(web.status.value.url, "e non si mostra un indirizzo che non risponderebbe")
    }

    @Test
    fun `spegnere chiude la porta e invalida il token`() {
        val web = servizio()
        web.onForeground()
        web.enable()
        val porta = web.status.value.port!!
        val vecchio = web.status.value.token!!

        web.disable()
        assertFalse(web.status.value.enabled)
        assertFalse(web.status.value.listening)
        assertNull(web.status.value.token)
        assertFalse(raggiungibile(porta), "a porta chiusa la connessione va rifiutata")

        // E il token non deve tornare buono riaccendendo.
        web.enable()
        assertNotEquals(vecchio, web.status.value.token)
        web.disable()
    }

    @Test
    fun `il background chiude la porta ma conserva il token`() {
        // È il criterio che protegge dalla rotazione dello schermo: un giro completo di
        // onStop/onStart non deve invalidare l'indirizzo già digitato sull'altro dispositivo.
        val web = servizio()
        web.onForeground()
        web.enable()
        val token = web.status.value.token!!
        val host = web.status.value.host

        web.onBackground()
        assertFalse(web.status.value.listening)
        assertTrue(web.status.value.enabled, "non è stato l'utente a spegnere")

        web.onForeground()
        assertTrue(web.status.value.listening)
        assertEquals(token, web.status.value.token, "il token doveva sopravvivere")
        assertEquals(host, web.status.value.host, "l'indirizzo doveva restare lo stesso")
        // Non si confronta l'URL intero: qui la porta la chiede al sistema (`port = 0`) e a ogni
        // riapertura ne arriva una diversa. In esercizio la porta è fissa, quindi con host e token
        // invariati l'URL è invariato — che è ciò che il criterio di US-007 chiede davvero.
        assertNotNull(web.status.value.url)
        web.disable()
    }

    @Test
    fun `molti giri fra primo piano e background non lasciano niente appeso`() {
        val web = servizio()
        web.onForeground()
        web.enable()
        val token = web.status.value.token
        repeat(30) {
            web.onBackground()
            web.onForeground()
        }
        assertTrue(web.status.value.listening)
        assertEquals(token, web.status.value.token)
        assertTrue(raggiungibile(web.status.value.port!!))
        web.disable()
    }

    @Test
    fun `all'avvio con l'interruttore gia' acceso si riapre da soli`() {
        // Lo stato dell'interruttore è persistito: alla riapertura dell'app deve valere.
        val web = servizio(SettingsDiProva(web = true))
        assertTrue(web.status.value.enabled)
        web.onForeground()
        assertTrue(web.status.value.listening)
        assertNotNull(web.status.value.token, "un token serve anche quando nessuno ha premuto nulla")
        web.disable()
    }

    @Test
    fun `una porta occupata diventa un messaggio in italiano, non un'eccezione`() {
        val occupante = java.net.ServerSocket(0)
        try {
            val web = WebService(
                dao = FakeEventDao(),
                settings = SettingsDiProva(),
                scope = scope,
                port = occupante.localPort,
                indirizzoLocale = { "192.168.1.42" }
            )
            web.onForeground()
            web.enable() // non deve lanciare
            assertFalse(web.status.value.listening)
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
        WebServerNonDisponibile.onForeground()
        assertFalse(WebServerNonDisponibile.status.value.enabled)
        assertNull(WebServerNonDisponibile.status.value.url)
    }

    @Test
    fun `l'indirizzo non si mostra finche' non si ascolta davvero`() {
        val stato = WebStatus(enabled = true, listening = false, host = "1.2.3.4", port = 9888, token = "abc")
        assertNull(stato.url, "un indirizzo mostrato mentre non si ascolta manda contro un muro")
    }
}

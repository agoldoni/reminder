package it.agoldoni.reminder.web

import it.agoldoni.reminder.data.EventDao
import it.agoldoni.reminder.platform.AppSettings
import it.agoldoni.reminder.platform.nowMillis
import it.agoldoni.reminder.sync.siteAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mette insieme i pezzi della web app locale: interruttore, socket, token e stato mostrabile.
 *
 * **Vive quanto il processo, non quanto una schermata.** È costruito dall'`Application` e riceve
 * `onForeground()`/`onBackground()` dall'`Activity`. Se socket e token fossero legati a una
 * schermata o a una Activity, una rotazione dello schermo — che è un giro completo di
 * `onStop`/`onStart` — rigenererebbe il token, e l'indirizzo già digitato sull'altro dispositivo
 * smetterebbe di funzionare senza che l'utente abbia toccato niente.
 */
class WebService(
    dao: EventDao,
    private val settings: AppSettings,
    scope: CoroutineScope,
    /** Porta di ascolto; a zero la sceglie il sistema, cosa che serve solo ai test. */
    private val port: Int = WEB_PORT,
    now: () -> Long = ::nowMillis,
    /** Come si ricava l'indirizzo da mostrare; sostituibile nei test. */
    private val indirizzoLocale: () -> String? = { runCatching { siteAddress().hostAddress }.getOrNull() }
) : WebServerController {

    private val token = AccessToken(now)
    private val router = Router(dao, token)
    private val server = HttpServer(scope) { richiesta, chi -> router.gestisci(richiesta, chi) }

    private val _status = MutableStateFlow(WebStatus(enabled = settings.webEnabled.value))
    override val status: StateFlow<WebStatus> = _status.asStateFlow()

    override val supported: Boolean = true

    /**
     * Vero mentre l'app è in primo piano. Android non lascia tenere un socket in ascolto ad app
     * chiusa: è la stessa ragione per cui la sincronizzazione, sul telefono, ascolta solo a
     * schermata aperta.
     */
    @Volatile
    private var inPrimoPiano = false

    override fun enable() {
        settings.setWebEnabled(true)
        // Token nuovo a ogni accensione: è questo a rendere accettabile che viaggi nell'URL,
        // perché un indirizzo copiato la volta scorsa smette di funzionare.
        token.rigenera()
        _status.value = _status.value.copy(enabled = true, lastMessage = null)
        if (inPrimoPiano) apri()
    }

    override fun disable() {
        settings.setWebEnabled(false)
        chiudi()
        // Spegnere non è mettere in pausa: il token va invalidato, non conservato.
        token.invalida()
        _status.value = _status.value.copy(enabled = false, token = null, lastMessage = null)
    }

    override fun onForeground() {
        inPrimoPiano = true
        if (settings.webEnabled.value) apri()
    }

    override fun onBackground() {
        inPrimoPiano = false
        // Il token **sopravvive**: chi ha già digitato l'indirizzo deve ritrovarlo valido quando
        // l'app torna davanti, altrimenti ogni rotazione dello schermo lo costringerebbe a
        // ridigitarlo.
        chiudi()
    }

    private fun apri() {
        if (_status.value.listening) return
        // Al primo avvio del processo con l'interruttore già acceso non c'è ancora un token.
        if (token.valore == null) token.rigenera()
        runCatching { server.start(port) }
            .onSuccess { portaEffettiva ->
                _status.value = _status.value.copy(
                    listening = true,
                    // La porta **effettiva**: con `port = 0` nei test non è quella richiesta, e
                    // mostrare quella richiesta manderebbe chi digita contro un muro.
                    port = portaEffettiva,
                    host = indirizzoLocale(),
                    token = token.valore,
                    lastMessage = null
                )
            }
            .onFailure { errore ->
                _status.value = _status.value.copy(
                    listening = false,
                    port = null,
                    host = null,
                    token = null,
                    lastMessage = "Impossibile aprire la porta $port: " +
                        "${errore.message ?: "porta occupata"}."
                )
            }
    }

    private fun chiudi() {
        server.stop()
        _status.value = _status.value.copy(listening = false, port = null, host = null)
    }
}

package it.agoldoni.reminder.web

import it.agoldoni.reminder.data.EventDao
import it.agoldoni.reminder.platform.AppSettings
import it.agoldoni.reminder.platform.nowMillis
import it.agoldoni.reminder.sync.siteAddress
import java.io.File
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mette insieme i pezzi della web app locale: interruttore, socket, token e stato mostrabile.
 *
 * **Vive quanto il processo, non quanto una schermata.** È costruito dall'`Application`, e finché
 * l'interruttore è acceso la porta resta aperta anche ad app chiusa: a tenere vivo il processo ci
 * pensa il [ProcessKeeper]. Se socket e token fossero legati a una schermata o a una Activity, una
 * rotazione dello schermo — che è un giro completo di `onStop`/`onStart` — rigenererebbe il token,
 * e l'indirizzo già digitato sull'altro dispositivo smetterebbe di funzionare senza che l'utente
 * abbia toccato niente.
 */
class WebService(
    dao: EventDao,
    private val settings: AppSettings,
    scope: CoroutineScope,
    /** Porta di ascolto; a zero la sceglie il sistema, cosa che serve solo ai test. */
    private val port: Int = WEB_PORT,
    now: () -> Long = ::nowMillis,
    /**
     * Dove vivono chiave e certificato del server. Non ha un valore predefinito di proposito: un
     * percorso sbagliato non darebbe un errore ma un certificato **rigenerato a ogni avvio**, e
     * l'utente si ritroverebbe l'avviso del browser ogni volta senza capire perché.
     */
    cartellaCertificato: File,
    /** Come si ricava l'indirizzo da mostrare; sostituibile nei test. */
    private val indirizzoLocale: () -> String? = { runCatching { siteAddress().hostAddress }.getOrNull() },
    /** Chi tiene vivo il processo. Fuori da Android non serve nessuno. */
    private val keeper: ProcessKeeper = ProcessKeeper { }
) : WebServerController {

    private val token = AccessToken(now)
    private val router = Router(dao, token)
    private val certificati = CertificateStore(cartellaCertificato, now = now)

    /**
     * Il socket lo apre l'identità TLS, non `HttpServer`, che di TLS resta ignaro. La lambda
     * risolve l'identità **al momento dell'apertura** e non alla costruzione: qui siamo
     * nell'inizializzazione dell'oggetto, che su Android avviene in `Application.onCreate()`, e
     * leggere o generare un certificato lì rallenterebbe l'avvio dell'app anche quando
     * l'interruttore è spento e non serve a nulla. `caricaOCrea` tiene la propria cache, quindi
     * riaperture successive non ripagano il costo.
     */
    private val server = HttpServer(scope, apriSocket = { porta -> identita().apriSocket(porta) }) {
        richiesta, chi ->
        router.gestisci(richiesta, chi)
    }

    private fun identita(): TlsIdentity = certificati.caricaOCrea {
        // Solo per il `subjectAltName`, che nessuno verificherà: il loopback lo aggiunge
        // `CertificateStore` da sé, perché serve alla prova via `adb forward`.
        listOfNotNull(indirizzoLocale()?.let { runCatching { InetAddress.getByName(it) }.getOrNull() })
    }

    private val _status = MutableStateFlow(WebStatus(enabled = settings.webEnabled.value))
    override val status: StateFlow<WebStatus> = _status.asStateFlow()

    override val supported: Boolean = true

    override fun enable() {
        settings.setWebEnabled(true)
        // Token nuovo a ogni accensione: è questo a rendere accettabile che viaggi nell'URL,
        // perché un indirizzo copiato la volta scorsa smette di funzionare.
        token.rigenera()
        _status.value = _status.value.copy(enabled = true, lastMessage = null)
        apri()
        if (_status.value.listening) ingaggiaCustode()
    }

    override fun disable() {
        settings.setWebEnabled(false)
        // Prima si congeda il custode e poi si chiude: l'ordine inverso lascerebbe per un istante
        // una notifica che dichiara aperta una porta già chiusa.
        runCatching { keeper.keepAlive(false) }
        chiudi()
        // Spegnere non è mettere in pausa: il token va invalidato, non conservato.
        token.invalida()
        _status.value = _status.value.copy(enabled = false, token = null, lastMessage = null)
    }

    override fun resume() {
        if (!settings.webEnabled.value) return
        // Già in ascolto: non c'è niente da riaprire, e richiamare il custode qui produrrebbe un
        // andirivieni con chi lo ha appena avviato.
        if (_status.value.listening) return
        apri()
        if (_status.value.listening) ingaggiaCustode()
    }

    /**
     * Il custode può rifiutarsi: da Android 12 un servizio in primo piano avviato mentre l'app non
     * è davanti viene respinto dal sistema. Non è un motivo per chiudere la porta — finché l'app
     * resta aperta funziona lo stesso — ma l'utente deve sapere che non reggerà a schermo spento,
     * invece di scoprirlo quando il browser smette di rispondere.
     */
    private fun ingaggiaCustode() {
        runCatching { keeper.keepAlive(true) }.onFailure { errore ->
            _status.value = _status.value.copy(
                lastMessage = "La porta è aperta, ma resterà raggiungibile solo con l'app in " +
                    "primo piano: ${errore.message ?: "il sistema ha rifiutato il servizio"}."
            )
        }
    }

    private fun apri() {
        if (_status.value.listening) return
        // Al primo avvio del processo con l'interruttore già acceso non c'è ancora un token.
        if (token.valore == null) token.rigenera()

        // Il certificato si prepara **prima** e a parte, per poterne riportare il guasto con il
        // suo nome: infilarlo nello stesso `runCatching` dell'apertura direbbe all'utente che la
        // porta è occupata mentre il problema è il disco.
        val identita = runCatching { identita() }.getOrElse { errore ->
            _status.value = _status.value.copy(
                listening = false,
                port = null,
                host = null,
                token = null,
                impronta = null,
                lastMessage = "Impossibile preparare il certificato del server: " +
                    "${errore.message ?: "errore sconosciuto"}."
            )
            return
        }

        runCatching { server.start(port) }
            .onSuccess { portaEffettiva ->
                _status.value = _status.value.copy(
                    listening = true,
                    // La porta **effettiva**: con `port = 0` nei test non è quella richiesta, e
                    // mostrare quella richiesta manderebbe chi digita contro un muro.
                    port = portaEffettiva,
                    host = indirizzoLocale(),
                    token = token.valore,
                    impronta = identita.impronta,
                    lastMessage = null
                )
            }
            .onFailure { errore ->
                _status.value = _status.value.copy(
                    listening = false,
                    port = null,
                    host = null,
                    token = null,
                    impronta = null,
                    lastMessage = "Impossibile aprire la porta $port: " +
                        "${errore.message ?: "porta occupata"}."
                )
            }
    }

    private fun chiudi() {
        server.stop()
        _status.value = _status.value.copy(listening = false, port = null, host = null, impronta = null)
    }
}

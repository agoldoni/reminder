package it.agoldoni.reminder.web

import it.agoldoni.reminder.data.EventDao
import it.agoldoni.reminder.platform.AlarmScheduler
import it.agoldoni.reminder.platform.AppSettings
import it.agoldoni.reminder.platform.nowMillis
import it.agoldoni.reminder.sync.siteAddress
import java.io.File
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mette insieme i pezzi della web app locale: interruttore, socket, token e stato mostrabile.
 *
 * **Vive quanto il processo, non quanto una schermata.** È costruito dall'`Application`, e finché
 * l'interruttore è acceso la porta resta aperta anche ad app chiusa: a tenere vivo il processo ci
 * pensa il [ProcessKeeper]. Legarlo a una Activity vorrebbe dire aprire e chiudere il socket a
 * ogni rotazione dello schermo — che è un giro completo di `onStop`/`onStart` — e lasciare la
 * pagina senza risposta ogni volta che qualcuno gira il telefono in mano.
 *
 * Fino alla feature 005 la ragione era un'altra e più grave: una rotazione **rigenerava il token**,
 * e l'indirizzo appena digitato sull'altro dispositivo smetteva di funzionare senza che l'utente
 * avesse toccato niente. Adesso i token si coniano da una chiave che sta su disco ([ChiaveFirma]) e
 * coniarne di nuovi non invalida quelli consegnati: quel difetto non c'è più, ed è la feature 006.
 */
class WebService(
    dao: EventDao,
    /**
     * Da dove arriva «qualcosa è cambiato». Su Android è l'invalidazione della tabella `events`,
     * cioè un segnale che nasce dal **database** e non dai punti di scrittura: così nessun ramo
     * futuro può dimenticarsi di annunciare, e la pagina non può diventare lenta in silenzio.
     *
     * Il valore predefinito è un flusso vuoto, e non nasconde un cablaggio dimenticato: senza
     * segnale l'attesa scade e si risponde `304`, cioè il ritmo di prima di questa feature.
     */
    cambiamenti: Flow<*> = emptyFlow<Any>(),
    /**
     * Le sveglie. Sta qui perché una scrittura che non le rimette in riga produce il guasto
     * peggiore di tutta la feature: il dato è giusto nel database e la notifica arriva all'ora
     * vecchia — sembra funzionare, e fallisce quando ormai non serve più.
     */
    alarms: AlarmScheduler,
    /** Identità di questo dispositivo: marchia l'`origin` degli eventi creati dal browser. */
    deviceId: String,
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

    /**
     * La chiave con cui si firmano i token, **nella stessa cartella del certificato**: sono due
     * segreti dello stesso server, protetti dalla stessa cosa — i permessi del file — e un secondo
     * posto sarebbe un secondo posto da ricordarsi di cancellare.
     */
    private val chiaviFirma = ChiaveFirma(cartellaCertificato)

    private val token = AccessToken(chiaviFirma, now)

    private val segnale = Cambiamenti(cambiamenti, scope)

    private val router = Router(
        scritture = ScrittureWeb(dao, alarms, deviceId, now),
        token = token,
        dao = dao,
        cambiamenti = segnale
    )
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
        // Nessun segreto da rigenerare: i token li conia `apri()` dalla chiave che sta su disco, e
        // quelli consegnati la volta scorsa continuano a valere. È il rovescio dell'indirizzo
        // fisso, ed è la ragione per cui esiste `revoke()`.
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
        // **Spegnere non revoca.** L'interruttore chiude la porta; le serrature si cambiano con
        // `revoke()`, che è un gesto diverso e visibile. Gli indirizzi spariscono dallo stato
        // perché non c'è più niente da raggiungere, non perché abbiano smesso di valere.
        _status.value = _status.value.copy(
            enabled = false,
            tokenLettura = null,
            tokenScrittura = null,
            validoFinoA = null,
            lastMessage = null
        )
    }

    /**
     * La revoca: chiave nuova, e ogni indirizzo consegnato finora smette di funzionare.
     *
     * **Si conia subito la coppia nuova** se la porta è aperta, così la schermata mostra già gli
     * indirizzi buoni: costringere l'utente a spegnere e riaccendere dopo una revoca vorrebbe dire
     * fargli chiudere la porta per riaprirla, cioè il gesto che la revoca esiste per evitare.
     *
     * Funziona anche a interruttore spento: toglie l'accesso per quando la porta si riaprirà.
     */
    override fun revoke() {
        runCatching { token.revoca() }.onFailure { errore ->
            _status.value = _status.value.copy(
                lastMessage = errore.message
                    ?: "Non si riesce a sostituire la chiave d'accesso: gli indirizzi consegnati " +
                    "restano validi."
            )
            return
        }

        if (!_status.value.listening) {
            _status.value = _status.value.copy(
                tokenLettura = null,
                tokenScrittura = null,
                validoFinoA = null,
                lastMessage = null
            )
            return
        }

        val coppia = runCatching { token.coniaCoppia() }.getOrElse { errore ->
            // La chiave è già cambiata, quindi i vecchi indirizzi sono morti comunque. Qui si è
            // solo senza indirizzi nuovi da mostrare, e dirlo è meglio che mostrarne di finti.
            guasto(
                errore.message
                    ?: "Chiave d'accesso sostituita, ma non si riesce a coniare i nuovi indirizzi."
            )
            return
        }
        _status.value = _status.value.copy(
            tokenLettura = coppia.lettura,
            tokenScrittura = coppia.scrittura,
            validoFinoA = coppia.scadenzaMillis,
            lastMessage = null
        )
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

        // **La chiave d'accesso viene prima di tutto il resto**, e un suo guasto chiude la
        // faccenda qui: una porta aperta senza indirizzi da mostrare è una porta che nessuno può
        // usare, e una chiave tenuta solo in memoria sarebbe peggio ancora — sembrerebbe
        // funzionare e morirebbe al riavvio, cioè il difetto che questa feature toglie.
        // Il messaggio arriva intero da `ChiaveFirma`, che sa quale dei due casi è capitato.
        val coppia = runCatching { token.coniaCoppia() }.getOrElse { errore ->
            guasto(
                errore.message
                    ?: "Impossibile preparare la chiave d'accesso: la porta resta chiusa."
            )
            return
        }

        // Il certificato si prepara **a parte**, per poterne riportare il guasto con il suo nome:
        // infilarlo nello stesso `runCatching` dell'apertura direbbe all'utente che la porta è
        // occupata mentre il problema è il disco.
        val identita = runCatching { identita() }.getOrElse { errore ->
            guasto(
                "Impossibile preparare il certificato del server: " +
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
                    tokenLettura = coppia.lettura,
                    tokenScrittura = coppia.scrittura,
                    validoFinoA = coppia.scadenzaMillis,
                    impronta = identita.impronta,
                    lastMessage = null
                )
            }
            .onFailure { errore ->
                guasto("Impossibile aprire la porta $port: ${errore.message ?: "porta occupata"}.")
            }
    }

    /** Porta chiusa e niente da mostrare, con la ragione già in italiano. */
    private fun guasto(messaggio: String) {
        _status.value = _status.value.copy(
            listening = false,
            port = null,
            host = null,
            tokenLettura = null,
            tokenScrittura = null,
            validoFinoA = null,
            impronta = null,
            lastMessage = messaggio
        )
    }

    private fun chiudi() {
        server.stop()
        _status.value = _status.value.copy(listening = false, port = null, host = null, impronta = null)
    }
}

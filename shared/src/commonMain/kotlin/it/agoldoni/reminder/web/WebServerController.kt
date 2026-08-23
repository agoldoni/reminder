package it.agoldoni.reminder.web

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Porta della web app locale. Fissa, così l'indirizzo da digitare sull'altro dispositivo resta
 * indovinabile anche senza avere l'app sotto gli occhi.
 *
 * **Non può coincidere con nessuna delle altre due porte fisse dell'app**: `SYNC_PORT` (47700) del
 * server di sincronizzazione e `SingleInstance.DEFAULT_PORT` (47653) dell'istanza singola desktop.
 * Le tre costanti nascono in moduli diversi e nessuno le vede insieme: quando due di esse hanno
 * coinciso, il servizio che si legava per secondo restava muto e l'app sembrava funzionare. I
 * presidi sono `PorteWebTest` qui accanto e `PorteTest` in `:desktopApp`, che è l'unico modulo a
 * vederle tutte e tre.
 */
const val WEB_PORT = 9888

/** Che cosa sta facendo la web app locale, in una forma direttamente mostrabile. */
data class WebStatus(
    /** L'interruttore dell'utente: sopravvive alla chiusura dell'app. */
    val enabled: Boolean = false,
    /**
     * Se in questo momento c'è davvero un socket in ascolto. È diverso da [enabled]: a interruttore
     * acceso ma app in background non si ascolta, e l'interfaccia deve poter dire quale delle due
     * cose sta succedendo invece di far credere che sia raggiungibile.
     */
    val listening: Boolean = false,
    val host: String? = null,
    /** Porta su cui ci si è **effettivamente** legati, che con `WEB_PORT = 0` nei test non coincide. */
    val port: Int? = null,
    /**
     * Il token che apre la pagina **in sola lettura**: è quello che si può dare a qualcun altro.
     *
     * Dalla feature 006 è un JWT firmato e non più otto caratteri, e la differenza che conta non è
     * la lunghezza: **non muore quando l'app si riavvia**. Quello che si vede qui è un buono di
     * consegna fresco, coniato all'apertura della porta; i token consegnati prima restano buoni
     * fino alla loro scadenza, e a farli cadere tutti c'è un gesto apposta.
     */
    val tokenLettura: String? = null,
    /** Il token che permette anche di modificare. Da tenere per sé. */
    val tokenScrittura: String? = null,
    /**
     * Fino a quando valgono i due indirizzi qui sopra. **Uno solo per entrambi**: si coniano nello
     * stesso istante e con la stessa durata, e due campi suggerirebbero una differenza che non c'è.
     *
     * Serve a poterlo **dire**. Senza, l'unica alternativa sarebbe scrivere «trenta giorni» in un
     * testo e lasciare all'utente la sottrazione — che farebbe male, e proprio nel momento in cui
     * gli servirebbe sapere se l'indirizzo che sta per dare a qualcuno durerà.
     */
    val validoFinoA: Long? = null,
    /**
     * Impronta SHA-256 del certificato che il server presenta, nella forma in cui la mostrano i
     * browser. Esiste perché un essere umano la confronti: il certificato è autofirmato, il
     * browser avvisa e l'utente scavalca l'avviso — e scavalcandolo accetta *qualunque*
     * certificato, compreso quello di chi si fosse messo in mezzo. Confrontarla una volta è ciò
     * che distingue «cifrato» da «cifrato e autenticato».
     */
    val impronta: String? = null,
    /** Esito o errore dell'ultimo tentativo, già in italiano. */
    val lastMessage: String? = null
) {
    /**
     * L'indirizzo che apre la pagina **in sola lettura**: guarda, non tocca. È quello che si può
     * dare a un'altra persona, ed è quello che la schermata mostra per primo.
     *
     * **`https` e non `http`**: la porta non parla più in chiaro (feature 003).
     *
     * **Il token sta nel frammento** (`#access=`) e non più nella query, e non è un dettaglio di
     * forma: il frammento **non viene mai spedito al server**. Non finisce in nessun log, non entra
     * nell'header `Referer`, non compare in nessuna riga di richiesta. La pagina lo legge, se lo
     * conserva e ripulisce la barra dell'indirizzo — ed è da lì in poi che l'indirizzo diventa
     * fisso, cioè qualcosa che si può mettere fra i segnalibri.
     */
    val urlLettura: String? get() = indirizzo(tokenLettura)

    /**
     * L'indirizzo che permette anche di modificare. Da tenere per sé.
     *
     * Nella schermata sta dietro un tocco in più, e non è cortesia: i due errori possibili non
     * pesano uguale. Copiare questo credendo di copiare l'altro regala il telecomando e non dà
     * nessun segnale; l'errore opposto si scopre in tre secondi, perché la pagina non ha i comandi.
     *
     * Con i JWT i due indirizzi differiscono per **tutta** la coda invece che per otto caratteri
     * finali, quindi lo scambio è meno probabile di prima — ma sono anche entrambi lunghi ~180
     * caratteri, cioè illeggibili a colpo d'occhio allo stesso modo. Il tocco in più resta.
     */
    val urlScrittura: String? get() = indirizzo(tokenScrittura)

    /**
     * Si compone qui e non nella schermata: due punti che lo compongono per conto proprio prima o
     * poi lo compongono in due modi diversi, e chi digita non ha modo di sapere quale è buono.
     */
    private fun indirizzo(token: String?): String? =
        if (listening && host != null && port != null && token != null) {
            "https://$host:$port/#access=$token"
        } else {
            null
        }
}

/**
 * Il punto da cui l'app comanda la web app locale. È un'interfaccia e non una classe per la stessa
 * ragione di `SyncController`: `AppContainer` e le schermate vivono in `commonMain`, mentre socket
 * e I/O stanno in `jvmSharedMain`.
 */
interface WebServerController {

    val status: StateFlow<WebStatus>

    /**
     * Se questa piattaforma ha davvero una web app da accendere. Serve alle schermate, che vivono
     * in `commonMain` e non sanno dove stanno girando: senza, la sezione comparirebbe anche su
     * desktop, dove non è cablata.
     */
    val supported: Boolean

    /** Accende l'interruttore, apre il socket e chiede al custode di tenere vivo il processo. */
    fun enable()

    /**
     * Spegne l'interruttore, chiude il socket e congeda il custode.
     *
     * **Non invalida più niente**, ed è il rovescio dell'indirizzo fisso: l'interruttore chiude la
     * porta, non cambia le serrature. Chi vuole togliere un accesso già dato usa [revoke], che è un
     * gesto diverso e va detto all'utente — altrimenti continuerà a credere che spegnere basti.
     */
    fun disable()

    /**
     * Butta via la chiave di firma e ne fa una nuova: **ogni indirizzo consegnato finora smette di
     * funzionare**, e vanno riconsegnati tutti.
     *
     * È l'unica revoca che esiste, e cade tutta insieme: non c'è una lista di token emessi da cui
     * togliere una riga. Funziona anche a interruttore spento — toglie l'accesso per quando la
     * porta si riaprirà — perché chi spegne credendo di revocare deve trovare lì il gesto vero.
     */
    fun revoke()

    /**
     * Riapre se l'interruttore è acceso. Da chiamare quando l'app arriva in primo piano: è
     * l'unico momento in cui su Android si può avviare un servizio in primo piano senza che il
     * sistema lo rifiuti, e serve a rimettere in piedi la porta dopo che il processo è stato
     * ricreato.
     *
     * Non fa nulla se si sta già ascoltando, così chiamarla due volte non costa niente.
     */
    fun resume()
}

/**
 * Chi tiene vivo il processo mentre la porta è aperta.
 *
 * Su Android non basta lasciare il socket aperto: il processo in background viene congelato o
 * ucciso e Doze taglia la rete. Serve un servizio in primo piano, che si porta dietro una notifica
 * permanente — la quale non è solo un costo, ma il segnale sempre visibile che una porta è aperta.
 * Altrove non serve niente, e l'implementazione predefinita infatti non fa nulla.
 */
fun interface ProcessKeeper {
    fun keepAlive(active: Boolean)
}

/**
 * Il ripiego per le piattaforme dove la web app non è cablata — oggi il desktop. Esiste perché
 * `AppContainer` possa avere un valore predefinito e il modulo `:desktopApp` non debba conoscere
 * una feature che non usa.
 */
object WebServerNonDisponibile : WebServerController {
    override val status: StateFlow<WebStatus> = MutableStateFlow(WebStatus())
    override val supported: Boolean = false
    override fun enable() = Unit
    override fun disable() = Unit
    override fun revoke() = Unit
    override fun resume() = Unit
}

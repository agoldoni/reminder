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
     */
    val tokenLettura: String? = null,
    /** Il token che permette anche di modificare. Da tenere per sé. */
    val tokenScrittura: String? = null,
    /**
     * Impronta SHA-256 del certificato che il server presenta, nella forma in cui la mostrano i
     * browser. Esiste perché un essere umano la confronti: il certificato è autofirmato, il
     * browser avvisa e l'utente scavalca l'avviso — e scavalcandolo accetta *qualunque*
     * certificato, compreso quello di chi si fosse messo in mezzo. Confrontarla una volta è ciò
     * che distingue «cifrato» da «cifrato e autenticato».
     */
    val impronta: String? = null,
    /**
     * Se l'app è in questo momento davanti all'utente. **La scrittura dal browser funziona solo
     * mentre lo è**: ad app chiusa la porta resta aperta e serve letture, esattamente come faceva
     * prima che le scritture esistessero.
     *
     * Non è la stessa cosa di [listening], e la differenza è tutta qui: la porta sopravvive alla
     * chiusura dell'app grazie al servizio in primo piano, l'attenzione dell'utente no.
     */
    val appDavanti: Boolean = false,
    /** Esito o errore dell'ultimo tentativo, già in italiano. */
    val lastMessage: String? = null
) {
    /**
     * L'indirizzo che apre la pagina **in sola lettura**: guarda, non tocca. È quello che si può
     * dare a un'altra persona, ed è quello che la schermata mostra per primo.
     *
     * **`https` e non `http`**: la porta non parla più in chiaro. Tenerle aperte tutte e due
     * avrebbe conservato la debolezza che questa scelta esiste per chiudere — chi ascolta
     * aspetterebbe la prima richiesta non cifrata. Il prezzo è che un segnalibro salvato con la
     * versione precedente smette di funzionare, e non in modo comprensibile: un client in chiaro
     * contro una porta TLS riceve spazzatura, non un errore. L'indirizzo giusto è sempre qui.
     */
    val urlLettura: String? get() = indirizzo(tokenLettura)

    /**
     * L'indirizzo che permette anche di modificare. Da tenere per sé.
     *
     * Nella schermata sta dietro un tocco in più, e non è cortesia: i due indirizzi differiscono
     * solo negli otto caratteri finali, quindi affiancati sono indistinguibili a colpo d'occhio —
     * e i due errori possibili non pesano uguale. Copiare questo credendo di copiare l'altro
     * regala il telecomando e non dà nessun segnale; l'errore opposto si scopre in tre secondi,
     * perché la pagina non ha i comandi.
     */
    val urlScrittura: String? get() = indirizzo(tokenScrittura)

    /**
     * Si compone qui e non nella schermata: due punti che lo compongono per conto proprio prima o
     * poi lo compongono in due modi diversi, e chi digita non ha modo di sapere quale è buono.
     */
    private fun indirizzo(token: String?): String? =
        if (listening && host != null && port != null && token != null) {
            "https://$host:$port/?t=$token"
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

    /** Spegne l'interruttore, chiude il socket, **invalida il token** e congeda il custode. */
    fun disable()

    /**
     * Riapre se l'interruttore è acceso. Da chiamare quando l'app arriva in primo piano: è
     * l'unico momento in cui su Android si può avviare un servizio in primo piano senza che il
     * sistema lo rifiuti, e serve a rimettere in piedi la porta dopo che il processo è stato
     * ricreato.
     *
     * Non fa nulla se si sta già ascoltando, così chiamarla due volte non costa niente.
     *
     * Registra anche che **l'app è davanti all'utente**, cosa che vale a interruttore spento come
     * acceso: è un fatto sul telefono, non una conseguenza dell'avere una porta aperta.
     */
    fun resume()

    /**
     * L'app non è più davanti. **Non chiude la porta** — quella sopravvive alla chiusura dell'app,
     * ed è tutta la ragione per cui esiste il servizio in primo piano — ma da qui in poi le
     * scritture dal browser sono rifiutate finché l'utente non torna.
     */
    fun pause()
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
    override fun resume() = Unit
    override fun pause() = Unit
}

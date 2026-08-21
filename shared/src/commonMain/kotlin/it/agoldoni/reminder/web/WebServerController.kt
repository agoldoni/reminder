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
    val token: String? = null,
    /** Esito o errore dell'ultimo tentativo, già in italiano. */
    val lastMessage: String? = null
) {
    /**
     * L'indirizzo completo da digitare sull'altro dispositivo. Si compone qui e non nella
     * schermata: due punti che lo compongono per conto proprio prima o poi lo compongono in due
     * modi diversi, e chi digita non ha modo di sapere quale dei due è quello buono.
     */
    val url: String?
        get() = if (listening && host != null && port != null && token != null) {
            "http://$host:$port/?t=$token"
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

    /** Accende l'interruttore e, se l'app è in primo piano, apre il socket. */
    fun enable()

    /** Spegne l'interruttore, chiude il socket **e invalida il token**. */
    fun disable()

    /**
     * L'app è tornata in primo piano. **Non** tocca l'interruttore: il token sopravvive, perché
     * altrimenti l'indirizzo già digitato sull'altro dispositivo smetterebbe di funzionare a ogni
     * rotazione dello schermo — che è un cambio di configurazione, e quindi un giro completo di
     * `onStop`/`onStart`.
     */
    fun onForeground()

    /**
     * L'app ha lasciato il primo piano: si chiude il socket ma si resta accesi. Android non lascia
     * tenere un socket in ascolto ad app chiusa, ed è la stessa ragione per cui la
     * sincronizzazione su telefono ascolta solo a schermata aperta.
     */
    fun onBackground()
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
    override fun onForeground() = Unit
    override fun onBackground() = Unit
}

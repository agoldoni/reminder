package it.agoldoni.reminder.sync

import kotlinx.coroutines.flow.StateFlow

/** Che cosa sta facendo la sincronizzazione, in una forma direttamente mostrabile. */
data class SyncStatus(
    val enabled: Boolean = false,
    /** Porta su cui questo dispositivo è raggiungibile, se sta ascoltando. */
    val listeningPort: Int? = null,
    val syncing: Boolean = false,
    /** Ultima sincronizzazione riuscita, in tempo locale. */
    val lastSyncAt: Long? = null,
    /** Esito o errore dell'ultimo tentativo, già in italiano. */
    val lastMessage: String? = null
)

/**
 * Il punto da cui l'app comanda la sincronizzazione. È un'interfaccia e non una classe perché
 * `AppContainer` e le schermate vivono in `commonMain`, mentre socket e crittografia stanno in
 * `jvmSharedMain`: la stessa ragione per cui `AlarmScheduler` è un'interfaccia.
 */
interface SyncController {

    val status: StateFlow<SyncStatus>

    /** I dispositivi raggiungibili: trovati sulla rete o inseriti a mano. */
    val discovered: StateFlow<List<DiscoveredPeer>>

    val discoveryStatus: StateFlow<DiscoveryStatus>

    /** I dispositivi già associati, con nome e ultimo allineamento. */
    val paired: StateFlow<List<PairedPeer>>

    /**
     * Avvio automatico all'apertura dell'app: **rispetta l'interruttore**. Finché la
     * sincronizzazione è spenta non apre un socket né manda un annuncio.
     */
    fun start()

    fun stop()

    /**
     * Ricerca e ascolto mentre la schermata di sincronizzazione è aperta. Vale **anche a
     * interruttore spento**, perché aprire quella schermata è già un atto esplicito dell'utente —
     * e senza questo non ci sarebbe modo di trovare il primo dispositivo da associare.
     *
     * Non accende l'interruttore: a schermata chiusa, se nessuna associazione è stata completata,
     * torna tutto spento.
     */
    fun beginInteractive()

    fun endInteractive()

    /** Accende l'interruttore e avvia; è ciò che fa la schermata di associazione. */
    fun enable()

    /** Spegne e dimentica l'ascolto: è il primo passo del rollback. */
    fun disable()

    /** Sincronizza con tutti i dispositivi associati. */
    suspend fun syncNow()

    /**
     * Associa questo dispositivo a [peer]. [approval] riceve il codice a sei cifre da mostrare e
     * deve restituire `true` solo se l'utente conferma di vedere **lo stesso** codice sull'altro
     * schermo.
     */
    suspend fun pair(peer: DiscoveredPeer, approval: PairingApprovalRequest): PairingResult

    /** Dissocia: le credenziali spariscono e le sincronizzazioni successive vengono rifiutate. */
    suspend fun unpair(deviceId: String)

    /** Aggiunge un indirizzo digitato quando la ricerca automatica non passa. */
    fun addManualPeer(host: String, port: Int): Result<DiscoveredPeer>

    /**
     * Registra chi mostrerà il codice di un'associazione **avviata dall'altro dispositivo**, e
     * `null` quando non c'è nessuno che possa mostrarlo. Senza qualcuno registrato l'associazione
     * in arrivo viene negata: accettarla mentre nessuno guarda il codice vanificherebbe il
     * confronto a vista, che è l'unica cosa che protegge dall'uomo nel mezzo.
     */
    fun onIncomingPairing(approval: PairingApprovalRequest?)
}

/** Un dispositivo associato, nella forma che serve alla schermata di stato. */
data class PairedPeer(
    val deviceId: String,
    val displayName: String,
    val lastSyncAt: Long,
    val lastHost: String?,
    val lastPort: Int?
)

/** Il confronto del codice, chiesto all'utente. */
typealias PairingApprovalRequest = suspend (code: String, peerName: String) -> Boolean

sealed interface PairingResult {
    data class Paired(val peer: PairedPeer) : PairingResult
    data class Refused(val reason: String) : PairingResult
}

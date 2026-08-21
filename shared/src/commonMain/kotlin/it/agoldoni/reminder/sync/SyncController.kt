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
     * Accende annunci, ascolto e ricerca. Senza il consenso esplicito dell'utente non fa nulla:
     * finché la sincronizzazione è spenta non si apre un socket né parte un annuncio.
     */
    fun start()

    fun stop()

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

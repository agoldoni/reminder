package it.agoldoni.reminder.sync

import it.agoldoni.reminder.data.PeerDao
import it.agoldoni.reminder.data.PeerEntity
import it.agoldoni.reminder.platform.AppSettings
import it.agoldoni.reminder.platform.nowMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mette insieme i pezzi: ricerca sulla rete, ascolto, associazione e giri di sincronizzazione.
 *
 * [listens] distingue i due dispositivi. Il desktop ascolta sempre; su Android le policy di
 * sistema non permettono di tenere un socket aperto ad app chiusa, quindi il telefono chiama e
 * basta, quando è in mano all'utente. Non è una limitazione aggirabile: è il motivo per cui la
 * sincronizzazione è asimmetrica.
 */
class SyncService(
    private val identity: LocalIdentity,
    private val peers: PeerDao,
    private val engine: SyncEngine,
    discovery: Discovery,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val listens: Boolean,
    /** Porta di ascolto; a zero la sceglie il sistema, cosa che serve solo ai test. */
    private val port: Int = SYNC_PORT,
    private val now: () -> Long = ::nowMillis
) : SyncController {

    private val directory = PeerDirectory(discovery, scope)

    private val _status = MutableStateFlow(SyncStatus(enabled = settings.syncEnabled.value))
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()

    override val discovered: StateFlow<List<DiscoveredPeer>> get() = directory.peers
    override val discoveryStatus: StateFlow<DiscoveryStatus> get() = directory.status

    override val paired: StateFlow<List<PairedPeer>> = peers.getAll()
        .map { list -> list.map { it.toPairedPeer() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private var server: SyncServer? = null

    /** Un giro alla volta: due sincronizzazioni sovrapposte con lo stesso peer si ostacolerebbero. */
    private val syncLock = Mutex()

    /**
     * Il codice va confermato anche da chi riceve l'associazione. Chi non ha una schermata aperta
     * per mostrarlo non può accettare: negare è l'unica risposta onesta, perché accettare senza
     * che nessuno abbia guardato il codice vanificherebbe il confronto a vista.
     */
    @Volatile
    private var incomingApproval: PairingApprovalRequest? = null

    override fun start() {
        if (!settings.syncEnabled.value) return
        var portaEffettiva: Int? = null
        if (listens && server == null) {
            val istanza = SyncServer(
                identity = identity,
                peers = peers,
                engine = engine,
                approval = { code, peer ->
                    incomingApproval?.invoke(code, peer.displayName) ?: false
                },
                now = now,
                scope = scope,
                onEvent = ::onServerEvent
            )
            server = istanza
            runCatching { istanza.start(port) }
                .onSuccess { porta ->
                    portaEffettiva = porta
                    _status.value = _status.value.copy(listeningPort = porta)
                }
                .onFailure { errore ->
                    server = null
                    _status.value = _status.value.copy(
                        lastMessage = "Impossibile mettersi in ascolto sulla porta $port: " +
                            "${errore.message ?: "porta occupata"}."
                    )
                }
        }
        // Ci si annuncia solo se si è davvero raggiungibili, e con la porta su cui si ascolta
        // davvero: annunciarne un'altra manderebbe l'altro dispositivo contro un muro.
        directory.start(
            portaEffettiva?.let {
                Advertisement(identity.deviceId, identity.displayName, it)
            }
        )
        _status.value = _status.value.copy(enabled = true)
    }

    override fun stop() {
        directory.stop()
        server?.stop()
        server = null
        _status.value = _status.value.copy(listeningPort = null)
    }

    override fun enable() {
        settings.setSyncEnabled(true)
        start()
    }

    override fun disable() {
        stop()
        settings.setSyncEnabled(false)
        _status.value = _status.value.copy(enabled = false)
    }

    override suspend fun syncNow() {
        if (!settings.syncEnabled.value) return
        syncLock.withLock {
            val associati = peers.list()
            if (associati.isEmpty()) {
                _status.value = _status.value.copy(
                    lastMessage = "Nessun dispositivo associato."
                )
                return
            }
            _status.value = _status.value.copy(syncing = true, lastMessage = null)
            var ultimoMessaggio: String? = null
            var riuscito = false
            for (peer in associati) {
                when (val esito = contatta(peer)) {
                    is SyncOutcome.Completed -> {
                        riuscito = true
                        ultimoMessaggio = descrivi(peer, esito.result)
                    }

                    is SyncOutcome.Refused -> ultimoMessaggio = "${peer.displayName}: ${esito.reason}"
                    is SyncOutcome.Failed -> ultimoMessaggio = "${peer.displayName}: ${esito.reason}"
                }
            }
            _status.value = _status.value.copy(
                syncing = false,
                lastSyncAt = if (riuscito) now() else _status.value.lastSyncAt,
                lastMessage = ultimoMessaggio
            )
        }
    }

    /**
     * Si prova prima l'indirizzo con cui il peer si sta annunciando adesso e poi l'ultimo a cui ha
     * risposto: su una rete domestica l'IP cambia, e l'annuncio è più aggiornato di quanto sia
     * salvato.
     */
    private suspend fun contatta(peer: PeerEntity): SyncOutcome {
        val indirizzi = buildList {
            directory.peers.value
                .firstOrNull { it.deviceId == peer.deviceId }
                ?.let { add(it.host to it.port) }
            peer.lastHost?.let { host -> add(host to (peer.lastPort ?: SYNC_PORT)) }
        }.distinct()

        if (indirizzi.isEmpty()) {
            return SyncOutcome.Failed(
                "${peer.displayName} non è raggiungibile: non si è annunciato e non ha un " +
                    "indirizzo noto."
            )
        }
        var ultimo: SyncOutcome = SyncOutcome.Failed("Nessun tentativo effettuato.")
        for ((host, port) in indirizzi) {
            ultimo = SyncClient.sync(host, port, identity, peers, engine)
            if (ultimo is SyncOutcome.Completed) return ultimo
        }
        return ultimo
    }

    override suspend fun pair(
        peer: DiscoveredPeer,
        approval: PairingApprovalRequest
    ): PairingResult {
        val esito = SyncClient.pair(
            host = peer.host,
            port = peer.port,
            identity = identity,
            approval = { code, chi -> approval(code, chi.displayName) },
            nowMillis = now()
        )
        return when (esito) {
            is PairingOutcome.Paired -> {
                val salvato = esito.peer.copy(lastHost = peer.host, lastPort = peer.port)
                peers.upsert(salvato)
                // Associarsi è il gesto con cui l'utente accende la sincronizzazione.
                enable()
                PairingResult.Paired(salvato.toPairedPeer())
            }

            is PairingOutcome.Refused -> PairingResult.Refused(esito.reason)
        }
    }

    /** Da chiamare mentre una schermata è pronta a mostrare il codice di un'associazione in arrivo. */
    fun acceptIncomingPairing(approval: PairingApprovalRequest?) {
        incomingApproval = approval
    }

    override suspend fun unpair(deviceId: String) {
        peers.delete(deviceId)
        _status.value = _status.value.copy(lastMessage = "Dispositivo dissociato.")
    }

    override fun addManualPeer(host: String, port: Int): Result<DiscoveredPeer> =
        directory.addManual(host, port)

    private fun onServerEvent(event: SyncServerEvent) {
        scope.launch {
            _status.value = when (event) {
                is SyncServerEvent.Paired ->
                    _status.value.copy(lastMessage = "${event.peer.displayName} associato.")

                is SyncServerEvent.Synced -> _status.value.copy(
                    lastSyncAt = now(),
                    lastMessage = descrivi(event.peer, event.result)
                )

                is SyncServerEvent.Refused -> _status.value.copy(lastMessage = event.reason)
                is SyncServerEvent.Failed -> _status.value.copy(lastMessage = event.reason)
            }
        }
    }

    private fun descrivi(peer: PeerEntity, result: SyncResult): String {
        val ricevuti = result.received.applied
        val inviati = result.sent
        return when {
            ricevuti == 0 && inviati == 0 -> "${peer.displayName}: già allineati."
            else -> "${peer.displayName}: ricevuti $ricevuti, inviati $inviati."
        }
    }
}

private fun PeerEntity.toPairedPeer() = PairedPeer(
    deviceId = deviceId,
    displayName = displayName,
    lastSyncAt = lastSyncAt,
    lastHost = lastHost,
    lastPort = lastPort
)

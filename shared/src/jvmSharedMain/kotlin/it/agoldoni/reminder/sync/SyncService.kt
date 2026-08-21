package it.agoldoni.reminder.sync

import it.agoldoni.reminder.data.PeerDao
import it.agoldoni.reminder.data.PeerEntity
import it.agoldoni.reminder.platform.AppSettings
import it.agoldoni.reminder.platform.nowMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Mette insieme i pezzi: ricerca sulla rete, ascolto, associazione e giri di sincronizzazione.
 *
 * [listensInBackground] distingue i due dispositivi, ma **solo a schermata chiusa**. Il desktop
 * ascolta sempre; Android no, perché le policy di sistema non lasciano tenere un socket aperto ad
 * app chiusa.
 *
 * Mentre la schermata di sincronizzazione è aperta, invece, **ascoltano entrambi**: l'app è in
 * primo piano per definizione e un socket in ascolto è perfettamente lecito. Non è un dettaglio:
 * su una rete dove il telefono non riesce a raggiungere il PC — succede fra sottoreti diverse, con
 * NAT asimmetrico in mezzo — è l'unico modo di stabilire comunque la comunicazione, partendo dal
 * lato che passa.
 */
class SyncService(
    private val identity: LocalIdentity,
    private val peers: PeerDao,
    private val engine: SyncEngine,
    discovery: Discovery,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val listensInBackground: Boolean,
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

    /** Vero mentre la schermata di sincronizzazione è aperta. */
    @Volatile
    private var interattivo = false

    override fun start() {
        if (settings.syncEnabled.value) avvia()
    }

    override fun beginInteractive() {
        interattivo = true
        avvia()
    }

    override fun endInteractive() {
        interattivo = false
        when {
            // Chi non ha ancora associato nulla non deve restare in ascolto a schermata chiusa.
            !settings.syncEnabled.value -> stop()
            // Su Android il socket in ascolto vale finché l'app è in primo piano: chiusa la
            // schermata si smette di ascoltare, ma si resta associati e si continua a chiamare.
            !listensInBackground -> {
                server?.stop()
                server = null
                _status.value = _status.value.copy(listeningPort = null)
                directory.start(null)
            }
        }
    }

    private fun avvia() {
        var portaEffettiva: Int? = null
        if ((listensInBackground || interattivo) && server == null) {
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
        avvia()
    }

    override fun disable() {
        stop()
        settings.setSyncEnabled(false)
        _status.value = _status.value.copy(enabled = false)
    }

    /**
     * Le chiamate di rete sono **bloccanti** e chi le invoca arriva quasi sempre da
     * `viewModelScope`, che gira su `Dispatchers.Main`: senza questo spostamento l'app Android si
     * pianta finché la connessione non va in timeout, e il sistema la chiude per ANR. Non è un
     * caso di bordo — è successo al primo tentativo di associazione su un telefono vero, ed è
     * invisibile ai test JVM, dove `Dispatchers.Main` è un dispatcher di prova senza vincoli.
     */
    override suspend fun syncNow(): Unit = withContext(Dispatchers.IO) {
        if (!settings.syncEnabled.value) return@withContext
        syncLock.withLock {
            val associati = peers.list()
            if (associati.isEmpty()) {
                _status.value = _status.value.copy(
                    lastMessage = "Nessun dispositivo associato."
                )
                return@withLock
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
    ): PairingResult = withContext(Dispatchers.IO) {
        val esito = SyncClient.pair(
            host = peer.host,
            port = peer.port,
            identity = identity,
            approval = { code, chi -> approval(code, chi.displayName) },
            nowMillis = now()
        )
        when (esito) {
            is PairingOutcome.Paired -> {
                val salvato = esito.peer.copy(lastHost = peer.host, lastPort = peer.port)
                peers.upsert(salvato)
                // Un indirizzo digitato non porta con sé un deviceId, quindi l'elenco dei trovati
                // non può accorgersi da solo che ora è associato: resterebbe lì con il pulsante
                // «Associa» accanto al dispositivo appena associato.
                if (peer.source == PeerSource.MANUAL) directory.removeManual(peer)
                // Associarsi è il gesto con cui l'utente accende la sincronizzazione.
                enable()
                PairingResult.Paired(salvato.toPairedPeer())
            }

            is PairingOutcome.Refused -> PairingResult.Refused(esito.reason)
        }
    }

    override fun onIncomingPairing(approval: PairingApprovalRequest?) {
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

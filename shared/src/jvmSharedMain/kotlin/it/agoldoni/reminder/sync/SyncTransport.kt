package it.agoldoni.reminder.sync

import it.agoldoni.reminder.data.PeerDao
import it.agoldoni.reminder.data.PeerEntity
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Porta su cui si mette in ascolto chi può farlo. Fissa, così il fallback manuale è indovinabile. */
const val SYNC_PORT = 47653

private const val CONNECT_TIMEOUT_MILLIS = 5_000
private const val READ_TIMEOUT_MILLIS = 30_000

/** Che cosa è successo in un tentativo di sincronizzazione. */
sealed interface SyncOutcome {
    data class Completed(val peer: PeerEntity, val result: SyncResult) : SyncOutcome

    /** L'altro lato ha detto di no, con una ragione da mostrare. */
    data class Refused(val reason: String) : SyncOutcome

    /** Non si è riusciti a parlargli: rete, timeout, connessione caduta. */
    data class Failed(val reason: String) : SyncOutcome
}

/**
 * Il lato che chiama. Su Android è l'unico praticabile: le policy di sistema non permettono di
 * tenere un socket in ascolto ad app chiusa, quindi il telefono sincronizza quando è in mano
 * all'utente e il desktop sta sempre ad ascoltare.
 */
object SyncClient {

    /** Associazione con un dispositivo trovato o digitato. */
    suspend fun pair(
        host: String,
        port: Int,
        identity: LocalIdentity,
        approval: PairingApproval,
        nowMillis: Long
    ): PairingOutcome = connected(host, port, { PairingOutcome.Refused(it) }) { socket ->
        Pairing.initiate(socket.getInputStream(), socket.getOutputStream(), identity, approval, nowMillis)
    }

    /** Un giro di sincronizzazione con un peer già associato. */
    suspend fun sync(
        host: String,
        port: Int,
        identity: LocalIdentity,
        peers: PeerDao,
        engine: SyncEngine
    ): SyncOutcome = connected(host, port, { SyncOutcome.Failed(it) }) { socket ->
        when (val session = Session.initiate(
            socket.getInputStream(),
            socket.getOutputStream(),
            identity
        ) { peers.getById(it) }) {
            is SessionOutcome.Refused -> SyncOutcome.Refused(session.reason)
            is SessionOutcome.Open -> {
                val result = SyncConversation.initiate(
                    session.channel,
                    engine,
                    session.peer.lastSyncAt
                )
                peers.rememberSync(session.peer.deviceId, result.watermark)
                peers.rememberAddress(session.peer.deviceId, host, port)
                SyncOutcome.Completed(session.peer, result)
            }
        }
    }

    /**
     * Un guasto di rete non è un'eccezione da far risalire fino alla UI: è un esito con un
     * messaggio in italiano, perché è quello che l'utente deve leggere.
     */
    private suspend fun <T> connected(
        host: String,
        port: Int,
        onError: (String) -> T,
        block: suspend (Socket) -> T
    ): T = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
            socket.soTimeout = READ_TIMEOUT_MILLIS
            block(socket)
        }
    } catch (timeout: SocketTimeoutException) {
        onError("$host non ha risposto in tempo.")
    } catch (network: IOException) {
        onError("Non è stato possibile raggiungere $host: ${network.message ?: "rete non disponibile"}.")
    } catch (protocol: SyncProtocolException) {
        onError(protocol.message ?: "Dialogo interrotto con l'altro dispositivo.")
    }
}

/**
 * Il lato che ascolta. Ogni connessione dichiara nel saluto se vuole associarsi o sincronizzare, e
 * viene smistata di conseguenza: è per questo che il saluto è separato dalle due procedure.
 *
 * Una connessione che va male chiude solo se stessa. Il ciclo di ascolto non deve morire perché un
 * dispositivo si è scollegato a metà scambio.
 */
class SyncServer(
    private val identity: LocalIdentity,
    private val peers: PeerDao,
    private val engine: SyncEngine,
    private val approval: PairingApproval,
    private val now: () -> Long,
    private val scope: CoroutineScope,
    private val onEvent: (SyncServerEvent) -> Unit = {}
) {

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    val port: Int? get() = serverSocket?.localPort

    /** [requestedPort] a zero fa scegliere la porta al sistema: serve ai test. */
    fun start(requestedPort: Int = SYNC_PORT): Int {
        serverSocket?.let { return it.localPort }
        val socket = ServerSocket(requestedPort)
        serverSocket = socket
        acceptJob = scope.launch(Dispatchers.IO) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (closed: IOException) {
                    break // `stop()` chiude il socket: è il modo normale di uscire dal ciclo.
                }
                launch(Dispatchers.IO) { serve(client) }
            }
        }
        return socket.localPort
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
    }

    private suspend fun serve(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = READ_TIMEOUT_MILLIS
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val greeting = Handshake.asResponder(input, output, identity) ?: return
                when (greeting.intent) {
                    SyncIntent.PAIR -> pair(input, output, greeting)
                    SyncIntent.SYNC -> sync(input, output, greeting, socket)
                }
            }
        } catch (error: Exception) {
            onEvent(SyncServerEvent.Failed(error.message ?: "Connessione interrotta."))
        }
    }

    private suspend fun pair(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        greeting: Greeting
    ) {
        when (val outcome = Pairing.acceptGreeted(input, output, greeting.peer, approval, now())) {
            is PairingOutcome.Paired -> {
                peers.upsert(outcome.peer)
                onEvent(SyncServerEvent.Paired(outcome.peer))
            }

            is PairingOutcome.Refused -> onEvent(SyncServerEvent.Refused(outcome.reason))
        }
    }

    private suspend fun sync(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        greeting: Greeting,
        socket: Socket
    ) {
        when (val session = Session.acceptGreeted(input, output, greeting.peer) { peers.getById(it) }) {
            is SessionOutcome.Refused -> onEvent(SyncServerEvent.Refused(session.reason))
            is SessionOutcome.Open -> {
                val result = SyncConversation.accept(session.channel, engine, session.peer.lastSyncAt)
                peers.rememberSync(session.peer.deviceId, result.watermark)
                socket.inetAddress?.hostAddress?.let {
                    peers.rememberAddress(session.peer.deviceId, it, socket.port)
                }
                onEvent(SyncServerEvent.Synced(session.peer, result))
            }
        }
    }
}

sealed interface SyncServerEvent {
    data class Paired(val peer: PeerEntity) : SyncServerEvent
    data class Synced(val peer: PeerEntity, val result: SyncResult) : SyncServerEvent
    data class Refused(val reason: String) : SyncServerEvent
    data class Failed(val reason: String) : SyncServerEvent
}

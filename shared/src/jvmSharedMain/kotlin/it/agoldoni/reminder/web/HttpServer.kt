package it.agoldoni.reminder.web

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Un browser che apre la connessione e non manda niente non deve tenere occupato nulla. */
private const val READ_TIMEOUT_MILLIS = 10_000

/**
 * Il ciclo di ascolto. Struttura ricalcata su `SyncServer`, per la ragione per cui quella struttura
 * è fatta così: **una connessione che va male chiude solo se stessa**. Il ciclo non deve morire
 * perché un browser si è chiuso a metà richiesta o perché qualcuno ha mandato spazzatura sulla
 * porta — e su una porta esposta in rete la spazzatura arriva.
 *
 * Nessun keep-alive: una richiesta per connessione, poi si chiude. Costa una connessione in più
 * per ogni file della pagina e fa risparmiare tutta la gestione dello stato di una connessione
 * riutilizzata, che è dove si annidano i problemi.
 */
internal class HttpServer(
    private val scope: CoroutineScope,
    /** Riceve la richiesta e l'indirizzo da cui arriva: la soglia dei tentativi è per indirizzo. */
    private val gestisci: suspend (HttpRequest, String) -> HttpResponse
) {

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    val port: Int? get() = serverSocket?.localPort

    /** [requestedPort] a zero fa scegliere la porta al sistema: serve ai test. */
    fun start(requestedPort: Int = WEB_PORT): Int {
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
                launch(Dispatchers.IO) { servi(client) }
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

    /**
     * L'indirizzo di chi chiama, senza la porta effimera: è l'unica cosa stabile su cui contare i
     * tentativi falliti. Se manca — non dovrebbe, ma il socket può essere già morto — si usa una
     * chiave fissa: meglio contarli tutti insieme che non contarli affatto.
     */
    private fun provenienza(socket: Socket): String =
        socket.inetAddress?.hostAddress ?: "sconosciuto"

    private suspend fun servi(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = READ_TIMEOUT_MILLIS
                val risposta = when (val letta = leggiRichiesta(socket.getInputStream())) {
                    // Nessuno a cui rispondere: il client ha chiuso senza dire niente.
                    is RichiestaLetta.Chiusa -> return
                    is RichiestaLetta.Malformata -> HttpResponse.vuota(400)
                    // Il difetto del gestore si cattura **qui dentro**, dove il socket è ancora
                    // aperto: lasciandolo uscire da `use` la connessione sarebbe già chiusa e il
                    // 500 non partirebbe, lasciando il browser davanti a una connessione morta.
                    is RichiestaLetta.Ok -> try {
                        gestisci(letta.request, provenienza(socket))
                    } catch (cancellata: CancellationException) {
                        throw cancellata // la cancellazione non è un difetto: non va inghiottita
                    } catch (difetto: Exception) {
                        HttpResponse.vuota(500)
                    }
                }
                scriviRisposta(socket.getOutputStream(), risposta)
            }
        } catch (scaduto: SocketTimeoutException) {
            // Ha aperto e non ha parlato. Capita ai browser che preallacciano: non è un errore.
        } catch (rete: IOException) {
            // Connessione caduta mentre si leggeva o si scriveva: riguarda solo questa.
        }
    }
}

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
 *
 * **Di TLS non sa niente**, e non è un caso: riceve un `ServerSocket` già aperto da chi sa come
 * aprirlo (`TlsIdentity.apriSocket`), così il trasporto cifrato sta in un file solo e questo ciclo
 * resta lo stesso che i test della feature 002 provano in chiaro. Un dettaglio che vale la pena
 * conoscere: su un socket TLS **l'handshake non avviene su `accept()` ma alla prima lettura**,
 * quindi un client che rifiuta il certificato — o che parla in chiaro a una porta cifrata — fa
 * fallire `getInputStream()` con una `SSLHandshakeException`, che discende da `IOException` ed è
 * già catturata qui sotto insieme a tutte le altre cadute di connessione.
 */
internal class HttpServer(
    private val scope: CoroutineScope,
    /**
     * Come si apre il socket d'ascolto. Il valore predefinito è in chiaro e serve ai test del
     * ciclo, che di TLS non hanno bisogno; in produzione arriva da `TlsIdentity`.
     *
     * Sta **in mezzo** e non in fondo perché [gestisci] deve restare l'ultimo parametro: è passato
     * come lambda finale da `WebService`.
     */
    private val apriSocket: (Int) -> ServerSocket = { ServerSocket(it) },
    /** Riceve la richiesta e l'indirizzo da cui arriva: la soglia dei tentativi è per indirizzo. */
    private val gestisci: suspend (HttpRequest, String) -> HttpResponse
) {

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    val port: Int? get() = serverSocket?.localPort

    /** [requestedPort] a zero fa scegliere la porta al sistema: serve ai test. */
    fun start(requestedPort: Int = WEB_PORT): Int {
        serverSocket?.let { return it.localPort }
        val socket = apriSocket(requestedPort)
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

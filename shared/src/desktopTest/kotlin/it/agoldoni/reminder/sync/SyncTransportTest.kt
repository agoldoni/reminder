package it.agoldoni.reminder.sync

import it.agoldoni.reminder.data.AppDatabase
import it.agoldoni.reminder.data.EventEntity
import it.agoldoni.reminder.platform.createAppDatabase
import it.agoldoni.reminder.platform.newUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Il giro completo su socket veri, in ascolto su loopback: associazione, poi sincronizzazione,
 * come avverrebbe fra telefono e PC. È quello che i test precedenti non potevano mostrare, perché
 * lavoravano su stream collegati a mano.
 */
class SyncTransportTest {

    private lateinit var directory: File
    private lateinit var telefonoDb: AppDatabase
    private lateinit var computerDb: AppDatabase
    private val scope = CoroutineScope(SupervisorJob())
    private var server: SyncServer? = null

    private val telefono = LocalIdentity("id-telefono", "Telefono")
    private val computer = LocalIdentity("id-computer", "Computer")

    private val codici = mutableMapOf<String, String>()
    private val eventiServer = mutableListOf<SyncServerEvent>()

    @BeforeTest
    fun setUp() {
        directory = File.createTempFile("promemoria-trasporto", "").let {
            it.delete(); it.apply { mkdirs() }
        }
        telefonoDb = createAppDatabase(File(directory, "telefono.db"), "id-telefono")
        computerDb = createAppDatabase(File(directory, "computer.db"), "id-computer")
    }

    @AfterTest
    fun tearDown() {
        server?.stop()
        scope.cancel()
        telefonoDb.close()
        computerDb.close()
        directory.deleteRecursively()
    }

    private fun avviaServer(atteso: CountDownLatch? = null): Int {
        val istanza = SyncServer(
            identity = computer,
            peers = computerDb.peerDao(),
            engine = SyncEngine(computerDb.eventDao(), RecordingAlarmScheduler()) { ORA_COMPUTER },
            approval = { code, _ -> codici["computer"] = code; true },
            now = { 1_800_000_000_000L },
            scope = scope,
            onEvent = { eventiServer += it; atteso?.countDown() }
        )
        server = istanza
        // Porta a zero: la sceglie il sistema, così due esecuzioni non si pestano i piedi.
        return istanza.start(requestedPort = 0)
    }

    private fun evento(title: String, updatedAt: Long, uuid: String = newUuid()) = EventEntity(
        title = title,
        dateTimeMillis = 1_900_000_000_000L,
        advanceMinutes = 15,
        uuid = uuid,
        updatedAt = updatedAt,
        origin = "id-telefono"
    )

    @Test
    fun `un dispositivo si associa e poi sincronizza attraverso il socket`() = runBlocking {
        val associato = CountDownLatch(1)
        val porta = avviaServer(associato)

        val esito = SyncClient.pair(
            host = "127.0.0.1",
            port = porta,
            identity = telefono,
            approval = { code, _ -> codici["telefono"] = code; true },
            nowMillis = 1_800_000_000_000L
        )

        val suTelefono = assertIs<PairingOutcome.Paired>(esito).peer
        telefonoDb.peerDao().upsert(suTelefono)
        assertTrue(associato.await(5, TimeUnit.SECONDS), "il server deve concludere l'associazione")

        assertEquals(
            codici["telefono"],
            codici["computer"],
            "l'utente confronta i due schermi: i codici devono coincidere"
        )
        val suComputer = assertNotNull(computerDb.peerDao().getById("id-telefono"))
        assertEquals(suTelefono.sharedSecret, suComputer.sharedSecret)
        assertEquals("Computer", suTelefono.displayName)

        // Ora la sincronizzazione vera, sulla stessa porta ma con una connessione nuova.
        telefonoDb.eventDao().insert(evento("Dentista", 100))
        computerDb.eventDao().insert(evento("Bollette", 150))
        val sincronizzato = CountDownLatch(1)
        eventiServer.clear()
        server?.stop()
        val portaSync = avviaServer(sincronizzato)

        val risultato = SyncClient.sync(
            host = "127.0.0.1",
            port = portaSync,
            identity = telefono,
            peers = telefonoDb.peerDao(),
            engine = SyncEngine(telefonoDb.eventDao(), RecordingAlarmScheduler()) { ORA_TELEFONO },
            nowMillis = CONTATTO_TELEFONO
        )

        assertIs<SyncOutcome.Completed>(risultato)
        assertTrue(sincronizzato.await(5, TimeUnit.SECONDS))
        assertEquals(
            listOf("Bollette", "Dentista"),
            telefonoDb.eventDao().getAll().map { it.title }.sorted()
        )
        assertEquals(
            listOf("Bollette", "Dentista"),
            computerDb.eventDao().getAll().map { it.title }.sorted()
        )
    }

    @Test
    fun `il watermark e l'indirizzo restano salvati per il giro successivo`() = runBlocking {
        val associato = CountDownLatch(1)
        val porta = avviaServer(associato)
        val esito = SyncClient.pair(
            "127.0.0.1", porta, telefono,
            { code, _ -> codici["telefono"] = code; true }, 1_800_000_000_000L
        )
        telefonoDb.peerDao().upsert(assertIs<PairingOutcome.Paired>(esito).peer)
        associato.await(5, TimeUnit.SECONDS)

        telefonoDb.eventDao().insert(evento("Dentista", 100))
        val sincronizzato = CountDownLatch(1)
        server?.stop()
        val portaSync = avviaServer(sincronizzato)
        SyncClient.sync(
            "127.0.0.1", portaSync, telefono, telefonoDb.peerDao(),
            SyncEngine(telefonoDb.eventDao(), RecordingAlarmScheduler()) { ORA_TELEFONO },
            CONTATTO_TELEFONO
        )
        sincronizzato.await(5, TimeUnit.SECONDS)

        val salvatoSulTelefono = assertNotNull(telefonoDb.peerDao().getById("id-computer"))
        assertEquals(ORA_COMPUTER, salvatoSulTelefono.watermark, "il watermark è l'ora del peer")
        assertEquals(
            CONTATTO_TELEFONO,
            salvatoSulTelefono.lastContactAt,
            "l'ora del contatto è invece la propria: è quella che si mostra all'utente"
        )
        assertEquals("127.0.0.1", salvatoSulTelefono.lastHost, "e l'indirizzo, per ripartire da lì")
        assertEquals(portaSync, salvatoSulTelefono.lastPort)

        val salvatoSulComputer = assertNotNull(computerDb.peerDao().getById("id-telefono"))
        assertEquals(ORA_TELEFONO, salvatoSulComputer.watermark)
        assertEquals(1_800_000_000_000L, salvatoSulComputer.lastContactAt, "il server usa il proprio now")
        // Il telefono di questo test non ascolta: non c'è porta a cui richiamarlo, e registrare
        // quella effimera da cui è arrivata la connessione sarebbe peggio che non registrare nulla.
        assertNull(salvatoSulComputer.lastHost, "senza porta d'ascolto non c'è indirizzo utile")
        assertNull(salvatoSulComputer.lastPort)
    }

    /**
     * Il caso opposto: chi chiama dichiara la porta su cui ascolta a sua volta, e il server la
     * registra al posto della porta effimera del socket. Senza, il tentativo di richiamarlo
     * finirebbe contro una porta chiusa da tempo.
     */
    @Test
    fun `chi ascolta viene registrato con la porta che ha dichiarato, non con quella effimera`() =
        runBlocking {
            val associato = CountDownLatch(1)
            val porta = avviaServer(associato)
            val telefonoInAscolto = telefono.copy(listeningPort = 51000)

            val esito = SyncClient.pair(
                "127.0.0.1", porta, telefonoInAscolto,
                { code, _ -> codici["telefono"] = code; true }, 1_800_000_000_000L
            )
            telefonoDb.peerDao().upsert(assertIs<PairingOutcome.Paired>(esito).peer)
            associato.await(5, TimeUnit.SECONDS)

            val sincronizzato = CountDownLatch(1)
            server?.stop()
            val portaSync = avviaServer(sincronizzato)
            SyncClient.sync(
                "127.0.0.1", portaSync, telefonoInAscolto, telefonoDb.peerDao(),
                SyncEngine(telefonoDb.eventDao(), RecordingAlarmScheduler()) { ORA_TELEFONO },
                CONTATTO_TELEFONO
            )
            sincronizzato.await(5, TimeUnit.SECONDS)

            val salvato = assertNotNull(computerDb.peerDao().getById("id-telefono"))
            assertEquals(51000, salvato.lastPort, "la porta dichiarata nel saluto, non socket.port")
            assertEquals("127.0.0.1", salvato.lastHost)
    }

    @Test
    fun `un dispositivo mai associato viene respinto dal server`() = runBlocking {
        val porta = avviaServer()
        // Il telefono crede di conoscere il computer, ma il computer non ha mai visto il telefono.
        telefonoDb.peerDao().upsert(
            it.agoldoni.reminder.data.PeerEntity(
                deviceId = "id-computer",
                displayName = "Computer",
                sharedSecret = randomBytes(32).toHex(),
                pairedAt = 1_800_000_000_000L
            )
        )

        val risultato = SyncClient.sync(
            "127.0.0.1", porta, telefono, telefonoDb.peerDao(),
            SyncEngine(telefonoDb.eventDao(), RecordingAlarmScheduler()) { ORA_TELEFONO },
            CONTATTO_TELEFONO
        )

        assertEquals(Session.NON_ASSOCIATO, assertIs<SyncOutcome.Refused>(risultato).reason)
    }

    @Test
    fun `un indirizzo irraggiungibile diventa un messaggio, non un'eccezione`() = runBlocking {
        val risultato = SyncClient.sync(
            host = "127.0.0.1",
            port = 1, // porta riservata: la connessione viene rifiutata subito
            identity = telefono,
            peers = telefonoDb.peerDao(),
            engine = SyncEngine(telefonoDb.eventDao(), RecordingAlarmScheduler()) { ORA_TELEFONO },
            nowMillis = CONTATTO_TELEFONO
        )

        val fallito = assertIs<SyncOutcome.Failed>(risultato)
        assertTrue("127.0.0.1" in fallito.reason, "il messaggio deve dire chi non risponde: ${fallito.reason}")
    }

    private companion object {
        const val ORA_TELEFONO = 5_000L
        const val ORA_COMPUTER = 9_000L

        /** Ora locale del telefono al momento del contatto: distinta dai watermark di proposito. */
        const val CONTATTO_TELEFONO = 1_700_000_000_000L
    }
}

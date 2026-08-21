package it.agoldoni.reminder.sync

import it.agoldoni.reminder.data.AppDatabase
import it.agoldoni.reminder.data.EventEntity
import it.agoldoni.reminder.data.PeerEntity
import it.agoldoni.reminder.platform.createAppDatabase
import it.agoldoni.reminder.platform.newUuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Lo scambio completo su due database Room veri e un canale cifrato vero. I test del motore
 * mostrano che la regola converge; questo mostra che converge anche passando dal filo — cioè con
 * il vincolo di unicità dell'`uuid` attivo, la serializzazione di mezzo e il canale che rifiuta
 * ciò che non torna.
 */
class SyncConversationTest {

    private lateinit var directory: File
    private lateinit var telefono: AppDatabase
    private lateinit var computer: AppDatabase
    private val wire = Wire()

    private val segreto = randomBytes(32).toHex()

    /** Orologi fissi e distinti: il watermark è l'ora del mittente, e così è verificabile. */
    private val oraTelefono = 5_000L
    private val oraComputer = 9_000L

    @BeforeTest
    fun setUp() {
        directory = File.createTempFile("promemoria-sync", "").let {
            it.delete(); it.apply { mkdirs() }
        }
        telefono = createAppDatabase(File(directory, "telefono.db"), "id-telefono")
        computer = createAppDatabase(File(directory, "computer.db"), "id-computer")
    }

    @AfterTest
    fun tearDown() {
        wire.close()
        telefono.close()
        computer.close()
        directory.deleteRecursively()
    }

    private fun evento(title: String, updatedAt: Long, uuid: String = newUuid()) = EventEntity(
        title = title,
        dateTimeMillis = 1_900_000_000_000L,
        advanceMinutes = 15,
        uuid = uuid,
        updatedAt = updatedAt,
        origin = "origine"
    )

    private fun peer(deviceId: String, displayName: String) = PeerEntity(
        deviceId = deviceId,
        displayName = displayName,
        sharedSecret = segreto,
        pairedAt = 1_800_000_000_000L
    )

    /** Un giro completo: sessione autenticata, canale cifrato, scambio in entrambi i versi. */
    private fun sincronizza(
        sinceTelefono: Long = 0,
        sinceComputer: Long = 0
    ): Pair<SyncResult, SyncResult> = runBlocking {
        val latoTelefono = async(Dispatchers.IO) {
            val esito = Session.initiate(
                wire.initiatorInput, wire.initiatorOutput,
                LocalIdentity("id-telefono", "Telefono")
            ) { peer("id-computer", "Computer") }
            val canale = assertIs<SessionOutcome.Open>(esito).channel
            SyncConversation.initiate(
                canale,
                SyncEngine(telefono.eventDao(), RecordingAlarmScheduler()) { oraTelefono },
                sinceTelefono
            )
        }
        val latoComputer = async(Dispatchers.IO) {
            val esito = Session.accept(
                wire.responderInput, wire.responderOutput,
                LocalIdentity("id-computer", "Computer")
            ) { peer("id-telefono", "Telefono") }
            val canale = assertIs<SessionOutcome.Open>(esito).channel
            SyncConversation.accept(
                canale,
                SyncEngine(computer.eventDao(), RecordingAlarmScheduler()) { oraComputer },
                sinceComputer
            )
        }
        latoTelefono.await() to latoComputer.await()
    }

    @Test
    fun `gli eventi passano nei due versi in un solo giro`() = runBlocking {
        telefono.eventDao().insert(evento("Dentista", 100))
        computer.eventDao().insert(evento("Bollette", 150))

        val (daTelefono, daComputer) = sincronizza()

        assertEquals(
            listOf("Bollette", "Dentista"),
            telefono.eventDao().getAll().map { it.title }.sorted()
        )
        assertEquals(
            listOf("Bollette", "Dentista"),
            computer.eventDao().getAll().map { it.title }.sorted()
        )
        assertEquals(1, daTelefono.received.inserted)
        assertEquals(1, daComputer.received.inserted)
        assertEquals(
            oraComputer,
            daTelefono.watermark,
            "il watermark verso il computer è l'ora dichiarata dal computer"
        )
        assertEquals(oraTelefono, daComputer.watermark)
    }

    @Test
    fun `un secondo giro non duplica nulla`() = runBlocking {
        telefono.eventDao().insert(evento("Dentista", 100))
        val (primoTelefono, primoComputer) = sincronizza()

        val secondoFilo = Wire()
        val (secondoTelefono, secondoComputer) = try {
            runBlocking {
                val a = async(Dispatchers.IO) {
                    val esito = Session.initiate(
                        secondoFilo.initiatorInput, secondoFilo.initiatorOutput,
                        LocalIdentity("id-telefono", "Telefono")
                    ) { peer("id-computer", "Computer") }
                    SyncConversation.initiate(
                        assertIs<SessionOutcome.Open>(esito).channel,
                        SyncEngine(telefono.eventDao(), RecordingAlarmScheduler()) { oraTelefono },
                        primoTelefono.watermark
                    )
                }
                val b = async(Dispatchers.IO) {
                    val esito = Session.accept(
                        secondoFilo.responderInput, secondoFilo.responderOutput,
                        LocalIdentity("id-computer", "Computer")
                    ) { peer("id-telefono", "Telefono") }
                    SyncConversation.accept(
                        assertIs<SessionOutcome.Open>(esito).channel,
                        SyncEngine(computer.eventDao(), RecordingAlarmScheduler()) { oraComputer },
                        primoComputer.watermark
                    )
                }
                a.await() to b.await()
            }
        } finally {
            secondoFilo.close()
        }

        assertEquals(1, computer.eventDao().getAll().size, "un solo Dentista, non due")
        assertEquals(0, secondoComputer.received.inserted)
        assertEquals(0, secondoTelefono.received.inserted)
        // Il rinvio dell'ultimo millisecondo è voluto: quello che conta è che venga scartato.
        assertTrue(secondoComputer.received.updated == 0)
    }

    @Test
    fun `una cancellazione attraversa il filo e resta come tombstone`() = runBlocking {
        val id = telefono.eventDao().insert(evento("Dentista", 100, uuid = "u-condiviso"))
        sincronizza()
        assertEquals(1, computer.eventDao().getAll().size)

        telefono.eventDao().softDelete(id, nowMillis = 200)
        val secondoFilo = Wire()
        try {
            runBlocking {
                val a = async(Dispatchers.IO) {
                    val esito = Session.initiate(
                        secondoFilo.initiatorInput, secondoFilo.initiatorOutput,
                        LocalIdentity("id-telefono", "Telefono")
                    ) { peer("id-computer", "Computer") }
                    SyncConversation.initiate(
                        assertIs<SessionOutcome.Open>(esito).channel,
                        SyncEngine(telefono.eventDao(), RecordingAlarmScheduler()) { oraTelefono },
                        100
                    )
                }
                val b = async(Dispatchers.IO) {
                    val esito = Session.accept(
                        secondoFilo.responderInput, secondoFilo.responderOutput,
                        LocalIdentity("id-computer", "Computer")
                    ) { peer("id-telefono", "Telefono") }
                    SyncConversation.accept(
                        assertIs<SessionOutcome.Open>(esito).channel,
                        SyncEngine(computer.eventDao(), RecordingAlarmScheduler()) { oraComputer },
                        0
                    )
                }
                a.await(); b.await()
            }
        } finally {
            secondoFilo.close()
        }

        assertTrue(computer.eventDao().getAll().isEmpty(), "l'app non deve più vederlo")
        assertTrue(
            assertNotNull(computer.eventDao().getByUuid("u-condiviso")).deleted,
            "ma la riga resta, altrimenti al giro dopo risorgerebbe"
        )
    }

    @Test
    fun `il contenuto dei promemoria non è leggibile sul filo`() = runBlocking {
        telefono.eventDao().insert(evento("Visita cardiologica", 100))

        sincronizza()

        val intercettato = wire.fromInitiator.toByteArray().decodeToString()
        assertTrue(
            "Visita" !in intercettato && "cardiologica" !in intercettato,
            "il titolo di un promemoria non deve comparire in chiaro sulla rete"
        )
    }
}

package it.agoldoni.reminder.sync

import it.agoldoni.reminder.data.EventEntity
import it.agoldoni.reminder.data.FakeEventDao
import it.agoldoni.reminder.platform.AlarmScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Registra le sveglie messe e tolte, per poter verificare che la rete le rimetta in riga. */
class RecordingAlarmScheduler : AlarmScheduler {
    val scheduled = mutableListOf<EventEntity>()
    val cancelled = mutableListOf<Long>()

    override fun schedule(event: EventEntity) {
        scheduled += event
    }

    override fun cancel(eventId: Long) {
        cancelled += eventId
    }
}

/**
 * Due dispositivi simulati, senza rete in mezzo: si scambiano lotti nello stesso ordine della
 * conversazione vera, ma i cicli sono guidati dal test. È il modo di vedere la convergenza senza
 * che un fallimento possa venire da un socket.
 *
 * Ogni scrittura avanza l'orologio del dispositivo, come nell'app: `updatedAt` e il watermark
 * vengono dallo stesso clock, e un test che li slegasse verificherebbe una situazione impossibile.
 */
private class Dispositivo(
    val nome: String,
    /** Orologio proprio: serve a mettere alla prova due dispositivi che non sono d'accordo sull'ora. */
    var orologio: Long = 1_000L
) {
    val dao = FakeEventDao()
    val alarms = RecordingAlarmScheduler()
    val engine = SyncEngine(dao, alarms) { orologio }
    var watermark = 0L

    suspend fun crea(uuid: String, title: String, quando: Long = 1_900_000_000_000L): Long {
        orologio += 10
        return dao.insert(
            EventEntity(
                title = title,
                dateTimeMillis = quando,
                advanceMinutes = 15,
                uuid = uuid,
                updatedAt = orologio,
                origin = nome
            )
        )
    }

    suspend fun rinomina(uuid: String, title: String) {
        orologio += 10
        val esistente = assertNotNull(dao.getByUuid(uuid))
        dao.update(esistente.copy(title = title, updatedAt = orologio))
    }

    suspend fun cancella(uuid: String) {
        orologio += 10
        dao.softDelete(assertNotNull(dao.getByUuid(uuid)).id, nowMillis = orologio)
    }

    suspend fun completa(uuid: String) {
        orologio += 10
        dao.markCompleted(assertNotNull(dao.getByUuid(uuid)).id, nowMillis = orologio)
    }

    /**
     * Un giro completo, nell'ordine della conversazione vera: entrambi calcolano cosa mandare
     * prima che l'altro abbia applicato qualcosa.
     */
    suspend fun sincronizzaCon(altro: Dispositivo) {
        val mio = engine.changesSince(altro.watermark)
        val suo = altro.engine.changesSince(watermark)

        altro.engine.apply(mio.events)
        altro.watermark = mio.upTo
        engine.apply(suo.events)
        watermark = suo.upTo
    }

    val visibili: List<EventEntity> get() = dao.events.filterNot { it.deleted }
    val titoli: Map<String, String> get() = visibili.associate { it.uuid to it.title }
}

class SyncEngineTest {

    @Test
    fun `un evento creato su un dispositivo compare sull'altro`() = runTest {
        val telefono = Dispositivo("telefono")
        val computer = Dispositivo("computer")
        telefono.crea("u1", "Dentista")

        telefono.sincronizzaCon(computer)

        assertEquals(listOf("Dentista"), computer.visibili.map { it.title })
        assertEquals(telefono.orologio, computer.watermark, "il watermark è l'ora del mittente")
    }

    @Test
    fun `un evento modificato aggiorna l'altro senza duplicarsi`() = runTest {
        val telefono = Dispositivo("telefono")
        val computer = Dispositivo("computer")
        telefono.crea("u1", "Dentista")
        telefono.sincronizzaCon(computer)

        telefono.rinomina("u1", "Dentista alle 16")
        telefono.sincronizzaCon(computer)

        assertEquals(1, computer.visibili.size, "il confronto è per uuid, non per id")
        assertEquals("Dentista alle 16", computer.visibili.single().title)
    }

    /** TC-03 — idempotenza. */
    @Test
    fun `applicare due volte lo stesso lotto non duplica niente`() = runTest {
        val telefono = Dispositivo("telefono")
        val computer = Dispositivo("computer")
        telefono.crea("u1", "Dentista")

        val lotto = telefono.engine.changesSince(0).events
        val primo = computer.engine.apply(lotto)
        val secondo = computer.engine.apply(lotto)

        assertEquals(1, primo.inserted)
        assertEquals(0, secondo.inserted)
        assertEquals(1, secondo.ignored, "la seconda passata non ha nulla da fare")
        assertEquals(1, computer.dao.events.size)
    }

    /** TC-02 — un evento cancellato non riappare. */
    @Test
    fun `una cancellazione si propaga e non torna indietro al ciclo successivo`() = runTest {
        val telefono = Dispositivo("telefono")
        val computer = Dispositivo("computer")
        telefono.crea("u1", "Dentista")
        telefono.sincronizzaCon(computer)
        assertEquals(1, computer.visibili.size)

        telefono.cancella("u1")
        telefono.sincronizzaCon(computer)

        assertTrue(computer.visibili.isEmpty(), "la cancellazione deve arrivare")
        assertTrue(
            assertNotNull(computer.dao.getByUuid("u1")).deleted,
            "e restare come tombstone, non sparire"
        )

        // Altri due giri: se il tombstone fosse stato rimosso invece che conservato, il computer
        // rimanderebbe l'evento al telefono e lo farebbe risorgere su entrambi.
        telefono.sincronizzaCon(computer)
        computer.sincronizzaCon(telefono)

        assertTrue(computer.visibili.isEmpty())
        assertTrue(telefono.visibili.isEmpty())
    }

    /** TC-01 — modifiche concorrenti allo stesso evento. */
    @Test
    fun `modifiche concorrenti convergono sullo stesso stato senza perdere gli altri eventi`() =
        runTest {
            val telefono = Dispositivo("telefono")
            val computer = Dispositivo("computer")
            telefono.crea("u1", "Riunione")
            telefono.sincronizzaCon(computer)
            telefono.crea("u2", "Solo sul telefono")
            computer.crea("u3", "Solo sul computer")

            // Stesso evento toccato sui due lati mentre erano scollegati: il computer per ultimo.
            telefono.rinomina("u1", "Riunione alle 9")
            computer.orologio = telefono.orologio + 100
            computer.rinomina("u1", "Riunione alle 11")

            telefono.sincronizzaCon(computer)
            computer.sincronizzaCon(telefono)

            assertEquals(telefono.titoli, computer.titoli, "i due lati devono finire identici")
            assertEquals("Riunione alle 11", telefono.titoli["u1"], "vince la modifica più recente")
            assertEquals("Solo sul telefono", telefono.titoli["u2"], "gli altri eventi restano")
            assertEquals("Solo sul computer", telefono.titoli["u3"])
        }

    @Test
    fun `dopo la sincronizzazione gli allarmi sono riprogrammati`() = runTest {
        val telefono = Dispositivo("telefono")
        val computer = Dispositivo("computer")
        telefono.crea("u1", "Dentista")

        telefono.sincronizzaCon(computer)

        assertEquals(
            listOf("Dentista"),
            computer.alarms.scheduled.map { it.title },
            "un evento futuro arrivato dalla rete deve avere la sua sveglia"
        )
        assertEquals(
            computer.visibili.single().id,
            computer.alarms.scheduled.single().id,
            "con l'id locale, non con quello del mittente"
        )
    }

    @Test
    fun `un evento completato dall'altro lato perde l'allarme`() = runTest {
        val telefono = Dispositivo("telefono")
        val computer = Dispositivo("computer")
        telefono.crea("u1", "Dentista")
        telefono.sincronizzaCon(computer)
        val idLocale = computer.visibili.single().id
        computer.alarms.scheduled.clear()

        telefono.completa("u1")
        telefono.sincronizzaCon(computer)

        assertTrue(idLocale in computer.alarms.cancelled, "l'allarme va tolto")
        assertTrue(
            computer.alarms.scheduled.isEmpty(),
            "e non rimesso: l'evento non è più da ricordare"
        )
    }

    /**
     * Il caso che ha fatto scegliere questo protocollo: due dispositivi non d'accordo sull'ora.
     * Se il watermark venisse dal massimo `updatedAt` ricevuto, l'evento del dispositivo avanti
     * rimbalzerebbe indietro, alzerebbe il watermark dell'altro oltre il proprio tempo, e le
     * modifiche successive di quest'ultimo non partirebbero mai più.
     */
    @Test
    fun `un orologio avanti sull'altro dispositivo non blocca le modifiche locali`() = runTest {
        val telefono = Dispositivo("telefono", orologio = 1_000L)
        val computer = Dispositivo("computer", orologio = 3_600_000L)
        telefono.crea("u1", "Dentista")
        computer.crea("u2", "Bollette")

        telefono.sincronizzaCon(computer)

        assertEquals(2, telefono.visibili.size)
        assertEquals(2, computer.visibili.size)
        assertEquals(
            telefono.orologio,
            computer.watermark,
            "il computer registra l'ora del telefono, non la propria"
        )

        // Modifica sul dispositivo con l'orologio indietro di un'ora: deve comunque partire.
        telefono.rinomina("u1", "Dentista alle 16")
        telefono.sincronizzaCon(computer)

        assertEquals("Dentista alle 16", computer.titoli["u1"])
    }

    @Test
    fun `chi riceve non si rimanda indietro gli eventi appena ricevuti`() = runTest {
        val telefono = Dispositivo("telefono")
        val computer = Dispositivo("computer")
        telefono.crea("u1", "Dentista")
        computer.crea("u2", "Bollette")

        telefono.sincronizzaCon(computer)

        // Secondo giro: nessuno dei due deve avere qualcosa di nuovo da applicare.
        val delTelefono = telefono.engine.changesSince(computer.watermark)
        val delComputer = computer.engine.changesSince(telefono.watermark)
        assertEquals(0, computer.engine.apply(delTelefono.events).applied)
        assertEquals(0, telefono.engine.apply(delComputer.events).applied)
    }
}

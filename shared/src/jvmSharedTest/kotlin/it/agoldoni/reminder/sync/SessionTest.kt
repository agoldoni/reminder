package it.agoldoni.reminder.sync

import it.agoldoni.reminder.data.PeerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionTest {

    private val wire = Wire()

    private val telefono = LocalIdentity("id-telefono", "Telefono")
    private val computer = LocalIdentity("id-computer", "Computer")

    /** Lo stesso segreto sui due lati, come l'avrebbe lasciato un'associazione riuscita. */
    private val segreto = randomBytes(32).toHex()

    private fun peerNoto(deviceId: String, displayName: String) = PeerEntity(
        deviceId = deviceId,
        displayName = displayName,
        sharedSecret = segreto,
        pairedAt = 1_800_000_000_000L
    )

    @AfterTest
    fun tearDown() = wire.close()

    private fun apri(
        conosciutoDalTelefono: PeerEntity? = peerNoto("id-computer", "Computer"),
        conosciutoDalComputer: PeerEntity? = peerNoto("id-telefono", "Telefono"),
        versioneIniziatore: Int = PROTOCOL_VERSION
    ): Pair<SessionOutcome, SessionOutcome> = runBlocking {
        val iniziatore = async(Dispatchers.IO) {
            if (versioneIniziatore != PROTOCOL_VERSION) {
                // Un dispositivo con un'altra versione: si presenta e basta.
                wire.initiatorOutput.sendMessage(
                    Hello(versioneIniziatore, telefono.deviceId, telefono.displayName)
                )
                val risposta = wire.initiatorInput.receiveMessage()
                SessionOutcome.Refused(assertIs<Rejected>(risposta).reason)
            } else {
                Session.initiate(
                    wire.initiatorInput, wire.initiatorOutput, telefono
                ) { conosciutoDalTelefono }
            }
        }
        val risponditore = async(Dispatchers.IO) {
            Session.accept(wire.responderInput, wire.responderOutput, computer) {
                conosciutoDalComputer
            }
        }
        iniziatore.await() to risponditore.await()
    }

    @Test
    fun `due dispositivi associati aprono il canale e si scambiano dati`() {
        val (latoTelefono, latoComputer) = apri()

        val canaleTelefono = assertIs<SessionOutcome.Open>(latoTelefono)
        val canaleComputer = assertIs<SessionOutcome.Open>(latoComputer)
        assertEquals("id-computer", canaleTelefono.peer.deviceId)
        assertEquals("id-telefono", canaleComputer.peer.deviceId)

        runBlocking {
            val invio = async(Dispatchers.IO) {
                canaleTelefono.channel.send("Dentista alle 15".encodeToByteArray())
                canaleTelefono.channel.send("secondo messaggio".encodeToByteArray())
            }
            val ricezione = async(Dispatchers.IO) {
                canaleComputer.channel.receive().decodeToString() to
                    canaleComputer.channel.receive().decodeToString()
            }
            invio.await()
            assertEquals("Dentista alle 15" to "secondo messaggio", ricezione.await())
        }
    }

    @Test
    fun `il traffico non è leggibile da chi lo intercetta`() {
        val (latoTelefono, latoComputer) = apri()
        val canaleTelefono = assertIs<SessionOutcome.Open>(latoTelefono)
        val canaleComputer = assertIs<SessionOutcome.Open>(latoComputer)

        runBlocking {
            val invio = async(Dispatchers.IO) {
                canaleTelefono.channel.send("Dentista alle 15".encodeToByteArray())
            }
            val ricezione = async(Dispatchers.IO) { canaleComputer.channel.receive() }
            invio.await()
            ricezione.await()
        }

        val intercettato = wire.fromInitiator.toByteArray().decodeToString()
        assertTrue(
            "Dentista" !in intercettato,
            "chi ascolta la rete non deve poter leggere il contenuto dei promemoria"
        )
        assertTrue(segreto !in wire.fromInitiator.toByteArray().toHex(), "né il segreto")
    }

    @Test
    fun `un dispositivo non associato viene rifiutato`() {
        val (latoTelefono, latoComputer) = apri(conosciutoDalComputer = null)

        assertEquals(Session.NON_ASSOCIATO, assertIs<SessionOutcome.Refused>(latoComputer).reason)
        assertEquals(Session.NON_ASSOCIATO, assertIs<SessionOutcome.Refused>(latoTelefono).reason)
    }

    @Test
    fun `un segreto diverso non apre il canale`() {
        val altroSegreto = peerNoto("id-telefono", "Telefono").copy(sharedSecret = randomBytes(32).toHex())

        val (latoTelefono, latoComputer) = apri(conosciutoDalComputer = altroSegreto)

        assertIs<SessionOutcome.Refused>(latoTelefono)
        assertIs<SessionOutcome.Refused>(latoComputer)
    }

    @Test
    fun `una versione di protocollo diversa viene rifiutata con un messaggio esplicito`() {
        val (latoTelefono, latoComputer) = apri(versioneIniziatore = PROTOCOL_VERSION + 1)

        val motivo = assertIs<SessionOutcome.Refused>(latoTelefono).reason
        assertTrue("$PROTOCOL_VERSION" in motivo && "${PROTOCOL_VERSION + 1}" in motivo,
            "il messaggio deve dire quali versioni non si parlano: $motivo")
        assertTrue("Aggiorna" in motivo, "e che cosa deve fare l'utente: $motivo")
        assertEquals(VERSIONE_INCOMPATIBILE, assertIs<SessionOutcome.Refused>(latoComputer).reason)
    }

    @Test
    fun `un frame alterato o ripetuto non viene accettato`() {
        val (latoTelefono, latoComputer) = apri()
        val mittente = assertIs<SessionOutcome.Open>(latoTelefono).channel
        val destinatario = assertIs<SessionOutcome.Open>(latoComputer).channel

        runBlocking {
            // Il primo messaggio passa; il secondo è una ripetizione byte per byte del primo,
            // che il numero di sequenza autenticato deve far rifiutare.
            val invio = async(Dispatchers.IO) {
                mittente.send("primo".encodeToByteArray())
                val primoFrame = wire.fromInitiator.toByteArray().let {
                    it.copyOfRange(it.size - FRAME_RIPETIBILE, it.size)
                }
                // Senza questo il frame ripetuto potrebbe essere malformato e verrebbe respinto
                // per la ragione sbagliata: quello che si vuole provare è che un frame **valido**
                // rigiocato non passa.
                assertEquals(
                    FRAME_RIPETIBILE - 4,
                    ((primoFrame[0].toInt() and 0xff) shl 24) or
                        ((primoFrame[1].toInt() and 0xff) shl 16) or
                        ((primoFrame[2].toInt() and 0xff) shl 8) or
                        (primoFrame[3].toInt() and 0xff),
                    "il frame estratto deve essere quello appena inviato, intero"
                )
                wire.initiatorOutput.write(primoFrame)
                wire.initiatorOutput.flush()
            }
            val ricezione = async(Dispatchers.IO) {
                assertEquals("primo", destinatario.receive().decodeToString())
                assertFailsWith<SyncProtocolException> { destinatario.receive() }
            }
            invio.await()
            ricezione.await()
        }
    }

    private companion object {
        /** 4 byte di lunghezza + "primo" cifrato + tag GCM da 16 byte. */
        const val FRAME_RIPETIBILE = 4 + 5 + 16
    }
}

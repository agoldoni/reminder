package it.agoldoni.reminder.sync

import it.agoldoni.reminder.data.PeerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

class PairingTest {

    private val wire = Wire()

    private val telefono = LocalIdentity(deviceId = "id-telefono", displayName = "Telefono")
    private val computer = LocalIdentity(deviceId = "id-computer", displayName = "Computer")

    @AfterTest
    fun tearDown() = wire.close()

    /** I codici visti dai due lati, per poterli confrontare come farebbe l'utente. */
    private val codici = mutableMapOf<String, String>()

    private fun approvazione(lato: String, conferma: Boolean = true): PairingApproval =
        { code, _ ->
            codici[lato] = code
            conferma
        }

    private fun associa(
        confermaTelefono: Boolean = true,
        confermaComputer: Boolean = true
    ): Pair<PairingOutcome, PairingOutcome> = runBlocking {
        val iniziatore = async(Dispatchers.IO) {
            Pairing.initiate(
                input = wire.initiatorInput,
                output = wire.initiatorOutput,
                identity = telefono,
                approval = approvazione("telefono", confermaTelefono),
                nowMillis = 1_800_000_000_000L
            )
        }
        val risponditore = async(Dispatchers.IO) {
            Pairing.accept(
                input = wire.responderInput,
                output = wire.responderOutput,
                identity = computer,
                approval = approvazione("computer", confermaComputer),
                nowMillis = 1_800_000_000_000L
            )
        }
        iniziatore.await() to risponditore.await()
    }

    @Test
    fun `i due lati vedono lo stesso codice e ricavano lo stesso segreto`() {
        val (daTelefono, daComputer) = associa()

        val suTelefono = assertIs<PairingOutcome.Paired>(daTelefono).peer
        val suComputer = assertIs<PairingOutcome.Paired>(daComputer).peer

        assertEquals(
            codici["telefono"],
            codici["computer"],
            "l'utente confronta i due schermi: i codici devono coincidere"
        )
        assertEquals(6, codici.getValue("telefono").length, "sei cifre, zeri iniziali compresi")
        assertEquals(
            suTelefono.sharedSecret,
            suComputer.sharedSecret,
            "senza lo stesso segreto le sessioni successive non si aprirebbero"
        )
        assertEquals(64, suTelefono.sharedSecret.length, "32 byte in esadecimale")

        // Ciascuno registra l'altro, non se stesso.
        assertEquals("id-computer", suTelefono.deviceId)
        assertEquals("Computer", suTelefono.displayName)
        assertEquals("id-telefono", suComputer.deviceId)
        assertEquals("Telefono", suComputer.displayName)
    }

    @Test
    fun `due associazioni distinte non producono lo stesso segreto`() {
        val primo = assertIs<PairingOutcome.Paired>(associa().first).peer.sharedSecret
        val altroFilo = Wire()
        val secondo = runBlocking {
            val a = async(Dispatchers.IO) {
                Pairing.initiate(
                    altroFilo.initiatorInput, altroFilo.initiatorOutput, telefono,
                    approvazione("telefono-2"), 1_800_000_000_000L
                )
            }
            val b = async(Dispatchers.IO) {
                Pairing.accept(
                    altroFilo.responderInput, altroFilo.responderOutput, computer,
                    approvazione("computer-2"), 1_800_000_000_000L
                )
            }
            b.await()
            assertIs<PairingOutcome.Paired>(a.await()).peer.sharedSecret
        }
        altroFilo.close()

        assertNotEquals(primo, secondo, "le chiavi dello scambio sono effimere a ogni associazione")
    }

    @Test
    fun `se un lato non conferma il codice non viene salvata nessuna associazione`() {
        val (daTelefono, daComputer) = associa(confermaComputer = false)

        assertIs<PairingOutcome.Refused>(daTelefono)
        assertIs<PairingOutcome.Refused>(daComputer)
        assertTrue(
            (daTelefono as PairingOutcome.Refused).reason.isNotBlank(),
            "il motivo va mostrato all'utente"
        )
    }

    @Test
    fun `il segreto dell'associazione apre davvero le sessioni successive`() {
        val (daTelefono, daComputer) = associa()
        val suTelefono = assertIs<PairingOutcome.Paired>(daTelefono).peer
        val suComputer = assertIs<PairingOutcome.Paired>(daComputer).peer

        // Seconda connessione, come avverrebbe alla sincronizzazione successiva: le chiavi
        // effimere dell'associazione non esistono più, resta solo il segreto salvato.
        val secondoFilo = Wire()
        try {
            runBlocking {
                val lato1 = async(Dispatchers.IO) {
                    Session.initiate(
                        secondoFilo.initiatorInput, secondoFilo.initiatorOutput, telefono
                    ) { suTelefono }
                }
                val lato2 = async(Dispatchers.IO) {
                    Session.accept(
                        secondoFilo.responderInput, secondoFilo.responderOutput, computer
                    ) { suComputer }
                }
                val canale1 = assertIs<SessionOutcome.Open>(lato1.await()).channel
                val canale2 = assertIs<SessionOutcome.Open>(lato2.await()).channel

                val invio = async(Dispatchers.IO) { canale1.send("ciao".encodeToByteArray()) }
                val ricezione = async(Dispatchers.IO) { canale2.receive().decodeToString() }
                invio.await()
                assertEquals("ciao", ricezione.await())
            }
        } finally {
            secondoFilo.close()
        }
    }

    @Test
    fun `la chiave pubblica dell'altro non viaggia mai in chiaro come segreto`() {
        val (daTelefono, _) = associa()
        val segreto = assertIs<PairingOutcome.Paired>(daTelefono).peer.sharedSecret

        val trafficoIniziatore = wire.fromInitiator.toByteArray().toHex()
        val trafficoRisponditore = wire.fromResponder.toByteArray().toHex()

        assertTrue(
            segreto !in trafficoIniziatore && segreto !in trafficoRisponditore,
            "il segreto si deriva dallo scambio: non deve comparire sul filo"
        )
        assertTrue(
            codici.getValue("telefono") !in wire.fromInitiator.toByteArray().decodeToString(),
            "nemmeno il codice di conferma viaggia: è per questo che protegge"
        )
    }
}

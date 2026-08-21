package it.agoldoni.reminder.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class PeerDirectoryTest {

    /**
     * Il directory tiene una sottoscrizione viva finché vive il suo scope: va creato sul
     * `backgroundScope`, altrimenti `runTest` aspetta invano che finisca.
     */
    private fun TestScope.directory(discovery: FakeDiscovery = FakeDiscovery()) =
        discovery to PeerDirectory(discovery, backgroundScope)

    @Test
    fun `l'elenco riporta i dispositivi annunciati`() = runTest {
        val (discovery, directory) = directory()
        directory.start()

        discovery.emit(mdnsPeer("Portatile", "192.168.1.10"))

        runCurrent()

        assertEquals(listOf("Portatile"), directory.peers.value.map { it.displayName })
        assertEquals(DiscoveryStatus.Searching, directory.status.value)
    }

    @Test
    fun `un indirizzo inserito a mano compare accanto a quelli trovati`() = runTest {
        val (discovery, directory) = directory()
        directory.start()
        discovery.emit(mdnsPeer("Portatile", "192.168.1.10"))

        val aggiunto = directory.addManual("192.168.1.42", 8765).getOrThrow()
        runCurrent()

        assertEquals(PeerSource.MANUAL, aggiunto.source)
        assertEquals("192.168.1.42:8765", aggiunto.displayName)
        assertNull(aggiunto.deviceId, "di un indirizzo digitato non si conosce ancora l'identità")
        assertEquals(
            listOf("Portatile", "192.168.1.42:8765"),
            directory.peers.value.map { it.displayName },
            "prima quelli trovati da soli, poi quelli digitati"
        )
    }

    @Test
    fun `l'annuncio ha la meglio sull'indirizzo digitato uguale`() = runTest {
        val (discovery, directory) = directory()
        directory.start()
        directory.addManual("192.168.1.10", 8765)

        discovery.emit(mdnsPeer("Portatile", "192.168.1.10", port = 8765))

        runCurrent()

        val elenco = directory.peers.value
        assertEquals(1, elenco.size, "lo stesso indirizzo non deve comparire due volte")
        assertEquals("Portatile", elenco.single().displayName)
        assertEquals(PeerSource.MDNS, elenco.single().source)
    }

    @Test
    fun `stessa porta diversa da quella digitata restano due voci`() = runTest {
        val (discovery, directory) = directory()
        directory.start()
        directory.addManual("192.168.1.10", 9000)

        discovery.emit(mdnsPeer("Portatile", "192.168.1.10", port = 8765))

        runCurrent()

        assertEquals(2, directory.peers.value.size)
    }

    @Test
    fun `un indirizzo digitato si può togliere`() = runTest {
        val (_, directory) = directory()
        val aggiunto = directory.addManual("192.168.1.42", 8765).getOrThrow()

        directory.removeManual(aggiunto)
        runCurrent()

        assertTrue(directory.peers.value.isEmpty())
    }

    @Test
    fun `gli indirizzi malformati vengono rifiutati con un messaggio`() = runTest {
        val (_, directory) = directory()

        val vuoto = directory.addManual("   ", 8765)
        val conSpazi = directory.addManual("192.168.1 .42", 8765)
        val portaZero = directory.addManual("192.168.1.42", 0)
        val portaAlta = directory.addManual("192.168.1.42", 70000)

        assertEquals("Indirizzo mancante.", vuoto.exceptionOrNull()?.message)
        assertEquals("L'indirizzo non può contenere spazi.", conSpazi.exceptionOrNull()?.message)
        assertEquals("La porta deve essere fra 1 e 65535.", portaZero.exceptionOrNull()?.message)
        assertEquals("La porta deve essere fra 1 e 65535.", portaAlta.exceptionOrNull()?.message)
        runCurrent()
        assertTrue(directory.peers.value.isEmpty(), "nessuno dei quattro deve entrare nell'elenco")
    }

    @Test
    fun `lo stesso indirizzo non si aggiunge due volte`() = runTest {
        val (_, directory) = directory()
        directory.addManual("192.168.1.42", 8765)

        val secondo = directory.addManual(" 192.168.1.42 ", 8765)

        runCurrent()

        assertEquals("Questo indirizzo è già nell'elenco.", secondo.exceptionOrNull()?.message)
        assertEquals(1, directory.peers.value.size)
    }

    @Test
    fun `quando mDNS non passa lo stato lo dice e l'inserimento manuale resta possibile`() = runTest {
        val (discovery, directory) = directory()
        directory.start()

        discovery.failWith("Questa rete non lascia passare la ricerca automatica.")

        val stato = directory.status.value
        assertTrue(stato is DiscoveryStatus.Unavailable)
        assertTrue(stato.message.isNotBlank(), "il messaggio va mostrato all'utente")
        assertTrue(directory.addManual("192.168.1.42", 8765).isSuccess)
        runCurrent()
        assertEquals(1, directory.peers.value.size)
    }

    @Test
    fun `avviare passa l'annuncio alla piattaforma`() = runTest {
        val (discovery, directory) = directory()
        val annuncio = Advertisement(deviceId = "abc", displayName = "Telefono", port = 8765)

        directory.start(annuncio)

        assertTrue(discovery.running)
        assertEquals(annuncio, discovery.startedWith)

        directory.stop()

        assertTrue(!discovery.running)
        assertEquals(DiscoveryStatus.Stopped, directory.status.value)
    }
}

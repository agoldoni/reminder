package it.agoldoni.reminder.ui.sync

import it.agoldoni.reminder.sync.DiscoveredPeer
import it.agoldoni.reminder.sync.DiscoveryStatus
import it.agoldoni.reminder.sync.PairedPeer
import it.agoldoni.reminder.sync.PairingApprovalRequest
import it.agoldoni.reminder.sync.PairingResult
import it.agoldoni.reminder.sync.PeerSource
import it.agoldoni.reminder.sync.SyncController
import it.agoldoni.reminder.sync.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeController : SyncController {
    override val status = MutableStateFlow(SyncStatus())
    private val _discovered = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val discovered: StateFlow<List<DiscoveredPeer>> = _discovered.asStateFlow()
    override val discoveryStatus = MutableStateFlow<DiscoveryStatus>(DiscoveryStatus.Stopped)
    override val paired = MutableStateFlow<List<PairedPeer>>(emptyList())

    var started = false
    var syncCount = 0
    var dissociati = mutableListOf<String>()
    var incoming: PairingApprovalRequest? = null

    /** Il codice che l'utente vedrebbe sull'altro schermo. */
    var codiceMostrato: String = "123456"

    override fun start() { started = true }
    override fun stop() { started = false }
    override fun beginInteractive() { started = true }
    override fun endInteractive() { started = false }
    override fun enable() { started = true }
    override fun disable() { started = false }
    override suspend fun syncNow() { syncCount++ }

    override suspend fun pair(
        peer: DiscoveredPeer,
        approval: PairingApprovalRequest
    ): PairingResult = if (approval(codiceMostrato, peer.displayName)) {
        PairingResult.Paired(PairedPeer(peer.deviceId ?: "ignoto", peer.displayName, 0, null, null))
    } else {
        PairingResult.Refused("Associazione annullata: il codice non è stato confermato.")
    }

    override suspend fun unpair(deviceId: String) { dissociati += deviceId }
    override fun addManualPeer(host: String, port: Int): Result<DiscoveredPeer> =
        if (port in 1..65535) {
            Result.success(DiscoveredPeer(null, "$host:$port", host, port, PeerSource.MANUAL))
        } else {
            Result.failure(IllegalArgumentException("La porta deve essere fra 1 e 65535."))
        }

    override fun onIncomingPairing(approval: PairingApprovalRequest?) { incoming = approval }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModelTest {

    private val controller = FakeController()
    private val viewModel = SyncViewModel(controller)

    /** `viewModelScope` gira su `Dispatchers.Main`, che sulla JVM di test non esiste da sé. */
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val trovato = DiscoveredPeer(
        deviceId = "id-computer",
        displayName = "Computer",
        host = "192.168.1.10",
        port = 47653,
        source = PeerSource.MDNS
    )

    @Test
    fun `associarsi mostra il codice e aspetta che l'utente confronti`() = runTest(dispatcher) {
        viewModel.associa(trovato)
        runCurrent()

        val richiesta = assertNotNull(viewModel.prompt.value, "il codice va mostrato")
        assertEquals("123456", richiesta.code)
        assertEquals("Computer", richiesta.peerName)
        assertFalse(richiesta.incoming, "qui è l'utente ad aver iniziato")
        assertNull(viewModel.message.value, "finché non risponde non è successo niente")

        viewModel.rispondiAlConfronto(true)
        runCurrent()

        assertNull(viewModel.prompt.value, "il dialogo si chiude")
        assertEquals("Computer associato.", viewModel.message.value)
    }

    @Test
    fun `rispondere che il codice non coincide annulla l'associazione`() = runTest(dispatcher) {
        viewModel.associa(trovato)
        runCurrent()

        viewModel.rispondiAlConfronto(false)
        runCurrent()

        assertEquals(
            "Associazione annullata: il codice non è stato confermato.",
            viewModel.message.value
        )
        assertNull(viewModel.prompt.value)
    }

    @Test
    fun `un'associazione in arrivo si può mostrare solo mentre la schermata è aperta`() = runTest(dispatcher) {
        assertNull(controller.incoming, "prima che la schermata si apra non c'è nessuno")

        viewModel.ascoltaAssociazioniInArrivo(true)
        assertNotNull(controller.incoming, "aperta la schermata, il codice si può mostrare")

        viewModel.ascoltaAssociazioniInArrivo(false)
        assertNull(
            controller.incoming,
            "chiusa la schermata le associazioni in arrivo tornano a essere negate"
        )
    }

    @Test
    fun `il codice di un'associazione in arrivo è marcato come tale`() = runTest(dispatcher) {
        viewModel.ascoltaAssociazioniInArrivo(true)
        val approvazione = assertNotNull(controller.incoming)

        val risposta = backgroundScope.async { approvazione("654321", "Telefono") }
        runCurrent()

        val richiesta = assertNotNull(viewModel.prompt.value)
        assertEquals("654321", richiesta.code)
        assertTrue(richiesta.incoming, "il testo cambia: è l'altro ad aver bussato")

        viewModel.rispondiAlConfronto(true)
        runCurrent()
        assertTrue(risposta.await())
    }

    @Test
    fun `sincronizza ora e dissocia arrivano al controller`() = runTest(dispatcher) {
        viewModel.sincronizzaOra()
        runCurrent()
        viewModel.dissocia("id-computer")
        runCurrent()

        assertEquals(1, controller.syncCount)
        assertEquals(listOf("id-computer"), controller.dissociati)
        assertEquals("Dispositivo dissociato.", viewModel.message.value)
    }

    @Test
    fun `un indirizzo manuale sbagliato produce un messaggio invece di un'eccezione`() = runTest(dispatcher) {
        viewModel.aggiungiManuale("192.168.1.10", 0)

        assertEquals("La porta deve essere fra 1 e 65535.", viewModel.message.value)
    }
}

package it.agoldoni.reminder.sync

import it.agoldoni.reminder.data.FakeEventDao
import it.agoldoni.reminder.data.FakePeerDao
import it.agoldoni.reminder.data.PeerEntity
import it.agoldoni.reminder.platform.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeSettings(iniziale: Boolean = false) : AppSettings {
    private val _syncEnabled = MutableStateFlow(iniziale)
    override val syncEnabled: StateFlow<Boolean> = _syncEnabled.asStateFlow()
    override fun setSyncEnabled(enabled: Boolean) {
        _syncEnabled.value = enabled
    }
}

class SyncServiceTest {

    private val scope = CoroutineScope(SupervisorJob())
    private val discovery = FakeDiscovery()
    private val peers = FakePeerDao()
    private val settings = FakeSettings()

    private fun servizio(listensInBackground: Boolean = true, settings: AppSettings = this.settings) =
        SyncService(
            identity = LocalIdentity("id-locale", "Questo dispositivo"),
            peers = peers,
            engine = SyncEngine(FakeEventDao(), RecordingAlarmScheduler()) { 1_000L },
            discovery = discovery,
            settings = settings,
            scope = scope,
            listensInBackground = listensInBackground,
            // Porta a zero: la sceglie il sistema, così il test non dipende da una porta libera.
            port = 0,
            now = { 2_000L }
        )

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    /**
     * Il criterio del piano: finché l'interruttore è spento l'avvio automatico non apre un socket
     * né manda un annuncio. È ciò che permette di spegnere la sincronizzazione senza reinstallare
     * nulla.
     */
    @Test
    fun `con la sincronizzazione spenta l'avvio automatico non apre niente`() {
        val servizio = servizio()

        servizio.start()

        assertNull(servizio.status.value.listeningPort, "nessun socket in ascolto")
        assertFalse(discovery.running, "nessun annuncio sulla rete")
        assertFalse(servizio.status.value.enabled)
    }

    /**
     * Senza questo non ci sarebbe modo di cominciare: per associare il primo dispositivo bisogna
     * trovarlo, e per trovarlo serve la ricerca accesa — che a interruttore spento non partirebbe.
     * Aprire la schermata è l'atto esplicito che la accende.
     */
    @Test
    fun `aprire la schermata accende la ricerca anche a interruttore spento`() {
        val servizio = servizio()

        servizio.beginInteractive()

        assertTrue(discovery.running, "si cerca")
        assertNotNull(servizio.status.value.listeningPort, "e si ascolta, per l'associazione")
    }

    @Test
    fun `chiudere la schermata rispegne tutto se non si è associato nulla`() {
        val servizio = servizio()
        servizio.beginInteractive()

        servizio.endInteractive()

        assertFalse(discovery.running)
        assertNull(servizio.status.value.listeningPort)
    }

    @Test
    fun `chiudere la schermata non spegne chi ha già un'associazione`() {
        val servizio = servizio(settings = FakeSettings(iniziale = true))
        servizio.beginInteractive()

        servizio.endInteractive()

        assertTrue(discovery.running, "chi è associato resta raggiungibile a schermata chiusa")
        assertNotNull(servizio.status.value.listeningPort)
    }

    @Test
    fun `accendere avvia ascolto e annuncio, spegnere li chiude`() {
        val servizio = servizio()

        servizio.enable()

        val porta = assertNotNull(servizio.status.value.listeningPort)
        assertTrue(porta > 0)
        assertTrue(discovery.running)
        assertEquals(
            porta,
            discovery.startedWith?.port,
            "si annuncia la porta su cui si ascolta davvero, non quella di default"
        )
        assertEquals("Questo dispositivo", discovery.startedWith?.displayName)

        servizio.disable()

        assertNull(servizio.status.value.listeningPort)
        assertFalse(discovery.running)
        assertFalse(servizio.status.value.enabled)
    }

    @Test
    fun `a schermata chiusa il telefono cerca senza annunciarsi`() {
        val servizio = servizio(listensInBackground = false)

        servizio.enable()

        assertNull(servizio.status.value.listeningPort, "ad app chiusa non tiene un socket aperto")
        assertTrue(discovery.running, "ma cerca comunque gli altri")
        assertNull(
            discovery.startedWith,
            "e non si annuncia: chi provasse a contattarlo troverebbe una porta chiusa"
        )
    }

    /**
     * Serve sulle reti dove è il **telefono** a non raggiungere il PC — sottoreti diverse con NAT
     * asimmetrico in mezzo. Se ascoltasse solo il desktop non ci sarebbe modo di associarsi;
     * potendo partire dal lato che passa, la comunicazione si stabilisce comunque.
     */
    @Test
    fun `mentre la schermata è aperta ascolta anche chi non ascolta in background`() {
        val servizio = servizio(listensInBackground = false)

        servizio.beginInteractive()

        val porta = assertNotNull(
            servizio.status.value.listeningPort,
            "in primo piano un socket in ascolto è lecito anche su Android"
        )
        assertEquals(porta, discovery.startedWith?.port, "e ci si annuncia con quella porta")
    }

    @Test
    fun `chiudendo la schermata il telefono smette di ascoltare ma resta associato`() {
        val servizio = servizio(
            listensInBackground = false,
            settings = FakeSettings(iniziale = true)
        )
        servizio.beginInteractive()
        assertNotNull(servizio.status.value.listeningPort)

        servizio.endInteractive()

        assertNull(servizio.status.value.listeningPort, "il socket si chiude con la schermata")
        assertTrue(discovery.running, "ma si continua a cercare, per poter chiamare l'altro")
        assertNull(discovery.startedWith, "senza annunciare una porta che non è più aperta")
    }

    @Test
    fun `senza dispositivi associati la sincronizzazione lo dice invece di fallire`() = runBlocking {
        val servizio = servizio()
        servizio.enable()

        servizio.syncNow()

        assertEquals("Nessun dispositivo associato.", servizio.status.value.lastMessage)
        assertFalse(servizio.status.value.syncing)
    }

    @Test
    fun `un peer associato ma irraggiungibile produce un messaggio, non un guasto`() = runBlocking {
        peers.upsert(
            PeerEntity(
                deviceId = "id-altro",
                displayName = "Computer",
                sharedSecret = randomBytes(32).toHex(),
                pairedAt = 1_000L
            )
        )
        val servizio = servizio()
        servizio.enable()

        servizio.syncNow()

        val messaggio = assertNotNull(servizio.status.value.lastMessage)
        assertTrue("Computer" in messaggio, "il messaggio deve dire con chi: $messaggio")
        assertTrue(
            "non è raggiungibile" in messaggio,
            "e che non si è annunciato né ha un indirizzo noto: $messaggio"
        )
        assertFalse(servizio.status.value.syncing, "lo stato non deve restare bloccato")
    }

    @Test
    fun `a sincronizzazione spenta un giro non parte`() = runBlocking {
        peers.upsert(
            PeerEntity("id-altro", "Computer", randomBytes(32).toHex(), pairedAt = 1_000L)
        )
        val servizio = servizio()

        servizio.syncNow()

        assertNull(servizio.status.value.lastMessage, "non deve nemmeno provarci")
    }

    @Test
    fun `dissociare toglie le credenziali`() = runBlocking {
        peers.upsert(
            PeerEntity("id-altro", "Computer", randomBytes(32).toHex(), pairedAt = 1_000L)
        )
        val servizio = servizio()

        servizio.unpair("id-altro")

        assertNull(peers.getById("id-altro"))
        assertTrue(peers.list().isEmpty())
    }

    @Test
    fun `un indirizzo digitato entra nell'elenco anche senza annunci`() {
        val servizio = servizio()

        val aggiunto = servizio.addManualPeer("192.168.1.42", 47653)

        assertTrue(aggiunto.isSuccess)
        assertEquals(
            "La porta deve essere fra 1 e 65535.",
            servizio.addManualPeer("192.168.1.42", 0).exceptionOrNull()?.message
        )
    }
}

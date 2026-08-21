package it.agoldoni.reminder.sync

import it.agoldoni.reminder.data.AppDatabase
import it.agoldoni.reminder.data.EventEntity
import it.agoldoni.reminder.platform.AppSettings
import it.agoldoni.reminder.platform.createAppDatabase
import it.agoldoni.reminder.platform.newUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class SettingsInMemoria(iniziale: Boolean = false) : AppSettings {
    private val _syncEnabled = MutableStateFlow(iniziale)
    override val syncEnabled: StateFlow<Boolean> = _syncEnabled.asStateFlow()
    override fun setSyncEnabled(enabled: Boolean) {
        _syncEnabled.value = enabled
    }
}

/**
 * TC-10 — due istanze complete che si trovano da sole, si associano e convergono.
 *
 * È l'unico test che mette insieme **mDNS e trasporto**: gli altri li esercitano separatamente, e
 * separatamente entrambi funzionavano anche quando l'annuncio usciva sull'interfaccia sbagliata o
 * la porta annunciata non era quella su cui si ascoltava. Qui un errore di quel tipo si vede,
 * perché nessuno passa a nessuno un indirizzo scritto a mano.
 *
 * **Usa la rete reale della macchina.** Su un host senza interfacce non di loopback si dichiara
 * saltato: non ci sarebbe nulla da scoprire.
 */
class DueIstanzeTest {

    private lateinit var directory: File
    private val scope = CoroutineScope(SupervisorJob())

    private lateinit var primoDb: AppDatabase
    private lateinit var secondoDb: AppDatabase
    private var primo: SyncService? = null
    private var secondo: SyncService? = null

    /** Nomi unici: due esecuzioni ravvicinate non devono trovarsi i servizi l'una dell'altra. */
    private val marcatore = newUuid().take(8)
    private val identitaPrimo = LocalIdentity("id-primo-$marcatore", "Primo $marcatore")
    private val identitaSecondo = LocalIdentity("id-secondo-$marcatore", "Secondo $marcatore")

    /** Concorrente di proposito: i codici li scrivono i thread di rete, li legge il test. */
    private val codici = java.util.concurrent.ConcurrentHashMap<String, String>()

    @BeforeTest
    fun setUp() {
        directory = File.createTempFile("promemoria-due-istanze", "").let {
            it.delete(); it.apply { mkdirs() }
        }
        primoDb = createAppDatabase(File(directory, "primo.db"), identitaPrimo.deviceId)
        secondoDb = createAppDatabase(File(directory, "secondo.db"), identitaSecondo.deviceId)
    }

    @AfterTest
    fun tearDown() {
        primo?.stop()
        secondo?.stop()
        scope.cancel()
        primoDb.close()
        secondoDb.close()
        directory.deleteRecursively()
    }

    private fun servizio(identity: LocalIdentity, db: AppDatabase, ora: Long) = SyncService(
        identity = identity,
        peers = db.peerDao(),
        engine = SyncEngine(db.eventDao(), RecordingAlarmScheduler()) { ora },
        discovery = JmdnsDiscovery(scope),
        settings = SettingsInMemoria(),
        scope = scope,
        listensInBackground = true,
        // Porta a zero su entrambi: due server sulla stessa macchina non possono condividerla, ed
        // è anche il caso in cui la porta annunciata deve essere quella effettiva.
        port = 0,
        now = { ora }
    )

    private fun evento(db: AppDatabase, titolo: String, updatedAt: Long) = EventEntity(
        title = titolo,
        dateTimeMillis = 1_900_000_000_000L,
        advanceMinutes = 15,
        uuid = newUuid(),
        updatedAt = updatedAt,
        origin = "test"
    )

    private fun reteDisponibile(): Boolean =
        runCatching { !siteAddress().isLoopbackAddress }.getOrDefault(false)

    @Test
    fun `due istanze si trovano, si associano e convergono senza indirizzi scritti a mano`() =
        runBlocking {
            if (!reteDisponibile()) {
                println("nessuna interfaccia di rete utilizzabile: test saltato")
                return@runBlocking
            }

            val a = servizio(identitaPrimo, primoDb, ORA_PRIMO).also { primo = it }
            val b = servizio(identitaSecondo, secondoDb, ORA_SECONDO).also { secondo = it }

            primoDb.eventDao().insert(evento(primoDb, "Dentista", 100))
            secondoDb.eventDao().insert(evento(secondoDb, "Bollette", 150))

            // Aprire la schermata è ciò che accende ricerca e ascolto su entrambi.
            a.beginInteractive()
            b.beginInteractive()

            val portaB = assertNotNull(b.status.value.listeningPort, "il secondo deve ascoltare")

            // --- scoperta: nessuno dei due sa dove sia l'altro, lo devono trovare da soli -------
            val trovato = withTimeoutOrNull(TIMEOUT_SCOPERTA) {
                var visto: DiscoveredPeer? = null
                while (visto == null) {
                    visto = a.discovered.value.firstOrNull {
                        it.deviceId == identitaSecondo.deviceId
                    }
                    if (visto == null) delay(250)
                }
                visto
            }
            assertNotNull(
                trovato,
                "il primo non ha trovato il secondo entro ${TIMEOUT_SCOPERTA / 1000} s"
            )
            assertEquals(identitaSecondo.displayName, trovato.displayName)
            assertEquals(
                portaB,
                trovato.port,
                "si annuncia la porta su cui si ascolta davvero, non quella richiesta"
            )
            assertEquals(PeerSource.MDNS, trovato.source, "trovato da solo, non digitato")

            // --- associazione: il codice deve coincidere sui due lati ---------------------------
            b.onIncomingPairing { code, _ -> codici["secondo"] = code; true }
            val esito = a.pair(trovato) { code, _ -> codici["primo"] = code; true }

            assertIs<PairingResult.Paired>(esito)
            assertEquals(
                codici["primo"],
                codici["secondo"],
                "è il confronto dei due codici a rendere sicura l'associazione"
            )
            val salvatoSuB = withTimeoutOrNull(TIMEOUT_BREVE) {
                var p = secondoDb.peerDao().getById(identitaPrimo.deviceId)
                while (p == null) { delay(100); p = secondoDb.peerDao().getById(identitaPrimo.deviceId) }
                p
            }
            assertNotNull(salvatoSuB, "anche chi riceve deve aver salvato l'associazione")

            // --- convergenza --------------------------------------------------------------------
            a.syncNow()

            val titoliAttesi = listOf("Bollette", "Dentista")
            assertEquals(titoliAttesi, primoDb.eventDao().getAll().map { it.title }.sorted())
            val convergiti = withTimeoutOrNull(TIMEOUT_BREVE) {
                var titoli = secondoDb.eventDao().getAll().map { it.title }.sorted()
                while (titoli != titoliAttesi) {
                    delay(100)
                    titoli = secondoDb.eventDao().getAll().map { it.title }.sorted()
                }
                titoli
            }
            assertEquals(titoliAttesi, convergiti, "i due database devono finire identici")

            // Un secondo giro non deve produrre nulla: è l'idempotenza, con la rete in mezzo.
            a.syncNow()
            assertEquals(2, primoDb.eventDao().getAll().size)
            assertEquals(2, secondoDb.eventDao().getAll().size)

            // L'indirizzo memorizzato dev'essere richiamabile, non la porta effimera del socket.
            val peerSuA = assertNotNull(primoDb.peerDao().getById(identitaSecondo.deviceId))
            assertEquals(portaB, peerSuA.lastPort)
            assertTrue(peerSuA.lastContactAt > 0, "l'ora locale del contatto va registrata")
        }

    private companion object {
        const val ORA_PRIMO = 1_700_000_000_000L
        const val ORA_SECONDO = 1_700_000_500_000L
        const val TIMEOUT_SCOPERTA = 30_000L
        const val TIMEOUT_BREVE = 10_000L
    }
}

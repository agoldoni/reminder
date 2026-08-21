package it.agoldoni.reminder.sync

import it.agoldoni.reminder.platform.newUuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round-trip mDNS vero: un'istanza annuncia, un'altra la trova. **Usa la rete reale della
 * macchina** — è il solo modo di sapere che l'annuncio esce e che il record TXT arriva intero,
 * cose che un doppio non può dimostrare.
 *
 * Serve un'interfaccia di rete attiva e non di loopback; su una macchina scollegata il test
 * si dichiara saltato invece di fallire, perché non ci sarebbe nulla da verificare.
 */
class JmdnsDiscoveryTest {

    private val advertiser = JmdnsDiscovery()
    private val browser = JmdnsDiscovery()

    @AfterTest
    fun tearDown() {
        advertiser.stop()
        browser.stop()
    }

    @Test
    fun `un dispositivo che si annuncia viene trovato dall'altro ma non da se stesso`() = runBlocking {
        if (!hasNetwork()) {
            println("nessuna interfaccia di rete utilizzabile: test saltato")
            return@runBlocking
        }

        val deviceId = newUuid()
        val nome = "Promemoria test ${deviceId.take(8)}"
        advertiser.start(Advertisement(deviceId = deviceId, displayName = nome, port = TEST_PORT))
        browser.start()

        val trovato = withTimeoutOrNull(TIMEOUT_MILLIS) {
            browser.peers.first { peers -> peers.any { it.deviceId == deviceId } }
                .first { it.deviceId == deviceId }
        }

        assertNotNull(trovato, "l'annuncio non è arrivato entro ${TIMEOUT_MILLIS / 1000} s")
        assertEquals(nome, trovato.displayName, "il nome leggibile deve arrivare intero")
        assertEquals(TEST_PORT, trovato.port)
        assertEquals(PeerSource.MDNS, trovato.source)
        assertTrue(trovato.host.isNotBlank(), "senza indirizzo il peer non è raggiungibile")

        // L'annuncio ha circolato davvero, come dimostra la riga trovata dal browser: se il filtro
        // non funzionasse, l'eco del proprio servizio sarebbe nell'elenco anche qui.
        assertTrue(
            advertiser.peers.value.none { it.displayName == nome },
            "il proprio annuncio non deve comparire fra i dispositivi trovati; il confronto è sul " +
                "nome e non sul deviceId perché sul proprio servizio jmdns non riporta il TXT, " +
                "e un controllo sull'identità passerebbe senza provare nulla"
        )
    }

    private fun hasNetwork(): Boolean =
        runCatching { !siteAddress().isLoopbackAddress }.getOrDefault(false)

    /**
     * L'indirizzo scelto dev'essere quello con cui si esce verso la rete, non il primo che capita:
     * su una macchina con Docker o con dei bridge la scansione delle interfacce restituirebbe
     * `docker0`, e l'annuncio finirebbe su una rete dove non c'è nessuno.
     */
    @Test
    fun `l'indirizzo scelto è quello della rotta verso la rete locale`() {
        if (!hasNetwork()) {
            println("nessuna interfaccia di rete utilizzabile: test saltato")
            return
        }
        val scelto = siteAddress()
        val atteso = java.net.DatagramSocket().use {
            it.connect(java.net.InetSocketAddress("192.0.2.1", 9))
            it.localAddress
        }
        assertEquals(atteso.hostAddress, scelto.hostAddress)
    }

    private companion object {
        const val TEST_PORT = 54321
        const val TIMEOUT_MILLIS = 20_000L
    }
}

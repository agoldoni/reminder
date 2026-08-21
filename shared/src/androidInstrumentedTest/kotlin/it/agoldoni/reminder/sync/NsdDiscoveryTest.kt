package it.agoldoni.reminder.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Su device non si può pretendere che mDNS passi — l'emulatore è dietro NAT e le reti ospiti lo
 * filtrano — quindi il test non verifica che qualcuno venga trovato. Verifica il cablaggio, che è
 * ciò che si rompe in silenzio: il multicast lock (che senza `CHANGE_WIFI_MULTICAST_STATE`
 * solleverebbe `SecurityException`), i due `getSystemService` e l'avvio di annuncio e ricerca.
 */
@RunWith(AndroidJUnit4::class)
class NsdDiscoveryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val discovery = NsdDiscovery(context)

    @After
    fun tearDown() {
        discovery.stop()
    }

    @Test
    fun avvioEArrestoNonSollevanoEccezioni() = runBlocking {
        assertEquals(DiscoveryStatus.Stopped, discovery.status.value)

        discovery.start(
            Advertisement(
                deviceId = "test-device-id",
                displayName = "Promemoria test",
                port = 54321
            )
        )

        // Searching se la ricerca è partita, Unavailable se la rete la rifiuta: entrambi sono
        // esiti legittimi, quello che si esclude è restare a Stopped o esplodere.
        val stato = withTimeoutOrNull(TIMEOUT_MILLIS) {
            discovery.status.first { it != DiscoveryStatus.Stopped }
        }
        assertNotNull(stato, "lo stato deve cambiare entro ${TIMEOUT_MILLIS / 1000} s")

        discovery.stop()
        assertEquals(DiscoveryStatus.Stopped, discovery.status.value)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L
    }
}

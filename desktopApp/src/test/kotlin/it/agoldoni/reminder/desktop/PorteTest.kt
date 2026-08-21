package it.agoldoni.reminder.desktop

import it.agoldoni.reminder.sync.SYNC_PORT
import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * Le due porte fisse dell'app nascono in moduli diversi e nessuno le vede insieme. Quando hanno
 * coinciso, l'istanza singola teneva la porta e il server di sincronizzazione non riusciva a
 * legarsi: l'app funzionava, ma la sincronizzazione era muta e lo si scopriva solo provandola su
 * due dispositivi veri.
 */
class PorteTest {

    @Test
    fun `l'istanza singola e la sincronizzazione non usano la stessa porta`() {
        assertNotEquals(
            SingleInstance.DEFAULT_PORT,
            SYNC_PORT,
            "l'istanza singola tiene la sua porta per tutta la vita del processo"
        )
    }
}

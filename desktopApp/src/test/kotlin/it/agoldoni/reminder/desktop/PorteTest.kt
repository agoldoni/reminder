package it.agoldoni.reminder.desktop

import it.agoldoni.reminder.sync.SYNC_PORT
import it.agoldoni.reminder.web.WEB_PORT
import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * Le porte fisse dell'app nascono in moduli diversi e nessuno le vede insieme — tranne questo
 * modulo, che le vede tutte. Quando due hanno coinciso, l'istanza singola teneva la porta e il
 * server di sincronizzazione non riusciva a legarsi: l'app funzionava, ma la sincronizzazione era
 * muta e lo si scopriva solo provandola su due dispositivi veri.
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

    @Test
    fun `l'istanza singola e la web app non usano la stessa porta`() {
        assertNotEquals(
            SingleInstance.DEFAULT_PORT,
            WEB_PORT,
            "l'istanza singola tiene la sua porta per tutta la vita del processo"
        )
    }
}

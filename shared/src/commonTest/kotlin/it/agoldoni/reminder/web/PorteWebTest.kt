package it.agoldoni.reminder.web

import it.agoldoni.reminder.sync.SYNC_PORT
import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * Le porte fisse dell'app nascono in moduli diversi e nessuno le vede insieme. Quando la
 * sincronizzazione e l'istanza singola desktop hanno usato lo stesso numero, il server di
 * sincronizzazione non riusciva a legarsi: l'app funzionava, ma la sincronizzazione era muta e lo
 * si scopriva solo provandola su due dispositivi veri. Con la terza porta il rischio cresce, e
 * questo test è metà del presidio — l'altra metà è `PorteTest` in `:desktopApp`, l'unico modulo
 * che vede anche `SingleInstance.DEFAULT_PORT`.
 */
class PorteWebTest {

    @Test
    fun `la web app e la sincronizzazione non usano la stessa porta`() {
        assertNotEquals(
            WEB_PORT,
            SYNC_PORT,
            "sul telefono i due server possono essere in ascolto nello stesso momento"
        )
    }
}

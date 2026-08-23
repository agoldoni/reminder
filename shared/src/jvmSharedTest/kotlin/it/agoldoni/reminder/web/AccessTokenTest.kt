package it.agoldoni.reminder.web

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TC-07/TC-08/TC-09/TC-10 — i due token, i permessi, e la difesa contro chi sonda.
 *
 * **Che cosa è cambiato rispetto alla feature 004**, perché questo file è quasi tutto riscritto e
 * la ragione va detta invece che dedotta dal diff:
 *
 * - non esistono più `rigenera()` e `invalida()`: il segreto non è più una coppia in memoria ma la
 *   chiave di firma su disco, e i token si coniano da quella;
 * - **«spegnere invalida entrambi i token» è diventato il suo contrario.** Non è un test aggiustato
 *   per farlo passare: è la feature. L'interruttore chiude la porta, la revoca cambia le serrature,
 *   e sono due gesti diversi;
 * - spariscono i casi sull'alfabeto senza `i l o 1` e sulla digitabilità: un token non si digita
 *   più, si consegna.
 *
 * Sopravvivono intatti, e sono quelli che contano ancora: la soglia per indirizzo e non globale, la
 * finestra che dimentica, l'accesso riuscito che azzera, e il token valido ma insufficiente che
 * **non** consuma tentativi.
 */
class AccessTokenTest {

    private val cartella = File.createTempFile("promemoria-accesso", "").let {
        it.delete()
        File(it.absolutePath)
    }

    @AfterTest
    fun pulisci() {
        cartella.listFiles()?.forEach { it.delete() }
        cartella.delete()
    }

    private var orologio = 1_000L
    private fun token() = AccessToken(ChiaveFirma(cartella), now = { orologio })

    // --- I due token ----------------------------------------------------------------------------

    @Test
    fun `il token giusto entra, quello sbagliato e quello assente no`() {
        val t = token()
        val buoni = t.coniaCoppia()
        assertEquals(Accesso.LETTURA, t.verifica(buoni.lettura, "10.0.0.1"))
        assertEquals(Accesso.SCRITTURA, t.verifica(buoni.scrittura, "10.0.0.1"))
        assertEquals(Accesso.NEGATO, t.verifica("sbagliato", "10.0.0.2"))
        assertEquals(Accesso.NEGATO, t.verifica(null, "10.0.0.3"))
        assertEquals(Accesso.NEGATO, t.verifica("", "10.0.0.4"))
    }

    @Test
    fun `i due token di una stessa coniatura sono diversi`() {
        val buoni = token().coniaCoppia()
        assertNotEquals(buoni.lettura, buoni.scrittura, "differiscono nel campo `p`, per costruzione")
    }

    @Test
    fun `i due token dichiarano la stessa scadenza`() {
        // Uno solo in `WebStatus`: si coniano nello stesso istante, e due date suggerirebbero una
        // differenza che non c'è.
        val buoni = token().coniaCoppia()
        assertEquals(orologio + DURATA_ACCESSO_MILLIS, buoni.scadenzaMillis)
    }

    @Test
    fun `un token scaduto non entra piu'`() {
        val t = token()
        val buoni = t.coniaCoppia()
        orologio = buoni.scadenzaMillis
        assertEquals(Accesso.NEGATO, t.verifica(buoni.lettura, "10.0.0.1"))
    }

    // --- I due livelli --------------------------------------------------------------------------

    @Test
    fun `il token di scrittura vale anche per leggere`() {
        // Senza questo, la pagina servita sull'indirizzo completo non potrebbe caricare la propria
        // lista e servirebbero due schede aperte.
        val t = token()
        val buoni = t.coniaCoppia()
        assertTrue(t.verifica(buoni.scrittura, "10.0.0.1").puoLeggere)
        assertTrue(t.verifica(buoni.scrittura, "10.0.0.1").puoScrivere)
    }

    @Test
    fun `il token di lettura non vale per scrivere`() {
        val t = token()
        val buoni = t.coniaCoppia()
        val esito = t.verifica(buoni.lettura, "10.0.0.1")
        assertTrue(esito.puoLeggere)
        assertTrue(!esito.puoScrivere, "è l'intero senso dei due indirizzi")
    }

    @Test
    fun `chi non entra non puo' ne' leggere ne' scrivere`() {
        for (esito in listOf(Accesso.NEGATO, Accesso.BLOCCATO)) {
            assertTrue(!esito.puoLeggere, "$esito non deve consentire la lettura")
            assertTrue(!esito.puoScrivere, "$esito non deve consentire la scrittura")
        }
    }

    @Test
    fun `solo chi entra e' autenticato, ed e' cio' che separa il 401 dal 403`() {
        val t = token()
        val buoni = t.coniaCoppia()
        assertTrue(t.verifica(buoni.lettura, "10.0.0.1").autenticato)
        assertTrue(t.verifica(buoni.scrittura, "10.0.0.1").autenticato)
        assertTrue(!Accesso.NEGATO.autenticato)
        assertTrue(!Accesso.BLOCCATO.autenticato, "«troppi tentativi» non dice chi sei")
    }

    // --- Coniare non revoca ---------------------------------------------------------------------

    @Test
    fun `coniare una coppia nuova non invalida quella di prima`() {
        // **È la feature.** Prima di questa riga, `rigenera()` faceva esattamente il contrario.
        val t = token()
        val vecchi = t.coniaCoppia()
        t.coniaCoppia()
        assertEquals(Accesso.LETTURA, t.verifica(vecchi.lettura, "10.0.0.1"))
        assertEquals(Accesso.SCRITTURA, t.verifica(vecchi.scrittura, "10.0.0.1"))
    }

    @Test
    fun `un'istanza nuova sulla stessa cartella accetta i token di quella di prima`() {
        // Il caso vero: il processo muore, l'app riparte, e il segnalibro deve funzionare ancora.
        val vecchi = token().coniaCoppia()
        assertEquals(Accesso.SCRITTURA, token().verifica(vecchi.scrittura, "10.0.0.1"))
    }

    // --- Revocare sì ----------------------------------------------------------------------------

    @Test
    fun `revocare invalida entrambi i token`() {
        val t = token()
        val vecchi = t.coniaCoppia()

        t.revoca()

        assertEquals(Accesso.NEGATO, t.verifica(vecchi.lettura, "10.0.0.1"))
        assertEquals(Accesso.NEGATO, t.verifica(vecchi.scrittura, "10.0.0.2"))
    }

    @Test
    fun `dopo la revoca i token nuovi funzionano`() {
        val t = token()
        t.coniaCoppia()
        t.revoca()
        val nuovi = t.coniaCoppia()
        assertEquals(Accesso.LETTURA, t.verifica(nuovi.lettura, "10.0.0.1"))
    }

    @Test
    fun `la revoca sopravvive al riavvio`() {
        val vecchi = token().coniaCoppia()
        token().revoca()
        assertEquals(Accesso.NEGATO, token().verifica(vecchi.lettura, "10.0.0.1"))
    }

    @Test
    fun `revocare azzera i tentativi`() {
        val t = token()
        repeat(10) { t.verifica("sbagliato", "10.0.0.9") }
        assertEquals(Accesso.BLOCCATO, t.verifica("sbagliato", "10.0.0.9"))

        t.revoca()

        assertEquals(Accesso.NEGATO, t.verifica("sbagliato", "10.0.0.9"), "il conto riparte da capo")
    }

    // --- La soglia ------------------------------------------------------------------------------

    @Test
    fun `superata la soglia i tentativi sono respinti senza verificare la firma`() {
        val t = token()
        val buoni = t.coniaCoppia()
        repeat(10) { assertEquals(Accesso.NEGATO, t.verifica("sbagliato", "10.0.0.9")) }

        assertEquals(Accesso.BLOCCATO, t.verifica("sbagliato", "10.0.0.9"))
        assertEquals(
            Accesso.BLOCCATO,
            t.verifica(buoni.lettura, "10.0.0.9"),
            "bloccato vuol dire bloccato: nemmeno un token buono passa da quell'indirizzo"
        )
    }

    @Test
    fun `la soglia e' per indirizzo, non globale`() {
        // Altrimenti chi sonda potrebbe chiudere fuori l'utente riempiendo il conto.
        val t = token()
        val buoni = t.coniaCoppia()
        repeat(20) { t.verifica("sbagliato", "10.0.0.9") }

        assertEquals(Accesso.LETTURA, t.verifica(buoni.lettura, "192.168.1.5"))
    }

    @Test
    fun `i tentativi contro l'uno e contro l'altro sommano sullo stesso conto`() {
        // Il contatore resta uno solo anche con due token: sdoppiarlo regalerebbe venti tentativi
        // al minuto invece di dieci.
        val t = token()
        t.coniaCoppia()
        repeat(5) { t.verifica("provo-il-primo", "10.0.0.9") }
        repeat(5) { t.verifica("provo-il-secondo", "10.0.0.9") }

        assertEquals(Accesso.BLOCCATO, t.verifica("ancora", "10.0.0.9"))
    }

    @Test
    fun `passata la finestra i tentativi si dimenticano`() {
        val t = token()
        repeat(11) { t.verifica("sbagliato", "10.0.0.9") }
        assertEquals(Accesso.BLOCCATO, t.verifica("sbagliato", "10.0.0.9"))

        orologio += 60_001

        assertEquals(Accesso.NEGATO, t.verifica("sbagliato", "10.0.0.9"))
    }

    @Test
    fun `un accesso riuscito azzera i tentativi di quell'indirizzo`() {
        val t = token()
        val buoni = t.coniaCoppia()
        repeat(9) { t.verifica("sbagliato", "10.0.0.9") }

        assertEquals(Accesso.LETTURA, t.verifica(buoni.lettura, "10.0.0.9"))

        repeat(9) { t.verifica("sbagliato", "10.0.0.9") }
        assertEquals(Accesso.NEGATO, t.verifica("sbagliato", "10.0.0.9"), "non ancora bloccato")
    }

    @Test
    fun `un token valido ma insufficiente non consuma i tentativi`() {
        // Chi apre l'indirizzo di sola lettura e prova a scrivere è l'utente, non un attaccante:
        // fargli consumare la soglia lo chiuderebbe fuori da ciò che ha il diritto di vedere.
        val t = token()
        val buoni = t.coniaCoppia()
        repeat(20) {
            val esito = t.verifica(buoni.lettura, "10.0.0.9")
            assertEquals(Accesso.LETTURA, esito)
            assertTrue(!esito.puoScrivere)
        }
        assertEquals(Accesso.SCRITTURA, t.verifica(buoni.scrittura, "10.0.0.9"))
    }

    // --- Il guasto della chiave -----------------------------------------------------------------

    @Test
    fun `se la chiave non si puo' preparare nessuno entra, e nessuno consuma tentativi`() {
        // Un guasto nostro non è un tentativo fallito di chi sta chiedendo. In pratica non capita
        // — `apri()` prepara la chiave prima di aprire il socket — ma un'eccezione qui uscirebbe
        // dal gestore come `500`, e direbbe a chi sonda che ha trovato qualcosa.
        val impossibile = File(cartella, "file-non-cartella")
        cartella.mkdirs()
        impossibile.writeText("sono un file")
        val t = AccessToken(ChiaveFirma(File(impossibile, "sotto")), now = { orologio })

        repeat(20) { assertEquals(Accesso.NEGATO, t.verifica("qualunque", "10.0.0.9")) }
        assertEquals(Accesso.NEGATO, t.verifica("qualunque", "10.0.0.9"), "mai BLOCCATO: non è colpa sua")
    }
}

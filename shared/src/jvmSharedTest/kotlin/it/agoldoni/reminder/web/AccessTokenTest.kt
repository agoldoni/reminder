package it.agoldoni.reminder.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TC-02/TC-03/TC-05 — i due token e la loro difesa contro i tentativi ripetuti. */
class AccessTokenTest {

    private var orologio = 1_000L
    private fun token() = AccessToken(now = { orologio })

    @Test
    fun `prima di accendere non esiste nessun token e nessuno entra`() {
        val t = token()
        assertNull(t.coppia)
        assertEquals(Accesso.NEGATO, t.verifica("qualunque", "10.0.0.1"))
        assertEquals(Accesso.NEGATO, t.verifica(null, "10.0.0.1"))
    }

    @Test
    fun `il token giusto entra, quello sbagliato e quello assente no`() {
        val t = token()
        val buoni = t.rigenera()
        assertEquals(Accesso.LETTURA, t.verifica(buoni.lettura, "10.0.0.1"))
        assertEquals(Accesso.SCRITTURA, t.verifica(buoni.scrittura, "10.0.0.1"))
        assertEquals(Accesso.NEGATO, t.verifica("sbagliato", "10.0.0.2"))
        assertEquals(Accesso.NEGATO, t.verifica(null, "10.0.0.3"))
        assertEquals(Accesso.NEGATO, t.verifica("", "10.0.0.4"))
    }

    // --- I due livelli --------------------------------------------------------------------------

    @Test
    fun `il token di scrittura vale anche per leggere`() {
        // Senza questo, la pagina servita sull'indirizzo completo non potrebbe caricare la propria
        // lista e servirebbero due schede aperte.
        val t = token()
        val buoni = t.rigenera()
        assertTrue(t.verifica(buoni.scrittura, "10.0.0.1").puoLeggere)
        assertTrue(t.verifica(buoni.scrittura, "10.0.0.1").puoScrivere)
    }

    @Test
    fun `il token di lettura non vale per scrivere`() {
        val t = token()
        val buoni = t.rigenera()
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
    fun `i due token di una stessa accensione sono diversi`() {
        // Con quaranta bit la collisione è teorica; se capitasse, i due indirizzi sarebbero lo
        // stesso indirizzo e quello di sola lettura comanderebbe.
        val t = token()
        repeat(200) {
            val coppia = t.rigenera()
            assertNotEquals(coppia.lettura, coppia.scrittura)
        }
    }

    @Test
    fun `un token valido ma insufficiente non consuma i tentativi`() {
        // Chi apre l'indirizzo di lettura e prova a scrivere è l'utente, non un attaccante:
        // addebitarglielo lo chiuderebbe fuori dopo dieci tentativi.
        val t = token()
        val buoni = t.rigenera()
        repeat(30) { assertEquals(Accesso.LETTURA, t.verifica(buoni.lettura, "10.0.0.9")) }
        assertEquals(
            Accesso.SCRITTURA,
            t.verifica(buoni.scrittura, "10.0.0.9"),
            "trenta letture legittime non devono aver riempito il conto"
        )
    }

    @Test
    fun `i tentativi contro l'uno e contro l'altro sommano sullo stesso conto`() {
        // Un contatore per token darebbe a chi sonda venti tentativi al minuto invece di dieci.
        val t = token()
        val buoni = t.rigenera()
        repeat(10) { t.verifica("sbagliato$it", "10.0.0.9") }
        assertEquals(Accesso.BLOCCATO, t.verifica(buoni.lettura, "10.0.0.9"))
        assertEquals(Accesso.BLOCCATO, t.verifica(buoni.scrittura, "10.0.0.9"))
    }

    // --- Ciclo di vita --------------------------------------------------------------------------

    @Test
    fun `entrambi i token cambiano a ogni accensione`() {
        val t = token()
        val primi = t.rigenera()
        val secondi = t.rigenera()
        assertNotEquals(primi.lettura, secondi.lettura, "un indirizzo copiato ieri non vale oggi")
        assertNotEquals(primi.scrittura, secondi.scrittura)
        assertEquals(Accesso.NEGATO, t.verifica(primi.lettura, "10.0.0.1"))
        assertEquals(Accesso.NEGATO, t.verifica(primi.scrittura, "10.0.0.1"))
        assertEquals(Accesso.LETTURA, t.verifica(secondi.lettura, "10.0.0.1"))
        assertEquals(Accesso.SCRITTURA, t.verifica(secondi.scrittura, "10.0.0.1"))
    }

    @Test
    fun `il token vecchio non diventa mai l'altro`() {
        // Un modo sottile di sbagliare la rigenerazione: riusare il token di scrittura di ieri
        // come token di lettura di oggi. Chi aveva l'indirizzo vecchio comanderebbe ancora.
        val t = token()
        val primi = t.rigenera()
        val secondi = t.rigenera()
        assertNotEquals(primi.scrittura, secondi.lettura)
        assertNotEquals(primi.lettura, secondi.scrittura)
    }

    @Test
    fun `spegnere invalida entrambi i token`() {
        val t = token()
        val buoni = t.rigenera()
        t.invalida()
        assertNull(t.coppia)
        assertEquals(Accesso.NEGATO, t.verifica(buoni.lettura, "10.0.0.1"))
        assertEquals(Accesso.NEGATO, t.verifica(buoni.scrittura, "10.0.0.1"))
    }

    // --- La soglia dei tentativi ----------------------------------------------------------------

    @Test
    fun `superata la soglia i tentativi sono respinti senza confrontare il token`() {
        val t = token()
        val buoni = t.rigenera()
        repeat(10) { assertEquals(Accesso.NEGATO, t.verifica("sbagliato", "10.0.0.9")) }
        // La prova che il confronto non avviene più: nemmeno il token **giusto** passa.
        assertEquals(Accesso.BLOCCATO, t.verifica(buoni.lettura, "10.0.0.9"))
    }

    @Test
    fun `la soglia e' per indirizzo, non globale`() {
        // Altrimenti chi tenta a raffica chiuderebbe fuori l'utente.
        val t = token()
        val buoni = t.rigenera()
        repeat(15) { t.verifica("sbagliato", "10.0.0.9") }
        assertEquals(Accesso.LETTURA, t.verifica(buoni.lettura, "10.0.0.1"))
    }

    @Test
    fun `passata la finestra i tentativi si dimenticano`() {
        // Dieci errori di battitura non devono trasformarsi in un servizio da riavviare.
        val t = token()
        val buoni = t.rigenera()
        repeat(10) { t.verifica("sbagliato", "10.0.0.9") }
        assertEquals(Accesso.BLOCCATO, t.verifica(buoni.lettura, "10.0.0.9"))
        orologio += 61_000
        assertEquals(Accesso.LETTURA, t.verifica(buoni.lettura, "10.0.0.9"))
    }

    @Test
    fun `un accesso riuscito azzera i tentativi di quell'indirizzo`() {
        val t = token()
        val buoni = t.rigenera()
        repeat(9) { t.verifica("sbagliato", "10.0.0.9") }
        assertEquals(Accesso.LETTURA, t.verifica(buoni.lettura, "10.0.0.9"))
        repeat(9) { assertEquals(Accesso.NEGATO, t.verifica("sbagliato", "10.0.0.9")) }
        assertEquals(Accesso.SCRITTURA, t.verifica(buoni.scrittura, "10.0.0.9"))
    }

    @Test
    fun `rigenerare azzera i tentativi`() {
        val t = token()
        t.rigenera()
        repeat(10) { t.verifica("sbagliato", "10.0.0.9") }
        val nuovi = t.rigenera()
        assertEquals(Accesso.LETTURA, t.verifica(nuovi.lettura, "10.0.0.9"))
    }

    // --- Forma dei token ------------------------------------------------------------------------

    @Test
    fun `i token sono digitabili senza ambiguita' e non si ripetono`() {
        val t = token()
        val visti = mutableSetOf<String>()
        repeat(250) {
            val coppia = t.rigenera()
            for (v in listOf(coppia.lettura, coppia.scrittura)) {
                assertEquals(8, v.length, "otto caratteri: si devono poter ridigitare")
                assertTrue(
                    v.all { c -> c in "abcdefghjkmnpqrstuvwxyz023456789" },
                    "carattere fuori alfabeto in $v"
                )
                assertTrue(v.none { c -> c in "ilo1" }, "carattere confondibile in $v")
                visti += v
            }
        }
        // Con 40 bit, 500 estrazioni quasi tutte diverse: un generatore costante si vedrebbe qui.
        assertTrue(visti.size > 495, "solo ${visti.size} token distinti su 500")
    }
}

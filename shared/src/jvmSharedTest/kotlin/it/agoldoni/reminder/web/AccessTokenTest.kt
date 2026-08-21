package it.agoldoni.reminder.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TC-02/TC-03 — il token e la sua difesa contro i tentativi ripetuti. */
class AccessTokenTest {

    private var orologio = 1_000L
    private fun token() = AccessToken(now = { orologio })

    @Test
    fun `prima di accendere non esiste nessun token e nessuno entra`() {
        val t = token()
        assertNull(t.valore)
        assertEquals(Accesso.NEGATO, t.verifica("qualunque", "10.0.0.1"))
        assertEquals(Accesso.NEGATO, t.verifica(null, "10.0.0.1"))
    }

    @Test
    fun `il token giusto entra, quello sbagliato e quello assente no`() {
        val t = token()
        val buono = t.rigenera()
        assertEquals(Accesso.CONSENTITO, t.verifica(buono, "10.0.0.1"))
        assertEquals(Accesso.NEGATO, t.verifica("sbagliato", "10.0.0.2"))
        assertEquals(Accesso.NEGATO, t.verifica(null, "10.0.0.3"))
        assertEquals(Accesso.NEGATO, t.verifica("", "10.0.0.4"))
    }

    @Test
    fun `il token cambia a ogni accensione`() {
        val t = token()
        val primo = t.rigenera()
        val secondo = t.rigenera()
        assertNotEquals(primo, secondo, "un indirizzo copiato ieri non deve funzionare oggi")
        assertEquals(Accesso.NEGATO, t.verifica(primo, "10.0.0.1"))
        assertEquals(Accesso.CONSENTITO, t.verifica(secondo, "10.0.0.1"))
    }

    @Test
    fun `spegnere invalida il token`() {
        val t = token()
        val buono = t.rigenera()
        t.invalida()
        assertNull(t.valore)
        assertEquals(Accesso.NEGATO, t.verifica(buono, "10.0.0.1"))
    }

    @Test
    fun `superata la soglia i tentativi sono respinti senza confrontare il token`() {
        val t = token()
        val buono = t.rigenera()
        repeat(10) { assertEquals(Accesso.NEGATO, t.verifica("sbagliato", "10.0.0.9")) }
        // La prova che il confronto non avviene più: nemmeno il token **giusto** passa.
        assertEquals(Accesso.BLOCCATO, t.verifica(buono, "10.0.0.9"))
    }

    @Test
    fun `la soglia e' per indirizzo, non globale`() {
        // Altrimenti chi tenta a raffica chiuderebbe fuori l'utente.
        val t = token()
        val buono = t.rigenera()
        repeat(15) { t.verifica("sbagliato", "10.0.0.9") }
        assertEquals(Accesso.CONSENTITO, t.verifica(buono, "10.0.0.1"))
    }

    @Test
    fun `passata la finestra i tentativi si dimenticano`() {
        // Dieci errori di battitura non devono trasformarsi in un servizio da riavviare.
        val t = token()
        val buono = t.rigenera()
        repeat(10) { t.verifica("sbagliato", "10.0.0.9") }
        assertEquals(Accesso.BLOCCATO, t.verifica(buono, "10.0.0.9"))
        orologio += 61_000
        assertEquals(Accesso.CONSENTITO, t.verifica(buono, "10.0.0.9"))
    }

    @Test
    fun `un accesso riuscito azzera i tentativi di quell'indirizzo`() {
        val t = token()
        val buono = t.rigenera()
        repeat(9) { t.verifica("sbagliato", "10.0.0.9") }
        assertEquals(Accesso.CONSENTITO, t.verifica(buono, "10.0.0.9"))
        repeat(9) { assertEquals(Accesso.NEGATO, t.verifica("sbagliato", "10.0.0.9")) }
        assertEquals(Accesso.CONSENTITO, t.verifica(buono, "10.0.0.9"))
    }

    @Test
    fun `rigenerare azzera i tentativi`() {
        val t = token()
        t.rigenera()
        repeat(10) { t.verifica("sbagliato", "10.0.0.9") }
        val nuovo = t.rigenera()
        assertEquals(Accesso.CONSENTITO, t.verifica(nuovo, "10.0.0.9"))
    }

    @Test
    fun `il token e' digitabile senza ambiguita' e non si ripete`() {
        val t = token()
        val visti = mutableSetOf<String>()
        repeat(500) {
            val v = t.rigenera()
            assertEquals(8, v.length, "otto caratteri: si devono poter ridigitare")
            assertTrue(v.all { c -> c in "abcdefghjkmnpqrstuvwxyz023456789" }, "carattere fuori alfabeto in $v")
            assertTrue(v.none { c -> c in "ilo1" }, "carattere confondibile in $v")
            visti += v
        }
        // Con 40 bit, 500 estrazioni tutte diverse: se il generatore fosse costante si vedrebbe qui.
        assertTrue(visti.size > 495, "solo ${visti.size} token distinti su 500")
    }
}

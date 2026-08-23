package it.agoldoni.reminder.web

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * TC-01/02/03 — il contatore dei cambiamenti e l'attesa.
 *
 * Tutto a **tempo virtuale**: un test che aspettasse davvero venticinque secondi non verrebbe
 * eseguito, e `testScheduler.currentTime` è anche il modo di provare che una risposta è arrivata
 * *subito* invece che dopo aver aspettato — che è una parte dell'asserzione, non un dettaglio.
 */
class CambiamentiTest {

    private val segnali = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    @Test
    fun `il contatore parte da zero e sale a ogni segnale`() = runTest {
        val cambiamenti = Cambiamenti(segnali, backgroundScope)
        runCurrent()
        assertEquals(0L, cambiamenti.versione, "all'avvio non è cambiato niente, è solo iniziato")

        segnali.emit(Unit)
        runCurrent()
        assertEquals(1L, cambiamenti.versione)

        segnali.emit(Unit)
        segnali.emit(Unit)
        runCurrent()
        assertEquals(3L, cambiamenti.versione)
    }

    /**
     * La corsa che l'ordine «contatore prima, corpo poi» esiste per chiudere: chi ha letto il
     * contatore, si è preso il suo tempo a comporre la risposta e nel frattempo si è perso un
     * segnale, non deve restare appeso venticinque secondi con in mano un dato già vecchio.
     */
    @Test
    fun `attendi non sospende se il contatore si e' gia' mosso`() = runTest {
        val cambiamenti = Cambiamenti(segnali, backgroundScope)
        runCurrent()
        val visto = cambiamenti.versione

        segnali.emit(Unit) // il segnale arriva mentre il chiamante era altrove
        runCurrent()

        val prima = testScheduler.currentTime
        val esito = cambiamenti.attendi(visto) { "nuovo" }
        assertEquals("nuovo", esito)
        assertEquals(prima, testScheduler.currentTime, "non doveva sospendere nemmeno un istante")
    }

    @Test
    fun `attendi si sveglia su un segnale`() = runTest {
        val cambiamenti = Cambiamenti(segnali, backgroundScope)
        runCurrent()

        var esito: String? = null
        val attesa = backgroundScope.launch { esito = cambiamenti.attendi(0L) { "nuovo" } }
        runCurrent()
        assertNull(esito, "senza segnale deve essere ancora appesa")

        segnali.emit(Unit)
        attesa.join()
        assertEquals("nuovo", esito)
    }

    @Test
    fun `attendi scade e non restituisce niente`() = runTest {
        val cambiamenti = Cambiamenti(segnali, backgroundScope, attesaMillis = 25_000L)
        runCurrent()

        val prima = testScheduler.currentTime
        assertNull(cambiamenti.attendi(0L) { "nuovo" })
        assertEquals(25_000L, testScheduler.currentTime - prima, "ha aspettato quanto doveva")
    }

    /**
     * Sulla tabella `events` si scrive anche per cose che non toccano i promemoria aperti: un
     * evento già completato, un tombstone che arriva dalla sincronizzazione. Svegliare il browser
     * su quei segnali vorrebbe dire mandarlo a ridisegnare per niente, in un ciclo stretto.
     */
    @Test
    fun `un segnale che non cambia niente non interrompe l'attesa`() = runTest {
        val cambiamenti = Cambiamenti(segnali, backgroundScope, attesaMillis = 25_000L)
        runCurrent()

        var chiamate = 0
        var esito: String? = "ancora qui"
        val attesa = backgroundScope.launch {
            esito = cambiamenti.attendi(0L) { chiamate++; null }
        }
        runCurrent()

        segnali.emit(Unit)
        runCurrent()
        assertEquals(1, chiamate, "ha guardato")
        assertEquals("ancora qui", esito, "e si è rimessa ad aspettare")

        // Due segnali in fila **si conflano**, e va bene così: quando l'attesa riprende il
        // contatore è già oltre entrambi, e ricomporre la risposta due volte per poi rispondere
        // una sola darebbe lo stesso risultato pagando due query.
        segnali.emit(Unit)
        segnali.emit(Unit)
        runCurrent()
        assertEquals(2, chiamate, "due segnali in fila valgono una guardata sola")
        assertEquals("ancora qui", esito)

        attesa.join() // fino alla scadenza
        assertNull(esito)
    }

    @Test
    fun `un solo segnale sveglia tutte le attese`() = runTest {
        val cambiamenti = Cambiamenti(segnali, backgroundScope)
        runCurrent()

        val svegliate = mutableListOf<Int>()
        repeat(3) { i -> backgroundScope.launch { cambiamenti.attendi(0L) { i }?.let { svegliate += it } } }
        runCurrent()
        assertEquals(emptyList(), svegliate)

        segnali.emit(Unit)
        runCurrent()
        assertEquals(listOf(0, 1, 2), svegliate.sorted())
    }

    /**
     * Oltre il tetto non si aspetta, e soprattutto **non si sbaglia**: si risponde subito, che per
     * il chiamante è indistinguibile da «non hai chiesto di aspettare». Il client ripiega da sé sul
     * polling e l'unica cosa che nota è che è tornato lento.
     */
    @Test
    fun `oltre il tetto si risponde subito invece di aspettare`() = runTest {
        val cambiamenti = Cambiamenti(segnali, backgroundScope, attesaMillis = 25_000L, massimo = 2)
        runCurrent()

        repeat(2) { backgroundScope.launch { cambiamenti.attendi(0L) { "nuovo" } } }
        runCurrent()

        val prima = testScheduler.currentTime
        assertNull(cambiamenti.attendi(0L) { "nuovo" }, "il terzo posto non c'era")
        assertEquals(prima, testScheduler.currentTime, "e non doveva aspettare per scoprirlo")
    }

    /** Il posto si libera anche quando l'attesa finisce, non solo quando scade. */
    @Test
    fun `il posto si libera dopo l'attesa`() = runTest {
        val cambiamenti = Cambiamenti(segnali, backgroundScope, attesaMillis = 1_000L, massimo = 1)
        runCurrent()

        assertNull(cambiamenti.attendi(0L) { "nuovo" }) // scade e libera
        val prima = testScheduler.currentTime
        assertNull(cambiamenti.attendi(0L) { "nuovo" })
        assertEquals(1_000L, testScheduler.currentTime - prima, "ha potuto aspettare di nuovo")
    }
}

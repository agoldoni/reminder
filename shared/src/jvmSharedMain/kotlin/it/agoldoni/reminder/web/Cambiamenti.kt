package it.agoldoni.reminder.web

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Quanto resta appesa una richiesta in attesa prima di rispondere `304`.
 *
 * **Venticinque secondi, e il numero ha due vincoli.** Verso l'alto: dev'essere abbondantemente
 * sotto la tolleranza di qualunque intermediario, e sotto il minuto che un browser considera una
 * connessione morta. Verso il basso: ogni scadenza costa una connessione nuova — niente keep-alive
 * — quindi accorciarla riporta al polling con l'aria di non farlo. A 25 s il conto delle
 * connessioni è **lo stesso** dei 30 s del polling di prima, e nel mezzo non passa un byte.
 */
internal const val ATTESA_MILLIS = 25_000L

/**
 * Quante richieste possono restare appese insieme.
 *
 * **Otto.** Gli utenti di questa porta sono le persone di una casa, ciascuna con al più due o tre
 * schede aperte: otto copre il caso reale con margine. Otto coroutine sospese e otto socket TLS
 * sono trascurabili perfino su un telefono, mentre alzarlo a trenta non regalerebbe niente a
 * nessuno se non a chi apre trenta connessioni di proposito.
 *
 * Superato il tetto **non si risponde un errore**: si risponde subito, come se l'attesa non fosse
 * stata chiesta. Il client ripiega da sé sul polling e l'unica cosa che nota è che è tornato lento.
 */
internal const val ATTESE_MASSIME = 8

/**
 * Il segnale di «qualcosa è cambiato», e il posto in cui si aspetta che arrivi.
 *
 * **La sorgente è una sola, ed è l'invalidazione del database.** Non un bus di notifiche chiamato a
 * mano dai punti di scrittura: quelli sono cinque — l'editor dell'app, il completamento, lo snooze
 * di una notifica, `SyncEngine`, `ScrittureWeb` — e un sesto arriverà. Un posto in più da cui
 * scrivere sarebbe un posto in più in cui dimenticarsi di annunciare, e quel difetto non si vede:
 * la pagina non smette di funzionare, sembra solo lenta. È lo stesso argomento per cui
 * `ScrittureWeb` è l'unico punto che tocca database e allarmi insieme.
 *
 * Il contatore è **monotono** e non porta con sé che cosa sia cambiato: a quella domanda risponde
 * già l'impronta del corpo, che è calcolata sul corpo stesso e quindi non può sbagliarsi.
 *
 * [attesaMillis] e [massimo] sono parametri e non costanti lette qui dentro, per la ragione di
 * sempre in questo progetto: un test che aspettasse davvero venticinque secondi non verrebbe
 * eseguito.
 */
internal class Cambiamenti(
    sorgente: Flow<*> = emptyFlow<Any>(),
    scope: CoroutineScope? = null,
    private val attesaMillis: Long = ATTESA_MILLIS,
    private val massimo: Int = ATTESE_MASSIME
) {

    private val _versione = MutableStateFlow(0L)

    /**
     * Va letto **prima** di comporre la risposta, e non dopo.
     *
     * Al contrario, una scrittura che cadesse fra la composizione del corpo e la sottoscrizione al
     * segnale non sveglierebbe nessuno: il client resterebbe appeso fino alla scadenza con in mano
     * un dato già vecchio. Leggendolo prima, quella scrittura ha già mosso il contatore quando si
     * arriva ad aspettare, e [attendi] torna subito senza sospendere.
     *
     * È il difetto che si manifesterebbe come «ogni tanto la pagina è in ritardo di venticinque
     * secondi», cioè nel modo più difficile da riprodurre a comando.
     */
    val versione: Long get() = _versione.value

    private val appese = AtomicInteger(0)

    init {
        // `emptyFlow()` finisce subito: l'istanza inerte non lascia niente in giro.
        scope?.launch { sorgente.collect { _versione.update { precedente -> precedente + 1 } } }
    }

    /**
     * Aspetta che qualcosa cambi **davvero**, al massimo per [attesaMillis].
     *
     * [ricomponi] viene chiamata a ogni segnale e decide lei se quel segnale conta: restituisce la
     * risposta nuova, oppure `null` per dire «non è cambiato niente che riguardi chi sta
     * aspettando» e rimettersi in attesa. Serve perché una scrittura sulla tabella `events` può non
     * toccare i promemoria aperti — completare un evento già completato, un tombstone arrivato
     * dalla sincronizzazione, una riscrittura identica — e svegliare il browser su quei segnali
     * vorrebbe dire mandarlo a ridisegnare per niente, in un ciclo stretto.
     *
     * Restituisce `null` se è scaduta, oppure se non c'era posto (vedi [ATTESE_MASSIME]): al
     * chiamante le due cose vanno bene uguali, perché la risposta è la stessa.
     */
    suspend fun <T : Any> attendi(visto: Long, ricomponi: suspend () -> T?): T? {
        if (!occupaPosto()) return null
        try {
            return withTimeoutOrNull(attesaMillis) {
                var da = visto
                var esito: T? = null
                while (esito == null) {
                    // `first` guarda anche il valore corrente: se il contatore si è già mosso
                    // mentre si componeva la risposta, non si sospende affatto.
                    da = _versione.first { it > da }
                    esito = ricomponi()
                }
                esito
            }
        } finally {
            appese.decrementAndGet()
        }
    }

    /** Un posto libero fra le attese, o niente. Il ciclo è il modo di dire «e non uno di più». */
    private fun occupaPosto(): Boolean {
        while (true) {
            val ora = appese.get()
            if (ora >= massimo) return false
            if (appese.compareAndSet(ora, ora + 1)) return true
        }
    }

    companion object {
        /**
         * L'istanza che non si sveglia mai e non aspetta mai.
         *
         * È il valore predefinito di `Router` e di `WebService`, e serve a una cosa sola: i test
         * scritti prima di questa feature costruiscono quegli oggetti senza saperne nulla, e devono
         * continuare a dire esattamente quello che dicevano. Non nasconde un cablaggio dimenticato
         * — senza segnale l'attesa scade e si risponde `304`, cioè il ritmo di prima — e il
         * presidio contro la dimenticanza è il test che scrive sul DAO da fuori.
         */
        val fermo = Cambiamenti(attesaMillis = 0L)
    }
}

package it.agoldoni.reminder.web

import it.agoldoni.reminder.platform.nowMillis
import it.agoldoni.reminder.sync.constantTimeEquals
import it.agoldoni.reminder.sync.randomBytes
import java.util.concurrent.ConcurrentHashMap

/**
 * Alfabeto senza caratteri che si confondono a leggerli da uno schermo e a ridigitarli su un altro:
 * niente `i`, `l`, `o` fra le lettere e niente `1` fra le cifre. Sono esattamente 32 simboli, così
 * ogni carattere consuma cinque bit **senza polarizzazione**: prendendo il resto di una divisione
 * su un alfabeto di lunghezza diversa alcuni simboli uscirebbero più spesso di altri.
 */
private const val ALFABETO = "abcdefghjkmnpqrstuvwxyz023456789"

/** Otto caratteri, quaranta bit. Dimensionato su [SOGLIA_TENTATIVI], non sulla forza da solo. */
private const val LUNGHEZZA = 8

/**
 * Tentativi falliti tollerati da uno stesso indirizzo prima di smettere di rispondere.
 *
 * Dieci al minuto contro quaranta bit vuol dire che indovinare richiederebbe tempi geologici: è
 * la **coppia** token corto + soglia bassa a reggere, non il token da solo. Alzare la soglia
 * senza allungare il token romperebbe l'equilibrio.
 */
private const val SOGLIA_TENTATIVI = 10

/**
 * La finestra dopo la quale i tentativi si dimenticano. Un blocco definitivo trasformerebbe dieci
 * errori di battitura in un servizio da riavviare: chi sbaglia a digitare è quasi sempre
 * l'utente, non un attaccante.
 */
private const val FINESTRA_MILLIS = 60_000L

/**
 * I due segreti di una accensione. Non sono due livelli dello stesso token: sono **due token
 * diversi**, e il potere sta in quale dei due si è digitato nell'indirizzo.
 *
 * Perché due invece di un interruttore «consenti modifiche» nell'app: così [lettura] diventa una
 * cosa che si può **dare a qualcun altro**. Con un interruttore solo bisognava scegliere fra
 * «tutti guardano» e «tutti comandano».
 */
internal data class CoppiaToken(val lettura: String, val scrittura: String)

/**
 * I token che aprono la web app, con la loro difesa contro i tentativi ripetuti.
 *
 * Si rigenerano a ogni accensione e si invalidano a ogni spegnimento: un indirizzo copiato ieri
 * non funziona oggi, ed è questo a rendere accettabile che il token viaggi nell'URL.
 *
 * **Il contatore dei tentativi resta uno solo** anche con due segreti. È per indirizzo IP, non per
 * token: sdoppiarlo regalerebbe a chi sonda venti tentativi al minuto invece di dieci, che è
 * esattamente la metà della coppia «token corto + soglia bassa» su cui poggia tutto.
 */
internal class AccessToken(
    private val now: () -> Long = ::nowMillis,
    private val genera: () -> String = ::tokenCasuale
) {

    @Volatile
    private var corrente: CoppiaToken? = null

    private val tentativi = ConcurrentHashMap<String, Tentativi>()

    val coppia: CoppiaToken? get() = corrente

    /**
     * Due token nuovi, e tentativi azzerati: la sessione precedente non lascia strascichi.
     *
     * I due sono diversi per costruzione. Con quaranta bit una collisione è teorica, ma se
     * capitasse i due indirizzi sarebbero lo stesso indirizzo — e quello di sola lettura
     * comanderebbe. Costa un confronto.
     */
    fun rigenera(): CoppiaToken {
        tentativi.clear()
        val lettura = genera()
        var scrittura = genera()
        while (scrittura == lettura) scrittura = genera()
        return CoppiaToken(lettura, scrittura).also { corrente = it }
    }

    fun invalida() {
        corrente = null
        tentativi.clear()
    }

    /**
     * [provenienza] è l'indirizzo da cui arriva la richiesta: la soglia è per indirizzo, non
     * globale, perché un attaccante non deve poter chiudere fuori l'utente riempiendo il conto.
     */
    fun verifica(offerto: String?, provenienza: String): Accesso {
        val attesi = corrente ?: return Accesso.NEGATO
        if (bloccato(provenienza)) return Accesso.BLOCCATO
        val bytes = offerto?.encodeToByteArray()
        if (bytes == null) {
            registraFallimento(provenienza)
            return Accesso.NEGATO
        }

        // **Entrambi i confronti si eseguono sempre**, e i risultati si guardano dopo. Fermarsi al
        // primo che coincide reintrodurrebbe una differenza di tempo fra «era il primo» e «era il
        // secondo», che è precisamente ciò che `constantTimeEquals` esiste per togliere.
        val eScrittura = constantTimeEquals(bytes, attesi.scrittura.encodeToByteArray())
        val eLettura = constantTimeEquals(bytes, attesi.lettura.encodeToByteArray())

        return when {
            eScrittura -> { tentativi.remove(provenienza); Accesso.SCRITTURA }
            eLettura -> { tentativi.remove(provenienza); Accesso.LETTURA }
            else -> { registraFallimento(provenienza); Accesso.NEGATO }
        }
    }

    private fun bloccato(provenienza: String): Boolean {
        val conto = tentativi[provenienza] ?: return false
        if (now() - conto.dalle > FINESTRA_MILLIS) {
            tentativi.remove(provenienza)
            return false
        }
        return conto.quanti >= SOGLIA_TENTATIVI
    }

    private fun registraFallimento(provenienza: String) {
        val adesso = now()
        tentativi.compute(provenienza) { _, precedente ->
            if (precedente == null || adesso - precedente.dalle > FINESTRA_MILLIS) {
                Tentativi(1, adesso)
            } else {
                precedente.copy(quanti = precedente.quanti + 1)
            }
        }
    }

    private data class Tentativi(val quanti: Int, val dalle: Long)
}

/**
 * Esito del controllo. [NEGATO] e [BLOCCATO] devono produrre **la stessa** risposta sul filo: sono
 * distinti solo perché il secondo dice che il confronto non è nemmeno avvenuto, cosa che serve a
 * poterlo verificare in un test.
 */
internal enum class Accesso {
    LETTURA,
    SCRITTURA,
    NEGATO,
    BLOCCATO;

    /**
     * Il token di scrittura vale **anche** per leggere: senza, la pagina servita sull'indirizzo
     * completo non potrebbe caricare la propria lista, e servirebbero due schede aperte.
     */
    val puoLeggere: Boolean get() = this == LETTURA || this == SCRITTURA

    val puoScrivere: Boolean get() = this == SCRITTURA
}

private fun tokenCasuale(): String {
    val bytes = randomBytes(LUNGHEZZA)
    return buildString(LUNGHEZZA) {
        for (b in bytes) append(ALFABETO[b.toInt() and 0x1f])
    }
}

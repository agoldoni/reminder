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
 * Il token che apre la web app, con la sua difesa contro i tentativi ripetuti.
 *
 * Si rigenera a ogni accensione e si invalida a ogni spegnimento: un indirizzo copiato ieri non
 * funziona oggi, ed è questo a rendere accettabile che il token viaggi nell'URL.
 */
internal class AccessToken(
    private val now: () -> Long = ::nowMillis,
    private val genera: () -> String = ::tokenCasuale
) {

    @Volatile
    private var corrente: String? = null

    private val tentativi = ConcurrentHashMap<String, Tentativi>()

    val valore: String? get() = corrente

    /** Nuovo token, e tentativi azzerati: la sessione precedente non lascia strascichi. */
    fun rigenera(): String {
        tentativi.clear()
        return genera().also { corrente = it }
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
        val atteso = corrente ?: return Accesso.NEGATO
        if (bloccato(provenienza)) return Accesso.BLOCCATO
        val coincide = offerto != null && constantTimeEquals(
            offerto.encodeToByteArray(),
            atteso.encodeToByteArray()
        )
        return if (coincide) {
            tentativi.remove(provenienza)
            Accesso.CONSENTITO
        } else {
            registraFallimento(provenienza)
            Accesso.NEGATO
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
internal enum class Accesso { CONSENTITO, NEGATO, BLOCCATO }

private fun tokenCasuale(): String {
    val bytes = randomBytes(LUNGHEZZA)
    return buildString(LUNGHEZZA) {
        for (b in bytes) append(ALFABETO[b.toInt() and 0x1f])
    }
}

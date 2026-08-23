package it.agoldoni.reminder.web

import it.agoldoni.reminder.platform.nowMillis
import java.util.concurrent.ConcurrentHashMap

/**
 * Tentativi falliti tollerati da uno stesso indirizzo prima di smettere di rispondere.
 *
 * **Che cosa difende, adesso.** Fino alla feature 005 era metà della sicurezza: quaranta bit di
 * token contro dieci tentativi al minuto. Contro una firma HMAC-SHA256 non serve più a fermare chi
 * indovina — non si indovina una firma da 256 bit nemmeno con tutto il tempo del mondo — e resta per
 * una ragione più modesta: **bocciare chi sonda a raffica**, che su una porta esposta in rete
 * capita. Costa una `ConcurrentHashMap` e vale la pena tenerla.
 */
private const val SOGLIA_TENTATIVI = 10

/**
 * La finestra dopo la quale i tentativi si dimenticano. Un blocco definitivo trasformerebbe dieci
 * richieste sbagliate in un servizio da riavviare.
 */
private const val FINESTRA_MILLIS = 60_000L

/**
 * I due indirizzi che l'app mostra, e l'istante in cui smetteranno di valere.
 *
 * **Non sono due livelli dello stesso token: sono due token diversi**, e il potere sta in quale dei
 * due si è consegnato. È questo a rendere [lettura] una cosa che si può **dare a qualcun altro**;
 * con un solo token più un interruttore «consenti modifiche» bisognerebbe scegliere fra «tutti
 * guardano» e «tutti comandano».
 *
 * [scadenzaMillis] è **una sola** perché i due si coniano nello stesso istante e con la stessa
 * durata: due campi suggerirebbero una differenza che non c'è.
 */
internal data class CoppiaToken(
    val lettura: String,
    val scrittura: String,
    val scadenzaMillis: Long
)

/**
 * Chi conia i token d'accesso alla web app e chi li verifica, con la difesa contro chi sonda.
 *
 * **La differenza rispetto alla feature 004 è dove vive il segreto.** Prima erano otto caratteri
 * tenuti in memoria e rigenerati a ogni accensione: l'indirizzo *era* la credenziale, quindi
 * l'indirizzo moriva ogni volta che moriva il processo. Adesso il segreto è la chiave di firma su
 * disco ([ChiaveFirma]) e il token è solo ciò che con quella chiave si può dimostrare — così
 * spegnere l'interruttore chiude la porta senza cambiare le serrature, e per cambiarle c'è un gesto
 * suo, [revoca].
 *
 * **Il contatore dei tentativi resta uno solo** anche con due token, ed è per indirizzo IP:
 * sdoppiarlo regalerebbe a chi sonda venti tentativi al minuto invece di dieci.
 */
internal class AccessToken(
    private val chiavi: ChiaveFirma,
    private val now: () -> Long = ::nowMillis,
    private val durataMillis: Long = DURATA_ACCESSO_MILLIS
) {

    private val tentativi = ConcurrentHashMap<String, Tentativi>()

    /**
     * I due indirizzi da mostrare, freschi.
     *
     * **Coniarne di nuovi non invalida quelli consegnati prima**, ed è tutta la feature: questi due
     * sono buoni di consegna, non l'identità del servizio. Per questo `apri()` può chiamarla a ogni
     * ritorno in primo piano senza fare danni, mentre la stessa disinvoltura su [ChiaveFirma]
     * butterebbe fuori tutti.
     *
     * L'orologio si legge **una volta** per tutti e due, così la scadenza dichiarata è la stessa e
     * non «quasi la stessa». Nessun controllo che i due siano diversi, a differenza della 004: i
     * payload differiscono nel campo `p`, quindi non possono coincidere per costruzione.
     *
     * Solleva se la chiave non si può preparare: chi conia è `WebService.apri()`, che lo trasforma
     * in un messaggio e **non apre la porta** — vedi [ChiaveFirma].
     */
    fun coniaCoppia(): CoppiaToken {
        val adesso = now()
        val chiave = chiavi.caricaOCrea()
        val lettura = Jwt.firma(PermessiWeb.LETTURA, chiave, adesso, durataMillis)
        val scrittura = Jwt.firma(PermessiWeb.SCRITTURA, chiave, adesso, durataMillis)
        return CoppiaToken(lettura.token, scrittura.token, lettura.scadenzaMillis)
    }

    /**
     * Una chiave di firma nuova: **ogni indirizzo consegnato finora smette di valere**.
     *
     * I tentativi si azzerano insieme, come faceva `rigenera()`: chi arriva dopo una revoca comincia
     * da capo, e chi stava sondando non si porta dietro il conto.
     */
    fun revoca() {
        chiavi.revoca()
        tentativi.clear()
    }

    /**
     * [provenienza] è l'indirizzo da cui arriva la richiesta: la soglia è per indirizzo e non
     * globale, perché un attaccante non deve poter chiudere fuori l'utente riempiendo il conto.
     */
    fun verifica(offerto: String?, provenienza: String): Accesso {
        // Un guasto della chiave **non** è un tentativo fallito di chi sta chiedendo: è nostro, e
        // non deve consumargli la soglia. In pratica non capita — `apri()` la prepara prima di
        // aprire il socket, e da lì in poi è in memoria — ma un `500` dentro il gestore direbbe a
        // chi sonda che ha trovato qualcosa.
        val chiave = runCatching { chiavi.caricaOCrea() }.getOrNull() ?: return Accesso.NEGATO

        if (bloccato(provenienza)) return Accesso.BLOCCATO

        val accesso = Jwt.verifica(offerto, chiave, now())
        if (accesso == null) {
            registraFallimento(provenienza)
            return Accesso.NEGATO
        }

        tentativi.remove(provenienza)
        return when (accesso.p) {
            PermessiWeb.LETTURA -> Accesso.LETTURA
            PermessiWeb.SCRITTURA -> Accesso.SCRITTURA
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
 * Esito del controllo. [NEGATO] e [BLOCCATO] devono produrre **la stessa** risposta sul filo — un
 * `401` — e sono distinti solo perché il secondo dice che il confronto non è nemmeno avvenuto, cosa
 * che serve a poterlo verificare in un test.
 */
internal enum class Accesso {
    LETTURA,
    SCRITTURA,
    NEGATO,
    BLOCCATO;

    /**
     * Il token di scrittura vale **anche** per leggere: senza, la pagina aperta con l'indirizzo
     * completo non potrebbe caricare la propria lista.
     */
    val puoLeggere: Boolean get() = this == LETTURA || this == SCRITTURA

    val puoScrivere: Boolean get() = this == SCRITTURA

    /**
     * Se il rifiuto è «non so chi sei» invece di «so chi sei e non ti basta». È la distinzione che
     * separa il `401` dal `403`, e il client la usa per decidere se buttare via il token che ha:
     * un token di sola lettura respinto su una scrittura è un token **buono**.
     */
    val autenticato: Boolean get() = this == LETTURA || this == SCRITTURA
}

package it.agoldoni.reminder.web

import it.agoldoni.reminder.data.EventDao
import it.agoldoni.reminder.data.EventEntity
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Versione del formato di scambio con il browser. Viaggia nella busta perché un client vecchio
 * contro un server nuovo possa fallire con un messaggio esplicito invece di provare a capirsi.
 * Stessa ragione di `PROTOCOL_VERSION` fra dispositivi.
 *
 * **2 dalla feature 004**, che è il caso per cui questo numero era stato messo qui: la busta porta
 * i permessi e ogni evento porta il proprio `updatedAt`.
 */
internal const val WEB_PAYLOAD_VERSION = 2

/**
 * Che cosa permette **l'indirizzo** con cui si è entrati. Non cambia finché la porta resta accesa:
 * è una proprietà del token, non del momento.
 *
 * Serve alla presentazione — dice al JavaScript se disegnare i comandi — e **non è un controllo
 * d'accesso**: quello lo fa `Router` su ogni singola scrittura. Confondere le due cose vorrebbe
 * dire proteggere una porta nascondendone la maniglia.
 */
@Serializable
internal enum class PermessiWeb {
    @SerialName("lettura") LETTURA,
    @SerialName("scrittura") SCRITTURA
}

/**
 * Un promemoria come viaggia verso il browser. Deliberatamente **non** è [EventEntity].
 *
 * Cosa c'è e perché: [id] è l'identificatore con cui una scrittura futura nominerà l'evento — la
 * sola lettura è un primo passo, e aggiungerlo dopo sarebbe una rottura del formato, mentre ora
 * costa un campo. [notificationMillis] è già calcolato qui perché la formula
 * `dateTimeMillis - advanceMinutes * 60000` non venga riscritta in JavaScript.
 *
 * [updatedAt] esce perché il controllo ottimistico non ha altro su cui poggiare: il browser deve
 * poter dichiarare *che cosa credeva di modificare*, e fra la lettura e il salvataggio possono
 * passare trenta secondi. Non è identità — è un numero di versione della riga — e non rivela nulla
 * che il browser non veda già, visto che di quell'evento sta guardando titolo, data e descrizione.
 *
 * Cosa non c'è e perché: `uuid` e `origin` sono l'identità dell'evento fra dispositivi associati e
 * non hanno ragione di uscire verso un browser qualsiasi; `completed` e `deleted` sarebbero sempre
 * falsi, visto che si mandano solo i promemoria aperti.
 *
 * **Nessuna data formattata**: solo millisecondi. È il browser a formattare, e questo gli permette
 * di ricalcolare la fascia cromatica al passare dell'ora senza chiedere niente al server.
 */
@Serializable
internal data class VoceWeb(
    val id: Long,
    val titolo: String,
    val descrizione: String? = null,
    val dateTimeMillis: Long,
    val notificationMillis: Long,
    val updatedAt: Long
)

@Serializable
internal data class WebPayload(
    val versione: Int = WEB_PAYLOAD_VERSION,
    /** Che cosa permette l'indirizzo con cui si è entrati. */
    val permessi: PermessiWeb,
    /**
     * Se in **questo momento** la scrittura è esercitabile, cioè se l'app è aperta sul telefono.
     *
     * Sta accanto a [permessi] e non al suo posto perché sono due cose diverse: uno dice quale
     * potere ha l'indirizzo, l'altro se adesso lo si può usare. Fonderli darebbe una pagina che si
     * traveste da quella di sola lettura appena il telefono va in tasca, e chi ha copiato
     * l'indirizzo completo penserebbe di aver copiato quello sbagliato.
     */
    val scritturaDisponibile: Boolean,
    val eventi: List<VoceWeb>
)

/** Il corpo già serializzato e la sua impronta: si calcolano insieme perché vanno insieme. */
internal data class CorpoJson(val bytes: ByteArray, val etag: String) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is CorpoJson && etag == other.etag && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = etag.hashCode()
}

private val json = Json { encodeDefaults = true }

internal fun EventEntity.toVoceWeb() = VoceWeb(
    id = id,
    titolo = title,
    descrizione = description,
    dateTimeMillis = dateTimeMillis,
    notificationMillis = dateTimeMillis - advanceMinutes * 60_000L,
    updatedAt = updatedAt
)

/**
 * I promemoria aperti, nell'ordine in cui li mostra l'app: è la stessa query, `getAllOpen()`, che
 * già filtra i completati e i tombstone e ordina per data crescente. Ripetere qui i criteri di
 * quella query vorrebbe dire poterli far divergere.
 *
 * **È l'unico posto che costruisce la rappresentazione**, e ci passano sia la lettura sia le
 * risposte alle scritture: così le due non possono divergere, perché non hanno due strade.
 *
 * Due conseguenze del fatto che [permessi] e [scritturaDisponibile] finiscono **dentro** il corpo,
 * su cui si calcola l'impronta. La prima: due client con token diversi ricevono impronte diverse,
 * il che è innocuo perché ciascun browser confronta l'impronta con la propria. La seconda è utile
 * ed è voluta: quando l'app si apre sul telefono il corpo cambia, quindi l'impronta cambia, quindi
 * la pagina ridisegna e i comandi si riaccendono — **senza una sola richiesta in più** rispetto al
 * controllo periodico che già c'è.
 */
internal suspend fun corpoEventi(
    dao: EventDao,
    permessi: PermessiWeb,
    scritturaDisponibile: Boolean
): CorpoJson {
    val payload = WebPayload(
        permessi = permessi,
        scritturaDisponibile = scritturaDisponibile,
        eventi = dao.getAllOpen().map { it.toVoceWeb() }
    )
    val bytes = json.encodeToString(payload).encodeToByteArray()
    return CorpoJson(bytes, impronta(bytes))
}

/**
 * L'impronta si calcola sul **corpo**, non su `max(updatedAt)` o sul numero di righe.
 *
 * Su quelle due grandezze si ragiona a lungo e si sbaglia comunque: completare un promemoria lo
 * toglie dall'insieme degli aperti, e il massimo di `updatedAt` fra quelli rimasti può *scendere*
 * — un client che confrontasse quello si perderebbe l'aggiornamento. Sul corpo è corretta per
 * costruzione e costa un passaggio su pochi kilobyte.
 */
private fun impronta(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return '"' + digest.take(8).joinToString("") { "%02x".format(it) } + '"'
}

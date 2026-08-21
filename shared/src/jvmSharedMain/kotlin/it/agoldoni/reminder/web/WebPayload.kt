package it.agoldoni.reminder.web

import it.agoldoni.reminder.data.EventDao
import it.agoldoni.reminder.data.EventEntity
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Versione del formato di scambio con il browser. Viaggia nella busta e serve a quando arriverà la
 * scrittura: un client vecchio contro un server nuovo deve poter fallire con un messaggio
 * esplicito invece di provare a capirsi. Stessa ragione di `PROTOCOL_VERSION` fra dispositivi.
 */
internal const val WEB_PAYLOAD_VERSION = 1

/**
 * Un promemoria come viaggia verso il browser. Deliberatamente **non** è [EventEntity].
 *
 * Cosa c'è e perché: [id] è l'identificatore con cui una scrittura futura nominerà l'evento — la
 * sola lettura è un primo passo, e aggiungerlo dopo sarebbe una rottura del formato, mentre ora
 * costa un campo. [notificationMillis] è già calcolato qui perché la formula
 * `dateTimeMillis - advanceMinutes * 60000` non venga riscritta in JavaScript.
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
    val notificationMillis: Long
)

@Serializable
internal data class WebPayload(
    val versione: Int = WEB_PAYLOAD_VERSION,
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
    notificationMillis = dateTimeMillis - advanceMinutes * 60_000L
)

/**
 * I promemoria aperti, nell'ordine in cui li mostra l'app: è la stessa query, `getAllOpen()`, che
 * già filtra i completati e i tombstone e ordina per data crescente. Ripetere qui i criteri di
 * quella query vorrebbe dire poterli far divergere.
 */
internal suspend fun corpoEventi(dao: EventDao): CorpoJson {
    val payload = WebPayload(eventi = dao.getAllOpen().map { it.toVoceWeb() })
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

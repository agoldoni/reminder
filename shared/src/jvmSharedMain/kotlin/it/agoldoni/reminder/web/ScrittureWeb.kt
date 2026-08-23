package it.agoldoni.reminder.web

import it.agoldoni.reminder.data.EventDao
import it.agoldoni.reminder.data.EventEntity
import it.agoldoni.reminder.platform.AlarmScheduler
import it.agoldoni.reminder.platform.nowMillis
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * L'anticipo della notifica è un **insieme chiuso**, e non per gusto della simmetria con l'app.
 *
 * `EventEditScreen` sceglie l'etichetta da mostrare con `advanceOptions.first { it.first == … }` —
 * `first`, non `firstOrNull`. Un evento con un anticipo fuori da questo elenco farebbe **lanciare
 * l'editor dell'app** all'apertura, e sarebbe un promemoria non più modificabile dal telefono. È
 * il difetto che questo `Set` esiste per rendere impossibile.
 */
internal val ANTICIPI_AMMESSI = setOf(0, 5, 15, 30, 60)

/** Un titolo più lungo di così non è un titolo. Il limite ferma chi manda un megabyte, non chi scrive. */
internal const val MAX_TITOLO = 200

internal const val MAX_DESCRIZIONE = 2_000

/**
 * L'intervallo di date accettato. Fuori di qui non c'è un promemoria, c'è un numero sbagliato: una
 * data nel 1900 o nell'anno 30000 produrrebbe un allarme che non serve a nessuno.
 */
internal const val MIN_DATA = 0L
internal const val MAX_DATA = 4_102_444_800_000L // 1 gennaio 2100

/** Il corpo di una creazione: gli stessi quattro campi dell'editor dell'app, né uno di più. */
@Serializable
internal data class CreazioneWeb(
    val titolo: String,
    val descrizione: String? = null,
    val dateTimeMillis: Long,
    val advanceMinutes: Int
)

/**
 * Il corpo di una modifica. [completato] è un campo come gli altri, e questo è il motivo per cui
 * «fatto» e «annulla» non hanno una rotta propria: sono modifiche, e passano dallo stesso controllo
 * ottimistico di tutte le altre.
 */
@Serializable
internal data class ModificaWeb(
    val titolo: String,
    val descrizione: String? = null,
    val dateTimeMillis: Long,
    val advanceMinutes: Int,
    val completato: Boolean = false,
    /**
     * L'`updatedAt` che il browser aveva letto. **Obbligatorio**: se mancasse e si proseguisse lo
     * stesso, un client distratto potrebbe aggirare il controllo dei conflitti per omissione.
     */
    val attesoUpdatedAt: Long? = null
)

/** Che cosa è successo a una scrittura. Il router lo traduce in un codice di stato. */
internal sealed interface EsitoScrittura {
    /** Fatta. [creato] distingue il `201` dal `200`. */
    data class Fatta(val creato: Boolean) : EsitoScrittura

    /** Il corpo non si capisce, o dichiara una lunghezza che non c'è: `400`. */
    data class NonLeggibile(val motivo: String) : EsitoScrittura

    /** Un campo è fuori dalle regole: `422`, **con il nome del campo**. */
    data class NonValida(val campo: String, val motivo: String) : EsitoScrittura

    /** L'evento non esiste, o è un tombstone: `404`. */
    data object Assente : EsitoScrittura

    /** La riga è già andata avanti: `409`. */
    data object Conflitto : EsitoScrittura
}

private val json = Json { ignoreUnknownKeys = true }

/**
 * Le scritture che arrivano dal browser.
 *
 * **È l'unico punto che tocca il database e gli allarmi insieme**, ed è la ragione per cui esiste
 * come classe invece di stare dentro `Router`: un secondo posto da cui si può scrivere sarebbe un
 * secondo posto in cui dimenticarsi di riprogrammare la sveglia, e quel difetto non si vede — il
 * dato è giusto nel database e la notifica arriva all'ora vecchia. Se un giorno si potrà arrivare
 * al DAO senza passare di qui, questa garanzia è persa.
 *
 * Non sa nulla di HTTP: riceve un testo e restituisce un esito. È ciò che permette di provarla
 * senza mettere in mezzo la rete, esattamente come si fa con `SyncEngine`.
 */
internal class ScrittureWeb(
    private val dao: EventDao,
    private val alarms: AlarmScheduler,
    /** Identità di **questo telefono**: marchia gli eventi nati qui. Il browser non ne ha una. */
    private val deviceId: String,
    private val now: () -> Long = ::nowMillis
) {

    suspend fun crea(corpo: String): EsitoScrittura {
        val richiesta = decodifica<CreazioneWeb>(corpo)
            ?: return EsitoScrittura.NonLeggibile("il corpo non è un JSON che si capisca")

        val titolo = titoloValido(richiesta.titolo) ?: return invalido("titolo")
        valida(richiesta.dateTimeMillis, richiesta.advanceMinutes, richiesta.descrizione)
            ?.let { return it }

        val adesso = now()
        val evento = EventEntity(
            title = titolo,
            description = descrizioneNormalizzata(richiesta.descrizione),
            dateTimeMillis = richiesta.dateTimeMillis,
            advanceMinutes = richiesta.advanceMinutes,
            // `uuid` lo genera il valore predefinito dell'entità; `origin` è il telefono, non il
            // browser, che in questo sistema non è un dispositivo con un'identità.
            origin = deviceId,
            updatedAt = adesso
        )
        val id = dao.insert(evento)
        riprogramma(evento.copy(id = id))
        return EsitoScrittura.Fatta(creato = true)
    }

    suspend fun modifica(id: Long, corpo: String): EsitoScrittura {
        val richiesta = decodifica<ModificaWeb>(corpo)
            ?: return EsitoScrittura.NonLeggibile("il corpo non è un JSON che si capisca")
        val atteso = richiesta.attesoUpdatedAt
            ?: return EsitoScrittura.NonLeggibile("manca attesoUpdatedAt")

        val titolo = titoloValido(richiesta.titolo) ?: return invalido("titolo")
        valida(richiesta.dateTimeMillis, richiesta.advanceMinutes, richiesta.descrizione)
            ?.let { return it }

        // Si guarda **prima** se l'evento esiste, per poter distinguere «non c'è» da «è cambiato»:
        // sono due messaggi diversi per chi sta davanti alla pagina.
        val esistente = dao.getById(id) ?: return EsitoScrittura.Assente

        val adesso = now()
        val righe = dao.updateIfUnchanged(
            id = id,
            title = titolo,
            description = descrizioneNormalizzata(richiesta.descrizione),
            dateTimeMillis = richiesta.dateTimeMillis,
            advanceMinutes = richiesta.advanceMinutes,
            completed = richiesta.completato,
            attesoUpdatedAt = atteso,
            nowMillis = adesso
        )
        // Zero righe con l'evento che esiste vuol dire una cosa sola: qualcuno è arrivato prima.
        if (righe == 0) return EsitoScrittura.Conflitto

        // Si riprogramma sulla riga **esistente copiata**, non su una ricostruita dal JSON: `uuid`
        // e `origin` vengono da lì, e l'allarme deve corrispondere a ciò che è finito nel database.
        riprogramma(
            esistente.copy(
                title = titolo,
                description = descrizioneNormalizzata(richiesta.descrizione),
                dateTimeMillis = richiesta.dateTimeMillis,
                advanceMinutes = richiesta.advanceMinutes,
                completed = richiesta.completato,
                updatedAt = adesso
            )
        )
        return EsitoScrittura.Fatta(creato = false)
    }

    /**
     * La stessa regola di `SyncEngine.reschedule`, e va tenuta la stessa.
     *
     * Cancellare prima di riprogrammare non è ridondante: un evento diventato completato o spostato
     * indietro deve **perdere** l'allarme che aveva, e `schedule` da solo non lo toglierebbe.
     */
    private fun riprogramma(evento: EventEntity) {
        alarms.cancel(evento.id)
        if (!evento.deleted && !evento.completed) alarms.schedule(evento)
    }

    private inline fun <reified T> decodifica(corpo: String): T? =
        runCatching { json.decodeFromString<T>(corpo) }.getOrNull()

    /** Il `trim` è quello di `EventEditViewModel.save()`: due strade che normalizzano diverso divergono. */
    private fun titoloValido(grezzo: String): String? =
        grezzo.trim().takeIf { it.isNotEmpty() && it.length <= MAX_TITOLO }

    private fun descrizioneNormalizzata(grezza: String?): String? =
        grezza?.trim()?.ifBlank { null }

    private fun valida(quando: Long, anticipo: Int, descrizione: String?): EsitoScrittura? = when {
        quando !in MIN_DATA..MAX_DATA -> invalido("dateTimeMillis", "data fuori dall'intervallo")
        anticipo !in ANTICIPI_AMMESSI ->
            invalido("advanceMinutes", "valore non fra ${ANTICIPI_AMMESSI.joinToString(", ")}")
        (descrizione?.trim()?.length ?: 0) > MAX_DESCRIZIONE ->
            invalido("descrizione", "più lunga di $MAX_DESCRIZIONE caratteri")
        else -> null
    }

    private fun invalido(campo: String, motivo: String = "valore non ammesso") =
        EsitoScrittura.NonValida(campo, motivo)
}

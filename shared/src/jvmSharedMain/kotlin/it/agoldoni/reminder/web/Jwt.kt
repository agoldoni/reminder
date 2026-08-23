package it.agoldoni.reminder.web

import it.agoldoni.reminder.sync.Hkdf
import it.agoldoni.reminder.sync.constantTimeEquals
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Versione del formato del token. Viaggia dentro il payload per la stessa ragione di
 * [WEB_PAYLOAD_VERSION]: il giorno in cui il contenuto cambierà, un token vecchio deve essere
 * rifiutato con un no netto invece di essere interpretato a metà.
 */
internal const val JWT_VERSION = 1

/**
 * Quanto vale un indirizzo consegnato: **trenta giorni secchi**.
 *
 * Non c'è rinnovo a scorrimento, ed è una scelta e non una mancanza: un token che si rinfresca a
 * ogni richiesta trasformerebbe «trenta giorni» in «trenta giorni di inattività», cioè in nessuna
 * scadenza per chi la pagina la usa davvero. Il prezzo, accettato, è una riconsegna al mese.
 */
internal const val DURATA_ACCESSO_MILLIS = 30L * 24 * 60 * 60 * 1000

/**
 * Un token appena coniato, con l'istante in cui smetterà di valere.
 *
 * La scadenza esce **da qui** e non viene ricalcolata da chi mostra l'indirizzo: due formule che
 * dicono la stessa cosa prima o poi dicono cose diverse, e quella visibile all'utente sarebbe la
 * sbagliata.
 */
internal data class TokenConiato(val token: String, val scadenzaMillis: Long)

/** Il contenuto di un token, dopo che la firma è stata verificata. Prima non esiste. */
@Serializable
internal data class AccessoJwt(
    val v: Int,
    /**
     * Gli **stessi due letterali** di `PermessiWeb` nel payload della pagina (`"lettura"` /
     * `"scrittura"`): il valore che entra dal token e quello che esce verso il browser non hanno
     * due vocabolari, quindi non possono divergere.
     */
    val p: PermessiWeb,
    /** Emissione, in **secondi**. Non si verifica: emittente e verificatore hanno lo stesso orologio. */
    val iat: Long,
    /** Scadenza, in **secondi**. */
    val exp: Long
)

/**
 * Firma e verifica dei token d'accesso alla web app: JWT HS256, scritto a mano.
 *
 * **Perché a mano.** Le primitive ci sono già tutte in `sync/Crypto.kt` — `Hkdf.hmac` e
 * `constantTimeEquals`, già provate da `SecureChannel` — e ciò che manca è la sola codifica. Una
 * dipendenza in più sull'APK per centocinquanta righe non si giustifica, in un progetto che ha già
 * scritto a mano ODS, HKDF e DER.
 *
 * **La regola che rende sicuro tutto questo, e che non va toccata:**
 *
 * ```
 * 1. si spezza in tre segmenti; diverso da tre → null
 * 2. si ricalcola HMAC-SHA256 sui BYTE GREZZI di "<seg0>.<seg1>"
 * 3. constantTimeEquals con il terzo segmento decodificato; diverso → null
 * 4. SOLO ORA si decodifica seg1 e lo si legge: v, exp, p
 * 5. seg0 — l'header — non si decodifica MAI
 * ```
 *
 * Il punto 5 è il cuore. `alg: none` e la confusione fra algoritmi non vengono *respinte*: **non
 * hanno un posto dove entrare**, perché nessun ramo di questo file dipende da ciò che c'è scritto
 * nell'header. Non serve nemmeno confrontarlo con il nostro: la firma lo copre già, quindi un
 * header diverso produce una firma diversa e cade al punto 3. È la stessa forma di difesa di
 * `StaticAssets`, dove la risalita di percorso non è filtrata ma resa impossibile.
 *
 * **Non lancia mai.** Un token storto è un esito, non un'eccezione: `Base64.getUrlDecoder()` e
 * `Json.decodeFromString` lanciano volentieri, e lasciarle passare trasformerebbe un `401` in un
 * `500` — cioè direbbe a chi sonda che ha trovato qualcosa.
 */
internal object Jwt {

    /**
     * L'header, precalcolato: `{"alg":"HS256","typ":"JWT"}`.
     *
     * È una costante e non un oggetto serializzato perché non deve **mai** esistere un percorso di
     * codice che lo interpreti. Qui si scrive, e basta.
     */
    private const val HEADER = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    /**
     * `ignoreUnknownKeys` perché un campo sconosciuto dentro un payload **firmato** non è un
     * attacco: è un token emesso da una versione futura di questa stessa app. Un decoder severo
     * renderebbe impossibile aggiungere un claim senza invalidare tutto ciò che è in circolazione —
     * ed è [JWT_VERSION], non il decoder, il posto in cui si decide che cosa non si sa più leggere.
     */
    private val json = Json { ignoreUnknownKeys = true }

    fun firma(
        permessi: PermessiWeb,
        chiave: ByteArray,
        adessoMillis: Long,
        durataMillis: Long = DURATA_ACCESSO_MILLIS
    ): TokenConiato {
        // **Secondi, come vuole la RFC 7519** — e la conversione avviene qui dentro e in nessun
        // altro posto. Tutto il resto dell'app lavora in millisecondi: un'unità mista che gira
        // libera produce un token che scade fra cinquant'anni o fra mezz'ora, e non si nota finché
        // non è tardi.
        val iat = adessoMillis / 1000
        val exp = iat + durataMillis / 1000

        val payload = encoder.encodeToString(
            json.encodeToString(AccessoJwt(JWT_VERSION, permessi, iat, exp)).encodeToByteArray()
        )
        val firmato = "$HEADER.$payload"
        val firma = encoder.encodeToString(Hkdf.hmac(chiave, firmato.toByteArray(Charsets.US_ASCII)))
        return TokenConiato("$firmato.$firma", exp * 1000)
    }

    /** Il contenuto del token se la firma regge e non è scaduto; `null` in ogni altro caso. */
    fun verifica(token: String?, chiave: ByteArray, adessoMillis: Long): AccessoJwt? {
        if (token.isNullOrEmpty()) return null

        // `split` senza limite: `a.b.c.d` dà quattro pezzi e cade qui, invece di far finta che il
        // quarto non esista. Un token con un segmento in più non è un token da interpretare.
        val pezzi = token.split('.')
        if (pezzi.size != 3) return null

        // (2) e (3): la firma, prima di qualunque lettura.
        val attesa = Hkdf.hmac(chiave, "${pezzi[0]}.${pezzi[1]}".toByteArray(Charsets.US_ASCII))
        val offerta = decodifica(pezzi[2]) ?: return null
        if (!constantTimeEquals(attesa, offerta)) return null

        // (4): da qui in poi il contenuto è nostro, perché nessun altro sa produrre quella firma.
        val bytes = decodifica(pezzi[1]) ?: return null
        val accesso = runCatching {
            json.decodeFromString<AccessoJwt>(String(bytes, Charsets.UTF_8))
        }.getOrNull() ?: return null

        if (accesso.v != JWT_VERSION) return null
        // `>=` e non `>`: `exp` è l'istante **a partire dal quale** il token non vale più.
        if (adessoMillis >= accesso.exp * 1000) return null

        // `iat` non si verifica: emittente e verificatore sono lo stesso processo sullo stesso
        // telefono, quindi non esiste uno scarto di orologi da tollerare. Sta nel payload perché
        // dice quando è stato consegnato un indirizzo, non per essere controllato.
        return accesso
    }

    /** `null` invece dell'`IllegalArgumentException` che [Base64.Decoder] lancia sull'input storto. */
    private fun decodifica(segmento: String): ByteArray? =
        runCatching { decoder.decode(segmento) }.getOrNull()
}

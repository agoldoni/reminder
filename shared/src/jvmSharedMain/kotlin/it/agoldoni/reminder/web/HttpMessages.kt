package it.agoldoni.reminder.web

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Il pezzo di HTTP/1.1 che serve a questa feature, e non un byte di più.
 *
 * Si accettano solo richieste senza corpo, si risponde sempre con `Connection: close` e non si
 * gestiscono né keep-alive né `Transfer-Encoding: chunked`. Non è pigrizia: ogni costrutto in più
 * è superficie in più su codice che legge da una rete, e qui la superficie è l'unica difesa che
 * si controlla davvero.
 *
 * Tutti i limiti sono espliciti e piccoli. Un client che li supera non riceve un errore di
 * dettaglio ma un `400` secco: raccontargli *quale* limite ha superato non gli serve, e a chi
 * sonda serve.
 */
internal object Http {

    /** Riga di richiesta e riga di header: 8 KiB è il limite di fatto dei server veri. */
    const val MAX_LINEA = 8 * 1024

    /** Oltre questo numero di header la richiesta non è una richiesta, è un tentativo. */
    const val MAX_HEADER = 50

    private const val CR = '\r'.code
    private const val LF = '\n'.code

    /**
     * Legge una riga terminata da `LF` (con o senza `CR` davanti), al massimo [max] byte.
     *
     * Restituisce `null` se lo stream finisce prima di qualunque byte — il client ha chiuso senza
     * dire niente, che è normale — e lancia [RigaTroppoLunga] se sfonda il limite. La distinzione
     * conta: nel primo caso non c'è nessuno a cui rispondere.
     */
    fun leggiRiga(input: InputStream, max: Int = MAX_LINEA): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            when {
                b == -1 -> return if (buffer.size() == 0) null else buffer.toString(Charsets.ISO_8859_1.name())
                b == LF -> {
                    val bytes = buffer.toByteArray()
                    val fine = if (bytes.isNotEmpty() && bytes.last().toInt() == CR) bytes.size - 1 else bytes.size
                    return String(bytes, 0, fine, Charsets.ISO_8859_1)
                }
                buffer.size() >= max -> throw RigaTroppoLunga()
                else -> buffer.write(b)
            }
        }
    }
}

internal class RigaTroppoLunga : IOException("riga oltre il limite")

/** Una richiesta letta dalla rete, ridotta a ciò che il router deve sapere. */
internal data class HttpRequest(
    val method: String,
    /** Percorso già decodificato e normalizzato; sempre iniziante per `/`. */
    val path: String,
    val query: Map<String, String>,
    /** Nomi in minuscolo: gli header HTTP non distinguono maiuscole e minuscole. */
    val headers: Map<String, String>
) {
    fun header(nome: String): String? = headers[nome.lowercase()]
}

/** Esito della lettura di una richiesta. Un guasto è un esito, non un'eccezione da far risalire. */
internal sealed interface RichiestaLetta {
    data class Ok(val request: HttpRequest) : RichiestaLetta

    /** Si è capito abbastanza da poter rispondere `400`. */
    data class Malformata(val motivo: String) : RichiestaLetta

    /** Il client ha chiuso senza mandare nulla: non c'è niente a cui rispondere. */
    data object Chiusa : RichiestaLetta
}

/**
 * Legge richiesta e header, fermandosi alla riga vuota. **Il corpo non viene letto**: qui si
 * risponde solo a `GET`, e un corpo che non si legge è un corpo che non si deve interpretare.
 */
internal fun leggiRichiesta(input: InputStream): RichiestaLetta {
    val riga = try {
        Http.leggiRiga(input) ?: return RichiestaLetta.Chiusa
    } catch (troppoLunga: RigaTroppoLunga) {
        return RichiestaLetta.Malformata("riga di richiesta oltre il limite")
    }
    if (riga.isBlank()) return RichiestaLetta.Malformata("riga di richiesta vuota")

    val pezzi = riga.split(' ')
    if (pezzi.size != 3) return RichiestaLetta.Malformata("riga di richiesta non in tre parti")
    val (metodo, bersaglio, versione) = pezzi
    if (!versione.startsWith("HTTP/")) return RichiestaLetta.Malformata("versione non dichiarata")
    if (metodo.isEmpty() || metodo.any { it !in 'A'..'Z' }) {
        return RichiestaLetta.Malformata("metodo non valido")
    }

    val headers = mutableMapOf<String, String>()
    while (true) {
        val h = try {
            Http.leggiRiga(input) ?: return RichiestaLetta.Malformata("header interrotti")
        } catch (troppoLunga: RigaTroppoLunga) {
            return RichiestaLetta.Malformata("header oltre il limite")
        }
        if (h.isEmpty()) break
        if (headers.size >= Http.MAX_HEADER) return RichiestaLetta.Malformata("troppi header")
        val duePunti = h.indexOf(':')
        if (duePunti <= 0) return RichiestaLetta.Malformata("header senza due punti")
        headers[h.substring(0, duePunti).trim().lowercase()] = h.substring(duePunti + 1).trim()
    }

    val taglio = bersaglio.indexOf('?')
    val grezzoPercorso = if (taglio >= 0) bersaglio.substring(0, taglio) else bersaglio
    val grezzaQuery = if (taglio >= 0) bersaglio.substring(taglio + 1) else ""

    val percorso = decodificaPercorso(grezzoPercorso)
        ?: return RichiestaLetta.Malformata("percorso non decodificabile")

    return RichiestaLetta.Ok(HttpRequest(metodo, percorso, leggiQuery(grezzaQuery), headers))
}

/**
 * Decodifica le sequenze `%XX` e verifica che ciò che ne esce sia un percorso e non un'arma.
 *
 * La decodifica avviene **una volta sola**: decodificare due volte è il modo classico di far
 * passare `%252e%252e%2f`. Dopo la decodifica si rifiuta tutto ciò che contiene `..`, byte di
 * controllo o barre rovesce — non perché il servizio degli asset ne abbia bisogno (quello lavora
 * su un elenco chiuso e sarebbe al sicuro comunque), ma perché un percorso strano non deve
 * arrivare intero fino a lì per essere scartato: si ferma qui, dove si vede.
 */
private fun decodificaPercorso(grezzo: String): String? {
    if (!grezzo.startsWith('/')) return null
    val uscita = StringBuilder(grezzo.length)
    var i = 0
    while (i < grezzo.length) {
        val c = grezzo[i]
        when {
            c == '%' -> {
                if (i + 2 >= grezzo.length) return null
                val valore = grezzo.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                uscita.append(valore.toChar())
                i += 3
            }
            else -> {
                uscita.append(c)
                i++
            }
        }
    }
    val percorso = uscita.toString()
    if (percorso.any { it.code < 0x20 || it.code == 0x7f }) return null
    if (percorso.contains("..") || percorso.contains('\\')) return null
    if (percorso.contains("//")) return null
    return percorso
}

private fun leggiQuery(grezza: String): Map<String, String> {
    if (grezza.isEmpty()) return emptyMap()
    val mappa = mutableMapOf<String, String>()
    for (coppia in grezza.split('&')) {
        if (coppia.isEmpty()) continue
        val uguale = coppia.indexOf('=')
        val chiave = if (uguale >= 0) coppia.substring(0, uguale) else coppia
        val valore = if (uguale >= 0) coppia.substring(uguale + 1) else ""
        // Prima chiave vince: `?t=buono&t=cattivo` non deve poter scavalcare il primo valore.
        mappa.putIfAbsent(decodificaValore(chiave), decodificaValore(valore))
    }
    return mappa
}

private fun decodificaValore(grezzo: String): String {
    val uscita = StringBuilder(grezzo.length)
    var i = 0
    while (i < grezzo.length) {
        val c = grezzo[i]
        when {
            c == '+' -> { uscita.append(' '); i++ }
            c == '%' && i + 2 < grezzo.length -> {
                val valore = grezzo.substring(i + 1, i + 3).toIntOrNull(16)
                if (valore == null) { uscita.append(c); i++ } else { uscita.append(valore.toChar()); i += 3 }
            }
            else -> { uscita.append(c); i++ }
        }
    }
    return uscita.toString()
}

/** Una risposta pronta da mandare. Il corpo è già byte: la codifica si decide una volta sola. */
internal data class HttpResponse(
    val status: Int,
    val contentType: String? = null,
    val body: ByteArray = ByteArray(0),
    /** Header aggiuntivi di questa risposta (`ETag`, per esempio). Quelli di sicurezza sono sotto. */
    val extra: Map<String, String> = emptyMap()
) {
    // data class con un ByteArray: equals/hashCode generati confronterebbero il riferimento.
    override fun equals(other: Any?): Boolean =
        this === other || (other is HttpResponse &&
            status == other.status && contentType == other.contentType &&
            extra == other.extra && body.contentEquals(other.body))

    override fun hashCode(): Int =
        (status * 31 + contentType.hashCode()) * 31 + body.contentHashCode()

    companion object {
        fun testo(status: Int, testo: String) =
            HttpResponse(status, "text/plain; charset=utf-8", testo.encodeToByteArray())

        /** Corpo vuoto di proposito: un `403` che spiega qualcosa aiuta solo chi sta sondando. */
        fun vuota(status: Int, extra: Map<String, String> = emptyMap()) =
            HttpResponse(status, extra = extra)
    }
}

/**
 * Gli header che vanno su **ogni** risposta.
 *
 * `Referrer-Policy: no-referrer` non è un di più: il token viaggia nell'URL, e senza questo
 * finirebbe nell'header `Referer` di ogni richiesta uscente. `no-store` tiene i promemoria fuori
 * dalla cache su disco del browser — e non contraddice le richieste condizionali, perché
 * l'impronta la conserva il nostro JavaScript in una variabile, non la cache HTTP.
 */
private val HEADER_DI_SICUREZZA = linkedMapOf(
    "Referrer-Policy" to "no-referrer",
    "Cache-Control" to "no-store",
    "X-Content-Type-Options" to "nosniff",
    "X-Frame-Options" to "DENY"
)

internal fun scriviRisposta(output: OutputStream, risposta: HttpResponse) {
    val testa = StringBuilder()
    testa.append("HTTP/1.1 ").append(risposta.status).append(' ')
        .append(descrizione(risposta.status)).append("\r\n")
    for ((nome, valore) in HEADER_DI_SICUREZZA) testa.append(nome).append(": ").append(valore).append("\r\n")
    for ((nome, valore) in risposta.extra) testa.append(nome).append(": ").append(valore).append("\r\n")
    risposta.contentType?.let { testa.append("Content-Type: ").append(it).append("\r\n") }
    // Anche su 304, dove il corpo è vietato: dichiarare zero è più chiaro che tacere.
    testa.append("Content-Length: ").append(risposta.body.size).append("\r\n")
    testa.append("Connection: close\r\n\r\n")

    output.write(testa.toString().toByteArray(Charsets.ISO_8859_1))
    if (risposta.body.isNotEmpty()) output.write(risposta.body)
    output.flush()
}

private fun descrizione(status: Int) = when (status) {
    200 -> "OK"
    304 -> "Not Modified"
    400 -> "Bad Request"
    403 -> "Forbidden"
    404 -> "Not Found"
    405 -> "Method Not Allowed"
    else -> "Error"
}

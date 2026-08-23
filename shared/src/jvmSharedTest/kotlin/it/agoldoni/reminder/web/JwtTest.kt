package it.agoldoni.reminder.web

import it.agoldoni.reminder.sync.Hkdf
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val CHIAVE = ByteArray(32) { it.toByte() }
private val ALTRA_CHIAVE = ByteArray(32) { (it + 1).toByte() }

private const val ADESSO = 1_700_000_000_000L

private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
private val decoder: Base64.Decoder = Base64.getUrlDecoder()

private fun b64(testo: String) = encoder.encodeToString(testo.encodeToByteArray())

/**
 * Compone un token **firmato correttamente** su un header e un payload arbitrari: serve a provare
 * che cosa succede a un contenuto che la firma copre davvero, senza poter riusare il coniatore vero.
 */
private fun componi(header: String, payload: String, chiave: ByteArray = CHIAVE): String {
    val firmato = "${b64(header)}.${b64(payload)}"
    val firma = encoder.encodeToString(Hkdf.hmac(chiave, firmato.toByteArray(Charsets.US_ASCII)))
    return "$firmato.$firma"
}

private fun payloadDi(token: String) = String(decoder.decode(token.split('.')[1]), Charsets.UTF_8)

/** TC-01, TC-02, TC-03 — il coniatore, la batteria avversariale, la scadenza. */
class JwtTest {

    // --- TC-01: il giro completo ----------------------------------------------------------------

    @Test
    fun `un token di lettura si verifica e dichiara la lettura`() {
        val coniato = Jwt.firma(PermessiWeb.LETTURA, CHIAVE, ADESSO)
        val letto = assertNotNull(Jwt.verifica(coniato.token, CHIAVE, ADESSO))
        assertEquals(PermessiWeb.LETTURA, letto.p)
        assertEquals(JWT_VERSION, letto.v)
    }

    @Test
    fun `un token di scrittura si verifica e dichiara la scrittura`() {
        val coniato = Jwt.firma(PermessiWeb.SCRITTURA, CHIAVE, ADESSO)
        assertEquals(PermessiWeb.SCRITTURA, Jwt.verifica(coniato.token, CHIAVE, ADESSO)?.p)
    }

    @Test
    fun `i due token coniati insieme sono diversi`() {
        val lettura = Jwt.firma(PermessiWeb.LETTURA, CHIAVE, ADESSO).token
        val scrittura = Jwt.firma(PermessiWeb.SCRITTURA, CHIAVE, ADESSO).token
        assertTrue(lettura != scrittura, "due permessi diversi non possono dare lo stesso token")
    }

    @Test
    fun `l'header e' quello dichiarato, e non viene mai letto`() {
        val token = Jwt.firma(PermessiWeb.LETTURA, CHIAVE, ADESSO).token
        assertEquals(
            """{"alg":"HS256","typ":"JWT"}""",
            String(decoder.decode(token.split('.')[0]), Charsets.UTF_8)
        )
    }

    // --- TC-03: la scadenza ---------------------------------------------------------------------

    @Test
    fun `la scadenza e' trenta giorni dopo la coniatura`() {
        val coniato = Jwt.firma(PermessiWeb.LETTURA, CHIAVE, ADESSO)
        assertEquals(ADESSO + DURATA_ACCESSO_MILLIS, coniato.scadenzaMillis)
    }

    @Test
    fun `un millisecondo prima della scadenza vale ancora`() {
        val coniato = Jwt.firma(PermessiWeb.LETTURA, CHIAVE, ADESSO)
        assertNotNull(Jwt.verifica(coniato.token, CHIAVE, coniato.scadenzaMillis - 1))
    }

    @Test
    fun `alla scadenza esatta non vale piu'`() {
        val coniato = Jwt.firma(PermessiWeb.LETTURA, CHIAVE, ADESSO)
        assertNull(
            Jwt.verifica(coniato.token, CHIAVE, coniato.scadenzaMillis),
            "`exp` e' l'istante A PARTIRE DAL QUALE il token non vale piu'"
        )
    }

    @Test
    fun `scaduto di un secondo non vale`() {
        val coniato = Jwt.firma(PermessiWeb.LETTURA, CHIAVE, ADESSO)
        assertNull(Jwt.verifica(coniato.token, CHIAVE, coniato.scadenzaMillis + 1_000))
    }

    @Test
    fun `i secondi non escono da Jwt`() {
        // `iat` ed `exp` sono in secondi dentro il payload, ma tutto cio' che esce e' in
        // millisecondi: e' l'unico punto in cui le due unita' si toccano.
        val coniato = Jwt.firma(PermessiWeb.LETTURA, CHIAVE, ADESSO)
        assertTrue(payloadDi(coniato.token).contains("\"exp\":${(ADESSO + DURATA_ACCESSO_MILLIS) / 1000}"))
        assertEquals(ADESSO + DURATA_ACCESSO_MILLIS, coniato.scadenzaMillis)
    }

    @Test
    fun `una durata piu' corta si rispetta`() {
        val coniato = Jwt.firma(PermessiWeb.LETTURA, CHIAVE, ADESSO, durataMillis = 60_000)
        assertEquals(ADESSO + 60_000, coniato.scadenzaMillis)
        assertNotNull(Jwt.verifica(coniato.token, CHIAVE, ADESSO + 59_999))
        assertNull(Jwt.verifica(coniato.token, CHIAVE, ADESSO + 60_000))
    }

    // --- TC-02: la batteria avversariale --------------------------------------------------------

    @Test
    fun `una firma manomessa non entra`() {
        val token = Jwt.firma(PermessiWeb.SCRITTURA, CHIAVE, ADESSO).token
        val pezzi = token.split('.')
        val guastata = pezzi[2].let { it.dropLast(1) + if (it.last() == 'A') 'B' else 'A' }
        assertNull(Jwt.verifica("${pezzi[0]}.${pezzi[1]}.$guastata", CHIAVE, ADESSO))
    }

    @Test
    fun `un payload manomesso non entra`() {
        // Il caso che conta: si prende un token di lettura e si prova a promuoverlo a scrittura.
        val token = Jwt.firma(PermessiWeb.LETTURA, CHIAVE, ADESSO).token
        val pezzi = token.split('.')
        val promosso = b64(payloadDi(token).replace("lettura", "scrittura"))
        assertNull(Jwt.verifica("${pezzi[0]}.$promosso.${pezzi[2]}", CHIAVE, ADESSO))
    }

    @Test
    fun `un header manomesso non entra, anche se nessuno lo legge`() {
        val token = Jwt.firma(PermessiWeb.LETTURA, CHIAVE, ADESSO).token
        val pezzi = token.split('.')
        assertNull(Jwt.verifica("${b64("""{"alg":"HS512"}""")}.${pezzi[1]}.${pezzi[2]}", CHIAVE, ADESSO))
    }

    @Test
    fun `alg none con firma vuota non entra`() {
        val payload = b64("""{"v":1,"p":"scrittura","iat":1,"exp":99999999999}""")
        assertNull(Jwt.verifica("${b64("""{"alg":"none"}""")}.$payload.", CHIAVE, ADESSO))
    }

    @Test
    fun `un token firmato con un'altra chiave non entra`() {
        val token = Jwt.firma(PermessiWeb.SCRITTURA, ALTRA_CHIAVE, ADESSO).token
        assertNull(Jwt.verifica(token, CHIAVE, ADESSO))
    }

    @Test
    fun `una firma della lunghezza sbagliata non entra`() {
        val pezzi = Jwt.firma(PermessiWeb.LETTURA, CHIAVE, ADESSO).token.split('.')
        val corta = encoder.encodeToString(ByteArray(16))
        assertNull(Jwt.verifica("${pezzi[0]}.${pezzi[1]}.$corta", CHIAVE, ADESSO))
    }

    @Test
    fun `una versione sconosciuta non entra`() {
        val token = componi("""{"alg":"HS256","typ":"JWT"}""", """{"v":2,"p":"lettura","iat":1,"exp":99999999999}""")
        assertNull(Jwt.verifica(token, CHIAVE, ADESSO), "firmato bene, ma di un formato che non si sa leggere")
    }

    @Test
    fun `un permesso sconosciuto non entra`() {
        val token = componi(
            """{"alg":"HS256","typ":"JWT"}""",
            """{"v":1,"p":"amministratore","iat":1,"exp":99999999999}"""
        )
        assertNull(Jwt.verifica(token, CHIAVE, ADESSO))
    }

    @Test
    fun `un payload senza exp non entra`() {
        val token = componi("""{"alg":"HS256","typ":"JWT"}""", """{"v":1,"p":"lettura","iat":1}""")
        assertNull(Jwt.verifica(token, CHIAVE, ADESSO), "senza scadenza non e' un token di questa app")
    }

    @Test
    fun `un payload firmato ma non decodificabile non entra`() {
        // Firma valida su un secondo segmento che non e' base64url: e' l'unico modo di arrivare
        // vivi al punto (4) con dei byte che non si possono leggere.
        val firmato = "${b64("""{"alg":"HS256","typ":"JWT"}""")}.!!!"
        val firma = encoder.encodeToString(Hkdf.hmac(CHIAVE, firmato.toByteArray(Charsets.US_ASCII)))
        assertNull(Jwt.verifica("$firmato.$firma", CHIAVE, ADESSO))
    }

    @Test
    fun `un payload firmato che non e' JSON non entra`() {
        assertNull(Jwt.verifica(componi("""{"alg":"HS256","typ":"JWT"}""", "non sono json"), CHIAVE, ADESSO))
    }

    @Test
    fun `un campo in piu' nel payload non da' fastidio`() {
        // `ignoreUnknownKeys` e' una scelta: un claim sconosciuto dentro un payload FIRMATO viene
        // da una versione futura di questa stessa app, non da un attaccante. E' `v` a decidere che
        // cosa non si sa piu' leggere, non il decoder.
        val token = componi(
            """{"alg":"HS256","typ":"JWT"}""",
            """{"v":1,"p":"lettura","iat":1,"exp":99999999999,"jti":"abc"}"""
        )
        assertEquals(PermessiWeb.LETTURA, Jwt.verifica(token, CHIAVE, ADESSO)?.p)
    }

    /**
     * **Il presidio della metrica M5.** Ogni forma storta immaginabile deve dare `null` — mai
     * un'eccezione. Un'eccezione qui non sarebbe un `401` ma un `500`, cioe' direbbe a chi sonda
     * che ha trovato qualcosa.
     */
    @Test
    fun `nessuna forma storta lancia — tutte danno null`() {
        val valido = Jwt.firma(PermessiWeb.SCRITTURA, CHIAVE, ADESSO).token
        val pezzi = valido.split('.')
        val storti = listOf(
            null,
            "",
            ".",
            "..",
            "...",
            "a",
            "a.b",
            "$valido.x",
            "$valido.",
            ".$valido",
            "${pezzi[0]}.${pezzi[1]}",
            "${pezzi[0]}.${pezzi[1]}.",
            "!!!.!!!.!!!",
            "${pezzi[0]}.${pezzi[1]}.!!!",
            // base64 **standard** invece di base64url: i caratteri `+` e `/` non appartengono
            // all'alfabeto di questo decoder.
            "${pezzi[0]}.${pezzi[1]}.a+b/c",
            " $valido",
            "$valido ",
            "Bearer $valido",
            pezzi.joinToString(".") { it.uppercase() }
        )
        for (storto in storti) {
            assertNull(Jwt.verifica(storto, CHIAVE, ADESSO), "avrebbe dovuto essere rifiutato: <$storto>")
        }
    }
}

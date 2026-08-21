package it.agoldoni.reminder.web

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * Codificatore ASN.1/DER ridotto a ciò che serve per comporre un certificato X.509.
 *
 * **Perché scritto a mano.** Su Android non c'è `keytool`, non c'è `sun.security.x509` e
 * BouncyCastle costerebbe megabyte nell'APK per un pugno di strutture — lo stesso ragionamento che
 * ha tenuto fuori `material-icons-extended` (37 MB per due icone) e le librerie ODS. È la terza
 * volta che questo progetto scrive a mano un formato invece di importarne il lettore: prima HKDF
 * (RFC 5869), poi il foglio ODF, ora questo.
 *
 * **Quanto è pericoloso sbagliare, qui.** Poco, ed è una conseguenza diretta dello scope della
 * feature 003: il certificato che questi byte compongono **non viene validato da nessuno** — il
 * browser avvisa e l'utente scavalca. Un difetto di codifica non apre un buco, cambia la forma
 * dell'avviso; il modo peggiore in cui può andare storto è che la JVM si rifiuti di caricare il
 * certificato e il server non parta, che è un guasto rumoroso e lo vedono i test.
 *
 * **Ciò che non serve codificare non si codifica.** La `SubjectPublicKeyInfo` arriva già in DER da
 * `PublicKey.getEncoded()` e la firma ECDSA arriva già in DER da `Signature`: entrambe entrano da
 * [raw] senza passare di qui.
 */
internal object Der {

    // ---- Tipi primitivi -----------------------------------------------------------------------

    /** `SEQUENCE`, cioè il mattone di quasi tutto in X.509. */
    fun sequence(vararg elementi: ByteArray): ByteArray = conTag(0x30, unisci(elementi))

    /** `SET`, che in un certificato compare solo dentro il `Name` (un `RelativeDistinguishedName`). */
    fun set(vararg elementi: ByteArray): ByteArray = conTag(0x31, unisci(elementi))

    /**
     * `INTEGER`. Si delega a [BigInteger.toByteArray], che produce già la rappresentazione in
     * complemento a due, big-endian e **minima**, con lo zero davanti quando il bit alto è acceso:
     * è esattamente la regola di DER, e riscriverla a mano sarebbe solo un modo di sbagliarla.
     */
    fun integer(valore: BigInteger): ByteArray = conTag(0x02, valore.toByteArray())

    fun integer(valore: Int): ByteArray = integer(BigInteger.valueOf(valore.toLong()))

    /**
     * `BIT STRING`. Il primo byte del contenuto dice quanti bit dell'ultimo byte non contano: per
     * ciò che serve qui — chiavi e firme, che sono sequenze di byte interi — è sempre zero, tranne
     * in `keyUsage`, che è una maschera di bit vera e passa [bitInutilizzati].
     */
    fun bitString(contenuto: ByteArray, bitInutilizzati: Int = 0): ByteArray =
        conTag(0x03, byteArrayOf(bitInutilizzati.toByte()) + contenuto)

    fun octetString(contenuto: ByteArray): ByteArray = conTag(0x04, contenuto)

    /** In DER `TRUE` è `0xFF` e non un byte diverso da zero qualsiasi: BER lo tollera, DER no. */
    fun boolean(valore: Boolean): ByteArray =
        conTag(0x01, byteArrayOf(if (valore) 0xFF.toByte() else 0x00))

    fun utf8String(testo: String): ByteArray = conTag(0x0C, testo.toByteArray(Charsets.UTF_8))

    /**
     * `OBJECT IDENTIFIER` dalla forma puntata. I primi due archi si fondono in un byte solo
     * (`40 * primo + secondo`), gli altri vanno in base 128 con il bit alto acceso su tutti i byte
     * tranne l'ultimo.
     */
    fun oid(punteggiato: String): ByteArray {
        val archi = punteggiato.split('.').map { it.toLong() }
        require(archi.size >= 2) { "un OID ha almeno due archi: $punteggiato" }
        val corpo = ByteArrayOutputStream()
        corpo.write((40 * archi[0] + archi[1]).toInt())
        archi.drop(2).forEach { corpo.write(base128(it)) }
        return conTag(0x06, corpo.toByteArray())
    }

    /**
     * `UTCTime` nella forma `yyMMddHHmmssZ`, **sempre in UTC**: scriverlo nel fuso del telefono
     * darebbe un certificato valido da un'ora sbagliata, e con un dispositivo a est di Greenwich
     * ancora non valido nel momento in cui viene presentato.
     *
     * Vale fino al 2049 — dal 2050 la RFC 5280 impone `GeneralizedTime` — e qui basta: la validità
     * più lunga che questa app emette è di dieci anni.
     */
    fun utcTime(istanteMillis: Long): ByteArray {
        val calendario = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = istanteMillis
        }
        val anno = calendario.get(Calendar.YEAR)
        require(anno in 1950..2049) { "UTCTime non copre l'anno $anno: servirebbe GeneralizedTime" }
        val testo = String.format(
            Locale.ROOT,
            "%02d%02d%02d%02d%02d%02dZ",
            anno % 100,
            calendario.get(Calendar.MONTH) + 1,
            calendario.get(Calendar.DAY_OF_MONTH),
            calendario.get(Calendar.HOUR_OF_DAY),
            calendario.get(Calendar.MINUTE),
            calendario.get(Calendar.SECOND)
        )
        return conTag(0x17, testo.toByteArray(Charsets.US_ASCII))
    }

    // ---- Tag di contesto ----------------------------------------------------------------------

    /**
     * `[n] EXPLICIT`: il contenuto resta incapsulato con il proprio tag. In un certificato serve
     * per la versione (`[0]`) e per le estensioni (`[3]`).
     */
    fun esplicito(numero: Int, contenuto: ByteArray): ByteArray = conTag(0xA0 or numero, contenuto)

    /**
     * `[n] IMPLICIT` primitivo: il tag di contesto **sostituisce** quello originale. Serve alle
     * voci del `subjectAltName`, dove un indirizzo IP è `[7]` e i suoi byte stanno lì nudi.
     */
    fun implicito(numero: Int, contenuto: ByteArray): ByteArray = conTag(0x80 or numero, contenuto)

    /** Byte già in DER prodotti da qualcun altro — chiave pubblica e firma — da infilare com'è. */
    fun raw(gia: ByteArray): ByteArray = gia

    // ---- Meccanica ----------------------------------------------------------------------------

    private fun conTag(tag: Int, contenuto: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + lunghezza(contenuto.size) + contenuto

    /**
     * La codifica della lunghezza è il punto in cui DER si rompe più spesso: sotto 128 va in un
     * byte solo, da 128 in su serve un byte che dice **quanti** byte di lunghezza seguono, e quei
     * byte devono essere il minimo indispensabile. Un solo byte di troppo e ogni verificatore
     * rifiuta la struttura.
     */
    private fun lunghezza(n: Int): ByteArray {
        if (n < 0x80) return byteArrayOf(n.toByte())
        var resto = n
        val cifre = ArrayDeque<Byte>()
        while (resto > 0) {
            cifre.addFirst((resto and 0xFF).toByte())
            resto = resto ushr 8
        }
        return byteArrayOf((0x80 or cifre.size).toByte()) + cifre.toByteArray()
    }

    /** Base 128 con il bit di continuazione acceso su tutti i byte tranne l'ultimo. */
    private fun base128(valore: Long): ByteArray {
        if (valore == 0L) return byteArrayOf(0)
        var resto = valore
        val cifre = ArrayDeque<Byte>()
        var ultimo = true
        while (resto > 0) {
            val sette = (resto and 0x7F).toInt()
            cifre.addFirst(if (ultimo) sette.toByte() else (sette or 0x80).toByte())
            ultimo = false
            resto = resto ushr 7
        }
        return cifre.toByteArray()
    }

    private fun unisci(pezzi: Array<out ByteArray>): ByteArray {
        val fuori = ByteArrayOutputStream()
        pezzi.forEach(fuori::write)
        return fuori.toByteArray()
    }
}

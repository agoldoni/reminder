package it.agoldoni.reminder.web

import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/** Un giorno in millisecondi: serve alla retrodatazione e al calcolo della scadenza. */
private const val GIORNO_MILLIS = 24L * 60 * 60 * 1000

/**
 * Il certificato che il server presenta ai browser: **autofirmato, generato una volta sola**.
 *
 * **Che cosa è, e che cosa non è.** Non autentica nessuno: il browser non lo conosce, avvisa, e
 * l'utente scavalca l'avviso. Serve solo perché TLS pretende che il server presenti un
 * certificato, e senza TLS il codice d'accesso viaggerebbe in chiaro su una rete condivisa — che è
 * l'unica cosa che questa feature esiste per impedire. Da qui discende tutto il resto: il
 * certificato non deve corrispondere all'indirizzo, non va riemesso quando il telefono cambia
 * rete, e un difetto in questi byte cambia la forma di un avviso, non apre un buco.
 *
 * **Perché allora le estensioni sono scritte per bene**, con `subjectAltName`, uso della chiave e
 * uso esteso, se nessuno le guarderà? Perché costano venti righe e fanno un certificato onesto
 * invece di un segnaposto: se un giorno lo si vorrà installare davvero in un archivio di fiducia,
 * è già pronto.
 *
 * La curva è **P-256**, la stessa scelta e per la stessa ragione di
 * [Crypto.kt](../sync/Crypto.kt): X25519 sulla JVM di Android arriva con l'API 31, mentre il
 * minimo qui è 26. Verificato sul campo che su API 26 la suite negoziata è
 * `TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256`.
 */
internal object SelfSignedCertificate {

    private val casuale = SecureRandom()

    /** `ecdsa-with-SHA256`. I **parametri restano assenti**: vedi [algoritmoDiFirma]. */
    private const val OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2"
    private const val OID_COMMON_NAME = "2.5.4.3"
    private const val OID_KEY_USAGE = "2.5.29.15"
    private const val OID_BASIC_CONSTRAINTS = "2.5.29.19"
    private const val OID_SUBJECT_ALT_NAME = "2.5.29.17"
    private const val OID_EXT_KEY_USAGE = "2.5.29.37"
    private const val OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1"

    /**
     * Genera coppia di chiavi e certificato.
     *
     * [adessoMillis] è passato dal chiamante e non letto da un orologio interno, per la stessa
     * disciplina già adottata dalle mutazioni del database: così i test lavorano a tempo virtuale
     * invece di aspettare.
     */
    fun genera(
        commonName: String,
        indirizzi: List<InetAddress>,
        adessoMillis: Long,
        validoPerGiorni: Int
    ): TlsIdentity {
        val coppia = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1"), casuale) }
            .generateKeyPair()

        val nome = nome(commonName)
        val tbs = Der.sequence(
            // v3. La versione è l'unico campo con un default (v1) e va scritta comunque: senza,
            // le estensioni non sarebbero ammesse.
            Der.esplicito(0, Der.integer(2)),
            Der.integer(seriale()),
            algoritmoDiFirma(),
            nome,
            Der.sequence(
                // Retrodatato di un giorno: due dispositivi con l'orologio leggermente diverso
                // darebbero un certificato «non ancora valido», e il messaggio del browser parla
                // di date, non di orologi.
                Der.utcTime(adessoMillis - GIORNO_MILLIS),
                Der.utcTime(adessoMillis + validoPerGiorni * GIORNO_MILLIS)
            ),
            // Autofirmato: emittente e soggetto sono lo stesso nome.
            nome,
            // Già in DER: `getEncoded()` di una chiave pubblica **è** una SubjectPublicKeyInfo.
            Der.raw(coppia.public.encoded),
            Der.esplicito(3, estensioni(indirizzi))
        )

        val firma = Signature.getInstance("SHA256withECDSA").run {
            initSign(coppia.private, casuale)
            update(tbs)
            sign() // già DER: è una Ecdsa-Sig-Value, entra nella BIT STRING com'è
        }

        val certificato = Der.sequence(tbs, algoritmoDiFirma(), Der.bitString(firma))
        val letto = CertificateFactory.getInstance("X.509")
            .generateCertificate(certificato.inputStream()) as X509Certificate
        return TlsIdentity(coppia.private, letto)
    }

    /**
     * `AlgorithmIdentifier` di ECDSA-con-SHA256: **solo l'OID, nessun parametro**.
     *
     * È la differenza con RSA, dove al posto dei parametri va un `NULL` esplicito, ed è un classico
     * di interoperabilità: la RFC 5758 dice che per ECDSA il campo `parameters` deve essere
     * *assente*, e scriverci `NULL` produce un certificato che qualche verificatore accetta e
     * qualche altro rifiuta — cioè il difetto peggiore, quello che si manifesta solo altrove.
     */
    private fun algoritmoDiFirma(): ByteArray = Der.sequence(Der.oid(OID_ECDSA_SHA256))

    /** `Name` con un solo `commonName`: non serve altro a un certificato che nessuno valida. */
    private fun nome(commonName: String): ByteArray = Der.sequence(
        Der.set(Der.sequence(Der.oid(OID_COMMON_NAME), Der.utf8String(commonName)))
    )

    /**
     * Positivo e non più lungo di 20 byte, come vuole la RFC 5280. 159 bit garantiscono entrambe le
     * cose senza casi limite da gestire: mai negativo, mai un byte di padding che sfori.
     */
    private fun seriale(): BigInteger = BigInteger(159, casuale)

    private fun estensioni(indirizzi: List<InetAddress>): ByteArray = Der.sequence(
        // Non è una CA. Critica, come vuole la RFC per un'estensione che nega una capacità.
        estensione(OID_BASIC_CONSTRAINTS, critica = true, valore = Der.sequence()),
        // Solo `digitalSignature` (bit 0): con ECDHE_ECDSA la chiave firma lo scambio, non cifra
        // nulla, quindi `keyEncipherment` sarebbe una dichiarazione falsa.
        estensione(
            OID_KEY_USAGE,
            critica = true,
            valore = Der.bitString(byteArrayOf(0x80.toByte()), bitInutilizzati = 7)
        ),
        estensione(
            OID_EXT_KEY_USAGE,
            critica = false,
            valore = Der.sequence(Der.oid(OID_SERVER_AUTH))
        ),
        // `[7] IMPLICIT` è la forma di un indirizzo IP dentro un GeneralName: i byte stanno lì
        // nudi, quattro per IPv4. Non critica, perché il soggetto non è vuoto.
        estensione(
            OID_SUBJECT_ALT_NAME,
            critica = false,
            valore = Der.sequence(*indirizzi.map { Der.implicito(7, it.address) }.toTypedArray())
        )
    )

    /**
     * `critical` ha `DEFAULT FALSE`, e in DER un valore predefinito **si omette**: scriverlo
     * produrrebbe una codifica che è BER valido e DER no.
     */
    private fun estensione(oid: String, critica: Boolean, valore: ByteArray): ByteArray =
        if (critica) {
            Der.sequence(Der.oid(oid), Der.boolean(true), Der.octetString(valore))
        } else {
            Der.sequence(Der.oid(oid), Der.octetString(valore))
        }
}

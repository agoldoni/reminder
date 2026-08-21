package it.agoldoni.reminder.web

import java.math.BigInteger
import java.util.Locale
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * TC-01/02 — il codificatore DER sui punti in cui DER si rompe davvero.
 *
 * Questi test non provano che il certificato sia valido: quello lo dice `TlsHandshakeTest`, con un
 * handshake vero. Qui si guardano solo i tre posti in cui una codifica scritta a mano sbaglia in
 * silenzio — la lunghezza al passaggio fra forma breve e forma lunga, l'intero che perde il byte
 * di segno, e l'ora scritta nel fuso sbagliato — perché lì il difetto non produce un errore ma un
 * byte diverso, e chi lo cerca partendo dall'handshake fallito ci mette un pomeriggio.
 *
 * Gli OID si confrontano con i byte della specifica, non con quelli prodotti da questo codice:
 * confrontare l'output con sé stesso congelerebbe l'errore invece di trovarlo.
 */
class DerTest {

    private val fusoOriginale: TimeZone = TimeZone.getDefault()

    @AfterTest
    fun ripristinaFuso() = TimeZone.setDefault(fusoOriginale)

    private fun esadecimale(byte: ByteArray) =
        byte.joinToString("") { "%02X".format(Locale.ROOT, it) }

    // ---- Lunghezze ----------------------------------------------------------------------------

    @Test
    fun `sotto 128 la lunghezza sta in un byte solo`() {
        val corpo = ByteArray(127)
        val codificato = Der.octetString(corpo)
        assertEquals(0x04, codificato[0].toInt() and 0xFF, "tag OCTET STRING")
        assertEquals(0x7F, codificato[1].toInt() and 0xFF, "forma breve")
        assertEquals(2 + 127, codificato.size)
    }

    @Test
    fun `a 128 si passa alla forma lunga`() {
        val codificato = Der.octetString(ByteArray(128))
        assertEquals(0x81, codificato[1].toInt() and 0xFF, "un byte di lunghezza segue")
        assertEquals(0x80, codificato[2].toInt() and 0xFF)
        assertEquals(3 + 128, codificato.size)
    }

    @Test
    fun `i confini della forma lunga`() {
        // 255 sta ancora in un byte; 256 ne richiede due; 65535 ancora due; 65536 tre.
        assertEquals("81FF", esadecimale(Der.octetString(ByteArray(255)).copyOfRange(1, 3)))
        assertEquals("820100", esadecimale(Der.octetString(ByteArray(256)).copyOfRange(1, 4)))
        assertEquals("82FFFF", esadecimale(Der.octetString(ByteArray(65535)).copyOfRange(1, 4)))
        assertEquals("83010000", esadecimale(Der.octetString(ByteArray(65536)).copyOfRange(1, 5)))
    }

    @Test
    fun `la lunghezza non porta zeri davanti`() {
        // Il difetto classico: scrivere sempre quattro byte di lunghezza. È BER valido e DER no.
        val codificato = Der.octetString(ByteArray(300))
        assertEquals(0x82, codificato[1].toInt() and 0xFF, "esattamente due byte, non quattro")
        assertEquals("012C", esadecimale(codificato.copyOfRange(2, 4)))
    }

    // ---- Interi -------------------------------------------------------------------------------

    @Test
    fun `un intero con il bit alto acceso prende lo zero davanti`() {
        // 255 non deve diventare un numero negativo: DER è in complemento a due.
        assertEquals("020200FF", esadecimale(Der.integer(255)))
        assertEquals("02017F", esadecimale(Der.integer(127)), "127 non ne ha bisogno")
    }

    @Test
    fun `un seriale lungo resta minimo`() {
        val seriale = BigInteger(1, ByteArray(20) { 0x01 })
        val codificato = Der.integer(seriale)
        assertEquals(0x02, codificato[0].toInt() and 0xFF)
        assertEquals(20, codificato[1].toInt() and 0xFF, "nessuno zero superfluo davanti")
    }

    @Test
    fun `lo zero è un byte solo`() {
        assertEquals("020100", esadecimale(Der.integer(0)))
    }

    // ---- OID ----------------------------------------------------------------------------------

    @Test
    fun `gli OID che servono a un certificato`() {
        // Byte presi dalle specifiche, non da questo codice.
        assertEquals("06082A8648CE3D040302", esadecimale(Der.oid("1.2.840.10045.4.3.2")), "ecdsa-with-SHA256")
        assertEquals("06082A8648CE3D030107", esadecimale(Der.oid("1.2.840.10045.3.1.7")), "prime256v1")
        assertEquals("0603551D11", esadecimale(Der.oid("2.5.29.17")), "subjectAltName")
        assertEquals("0603551D13", esadecimale(Der.oid("2.5.29.19")), "basicConstraints")
        assertEquals("0603551D0F", esadecimale(Der.oid("2.5.29.15")), "keyUsage")
        assertEquals("0603551D25", esadecimale(Der.oid("2.5.29.37")), "extKeyUsage")
        assertEquals("06082B06010505070301", esadecimale(Der.oid("1.3.6.1.5.5.7.3.1")), "serverAuth")
        assertEquals("0603550403", esadecimale(Der.oid("2.5.4.3")), "commonName")
    }

    @Test
    fun `i primi due archi si fondono in un byte`() {
        // 1.2 → 40*1+2 = 42 = 0x2A. È la regola che sembra un dettaglio e cambia tutto l'OID.
        assertEquals("06012A", esadecimale(Der.oid("1.2")))
    }

    // ---- Tempo --------------------------------------------------------------------------------

    @Test
    fun `l'ora si scrive in UTC anche se il dispositivo è altrove`() {
        // È il difetto che nessuno vede: il certificato «non è ancora valido» e il messaggio del
        // browser parla di date, non di fusi.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        val aTokyo = Der.utcTime(0L)
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        val aLosAngeles = Der.utcTime(0L)

        assertEquals("700101000000Z", String(aTokyo.copyOfRange(2, aTokyo.size), Charsets.US_ASCII))
        assertEquals(esadecimale(aTokyo), esadecimale(aLosAngeles), "il fuso non deve entrarci")
    }

    @Test
    fun `il tag e la forma di UTCTime`() {
        val codificato = Der.utcTime(1_755_000_000_000L) // 2025-08-12T12:00:00Z
        assertEquals(0x17, codificato[0].toInt() and 0xFF, "tag UTCTime")
        assertEquals(13, codificato[1].toInt() and 0xFF, "yyMMddHHmmssZ sono 13 caratteri")
        assertEquals("250812120000Z", String(codificato.copyOfRange(2, 15), Charsets.US_ASCII))
    }

    @Test
    fun `oltre il 2049 UTCTime non si può usare`() {
        // 2050-01-01. La RFC 5280 impone GeneralizedTime da lì in poi: meglio un errore netto ora
        // che un anno «50» interpretato come 1950 dal verificatore.
        val errore = assertFailsWith<IllegalArgumentException> { Der.utcTime(2_524_608_000_000L) }
        assertTrue("GeneralizedTime" in (errore.message ?: ""), errore.message ?: "")
    }

    // ---- Struttura ----------------------------------------------------------------------------

    @Test
    fun `booleano vero è FF e non un byte qualsiasi`() {
        assertEquals("0101FF", esadecimale(Der.boolean(true)))
        assertEquals("010100", esadecimale(Der.boolean(false)))
    }

    @Test
    fun `bit string porta davanti il conto dei bit inutilizzati`() {
        assertEquals("0303070080", esadecimale(Der.bitString(byteArrayOf(0x00, 0x80.toByte()), 7)))
        assertEquals("030200AB", esadecimale(Der.bitString(byteArrayOf(0xAB.toByte()))))
    }

    @Test
    fun `i tag di contesto distinguono esplicito da implicito`() {
        // [0] EXPLICIT incapsula; [7] IMPLICIT sostituisce il tag: è la differenza fra la versione
        // del certificato e un indirizzo IP dentro il subjectAltName.
        assertEquals("A003020102", esadecimale(Der.esplicito(0, Der.integer(2))))
        assertEquals("87047F000001", esadecimale(Der.implicito(7, byteArrayOf(127, 0, 0, 1))))
    }

    @Test
    fun `sequence e set annidano quel che ricevono`() {
        val nome = Der.sequence(Der.set(Der.sequence(Der.oid("2.5.4.3"), Der.utf8String("Promemoria"))))
        assertEquals(0x30, nome[0].toInt() and 0xFF)
        assertEquals(0x31, nome[2].toInt() and 0xFF)
        assertTrue("50726F6D656D6F726961" in esadecimale(nome), "il CN deve esserci in UTF-8")
    }
}

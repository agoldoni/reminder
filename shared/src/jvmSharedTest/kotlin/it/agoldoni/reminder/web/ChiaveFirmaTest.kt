package it.agoldoni.reminder.web

import java.io.File
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ADESSO_K = 1_700_000_000_000L

/**
 * TC-04/05/06 — il presidio del rischio R-09: **la chiave di firma nasce una volta sola**.
 *
 * È il gemello di `CertificateStoreTest`, con la posta più alta. Un certificato rigenerato rimette
 * l'utente davanti a un avviso; una chiave di firma rigenerata **butta fuori tutti i browser
 * insieme**, e in silenzio. Qui si confrontano i byte, perché sul dispositivo non si noterebbe: si
 * vedrebbe solo una pagina che dice «indirizzo scaduto» senza spiegare da quando.
 */
class ChiaveFirmaTest {

    private val cartella = File.createTempFile("promemoria-firma", "").let {
        it.delete()
        File(it.absolutePath)
    }

    private val file get() = File(cartella, "firma.key")

    @AfterTest
    fun pulisci() {
        cartella.listFiles()?.forEach { it.delete() }
        cartella.delete()
    }

    /** Una sorgente prevedibile: ogni chiamata dà una chiave diversa, ma sempre la stessa serie. */
    private fun sorgente(): (Int) -> ByteArray {
        var giro = 0
        return { quanti -> ByteArray(quanti) { (giro + it).toByte() }.also { giro++ } }
    }

    @Test
    fun `su cartella vuota nasce e finisce sul disco`() {
        val chiave = ChiaveFirma(cartella, sorgente()).caricaOCrea()

        assertEquals(32, chiave.size, "trentadue byte, la lunghezza naturale di HMAC-SHA256")
        assertTrue(file.isFile, "una chiave solo in memoria morirebbe con il processo")
        assertTrue(file.readBytes() contentEquals chiave)
    }

    @Test
    fun `la seconda chiamata sulla stessa istanza non rigenera`() {
        val archivio = ChiaveFirma(cartella, sorgente())
        assertTrue(archivio.caricaOCrea() contentEquals archivio.caricaOCrea())
    }

    @Test
    fun `un'istanza nuova sulla stessa cartella ritrova la stessa chiave`() {
        // È il caso vero: il processo muore, l'app riparte, e l'indirizzo consegnato ieri deve
        // continuare a valere. È tutta qui la feature.
        val prima = ChiaveFirma(cartella, sorgente()).caricaOCrea()
        val seconda = ChiaveFirma(cartella, sorgente()).caricaOCrea()

        assertTrue(prima contentEquals seconda, "la chiave deve sopravvivere al processo")
    }

    @Test
    fun `un token firmato prima del riavvio si verifica ancora dopo`() {
        val coniato = Jwt.firma(PermessiWeb.LETTURA, ChiaveFirma(cartella, sorgente()).caricaOCrea(), ADESSO_K)

        val dopoIlRiavvio = ChiaveFirma(cartella, sorgente()).caricaOCrea()

        assertNotNull(
            Jwt.verifica(coniato.token, dopoIlRiavvio, ADESSO_K),
            "il segnalibro sul tablet in cucina non deve rompersi da solo"
        )
    }

    @Test
    fun `revocare cambia la chiave`() {
        val archivio = ChiaveFirma(cartella, sorgente())
        val prima = archivio.caricaOCrea()
        val dopo = archivio.revoca()

        assertFalse(prima contentEquals dopo)
        assertTrue(file.readBytes() contentEquals dopo, "anche sul disco, o tornerebbe al riavvio")
    }

    @Test
    fun `dopo la revoca i token consegnati prima non valgono piu'`() {
        val archivio = ChiaveFirma(cartella, sorgente())
        val coniato = Jwt.firma(PermessiWeb.SCRITTURA, archivio.caricaOCrea(), ADESSO_K)

        val nuova = archivio.revoca()

        assertNull(Jwt.verifica(coniato.token, nuova, ADESSO_K), "è questa l'unica revoca che esiste")
    }

    @Test
    fun `la revoca vale anche per un'istanza costruita dopo`() {
        val archivio = ChiaveFirma(cartella, sorgente())
        val coniato = Jwt.firma(PermessiWeb.LETTURA, archivio.caricaOCrea(), ADESSO_K)
        archivio.revoca()

        val dopoIlRiavvio = ChiaveFirma(cartella, sorgente()).caricaOCrea()

        assertNull(Jwt.verifica(coniato.token, dopoIlRiavvio, ADESSO_K))
    }

    // --- TC-06: il file rotto -------------------------------------------------------------------

    @Test
    fun `un file della lunghezza sbagliata non apre la porta e si cancella`() {
        // **La differenza che conta rispetto a CertificateStore**, che in un caso analogo si
        // rigenera e tira avanti: qui rigenerare vorrebbe dire revocare tutto, e una revoca di
        // massa non deve poter avvenire in silenzio nel mezzo di una sessione.
        cartella.mkdirs()
        file.writeBytes(ByteArray(31))

        val errore = assertFailsWith<IOException> { ChiaveFirma(cartella, sorgente()).caricaOCrea() }

        assertContains(errore.message!!, "Riavvia l'app")
        assertFalse(file.exists(), "cancellarlo è ciò che impedisce di ripetere il rifiuto per sempre")
    }

    @Test
    fun `un file vuoto e' un file rotto`() {
        cartella.mkdirs()
        file.writeBytes(ByteArray(0))

        assertFailsWith<IOException> { ChiaveFirma(cartella, sorgente()).caricaOCrea() }
        assertFalse(file.exists())
    }

    @Test
    fun `un file troppo lungo e' un file rotto`() {
        cartella.mkdirs()
        file.writeBytes(ByteArray(33))

        assertFailsWith<IOException> { ChiaveFirma(cartella, sorgente()).caricaOCrea() }
        assertFalse(file.exists())
    }

    @Test
    fun `dopo il rifiuto il riavvio ricrea la chiave senza lamentarsi`() {
        // Il seguito del caso precedente, ed è la ragione per cui il file si cancella: al riavvio
        // non c'è più niente di rotto da leggere, quindi si passa dal percorso normale di nascita.
        cartella.mkdirs()
        file.writeBytes(ByteArray(31))
        assertFailsWith<IOException> { ChiaveFirma(cartella, sorgente()).caricaOCrea() }

        val chiave = ChiaveFirma(cartella, sorgente()).caricaOCrea()

        assertEquals(32, chiave.size)
        assertTrue(file.isFile)
    }

    @Test
    fun `revocare rimette in piedi una cartella con un file rotto`() {
        cartella.mkdirs()
        file.writeBytes(ByteArray(31))

        val chiave = ChiaveFirma(cartella, sorgente()).revoca()

        assertEquals(32, chiave.size)
        assertTrue(file.readBytes() contentEquals chiave)
    }

    // --- D-09: non si degrada a una chiave in memoria --------------------------------------------

    @Test
    fun `una cartella impossibile da creare diventa un errore, non una chiave effimera`() {
        // Tenerla solo in memoria sembrerebbe funzionare e non funzionerebbe: al riavvio
        // l'indirizzo appena consegnato sarebbe già morto. È il difetto che questa feature toglie.
        val impossibile = File(cartella, "file-non-cartella")
        cartella.mkdirs()
        impossibile.writeText("sono un file")

        val errore = assertFailsWith<IOException> {
            ChiaveFirma(File(impossibile, "sotto")).caricaOCrea()
        }
        assertContains(errore.message!!, "la porta resta chiusa")
    }

    @Test
    fun `la chiave non e' leggibile da altri`() {
        ChiaveFirma(cartella, sorgente()).caricaOCrea()

        assertTrue(file.canRead(), "il proprietario deve poterla leggere")
        val permessi = java.nio.file.Files.getPosixFilePermissions(file.toPath())
        assertTrue(
            permessi.none { it.name.startsWith("GROUP") || it.name.startsWith("OTHERS") },
            "la chiave di firma non deve essere leggibile da altri: $permessi"
        )
    }
}

package it.agoldoni.reminder.web

import java.io.File
import java.io.IOException
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TC-06/07/08 — il presidio del rischio R1: **il certificato nasce una volta sola**.
 *
 * È il test più importante di questa feature, e prova qualcosa che sul dispositivo non si nota:
 * un certificato rigenerato non rompe niente, rimette solo l'utente davanti all'avviso del
 * browser — e chi verifica accetta l'avviso per riflesso, quindi non se ne accorge. Qui invece si
 * confrontano i byte.
 */
class CertificateStoreTest {

    private val cartella = File.createTempFile("promemoria-tls", "").let {
        it.delete()
        File(it.absolutePath)
    }

    private val indirizzi = { listOf(InetAddress.getByName("192.168.1.42")) }

    @AfterTest
    fun pulisci() {
        cartella.listFiles()?.forEach { it.delete() }
        cartella.delete()
    }

    private fun archivio(adesso: Long = 1_755_000_000_000L) =
        CertificateStore(cartella, now = { adesso })

    @Test
    fun `su cartella vuota genera e scrive i due file`() {
        val identita = archivio().caricaOCrea(indirizzi)

        assertTrue(File(cartella, "chiave.der").isFile, "la chiave deve essere sul disco")
        assertTrue(File(cartella, "certificato.der").isFile)
        assertContains(identita.certificato.subjectX500Principal.name, "Promemoria")
    }

    @Test
    fun `la seconda chiamata sulla stessa istanza non rigenera`() {
        val archivio = archivio()
        val prima = archivio.caricaOCrea(indirizzi)
        val seconda = archivio.caricaOCrea(indirizzi)

        assertTrue(
            prima.certificato.encoded contentEquals seconda.certificato.encoded,
            "stessa istanza: non deve nemmeno rileggere il disco"
        )
    }

    @Test
    fun `un'istanza nuova sulla stessa cartella ritrova lo stesso certificato`() {
        // È il caso reale: il processo dell'app viene ucciso e ricostruito, e l'utente non deve
        // ritrovarsi l'avviso del browser.
        val prima = archivio().caricaOCrea(indirizzi)
        val dopoIlRiavvio = archivio().caricaOCrea(indirizzi)

        assertTrue(prima.certificato.encoded contentEquals dopoIlRiavvio.certificato.encoded)
        assertEquals(prima.impronta, dopoIlRiavvio.impronta)
        assertTrue(
            prima.chiavePrivata.encoded contentEquals dopoIlRiavvio.chiavePrivata.encoded,
            "senza la chiave il certificato non serve a niente"
        )
    }

    @Test
    fun `un indirizzo diverso non fa nascere un certificato nuovo`() {
        // Il telefono cambia rete di continuo. Il subjectAltName non lo verifica nessuno, quindi
        // riemettere sarebbe un avviso regalato all'utente.
        val prima = archivio().caricaOCrea { listOf(InetAddress.getByName("192.168.1.42")) }
        val altraRete = archivio().caricaOCrea { listOf(InetAddress.getByName("10.0.0.7")) }

        assertTrue(prima.certificato.encoded contentEquals altraRete.certificato.encoded)
    }

    @Test
    fun `il tempo che passa non fa nascere un certificato nuovo`() {
        val prima = archivio(adesso = 1_755_000_000_000L).caricaOCrea(indirizzi)
        // Cinque anni dopo: il certificato è ancora valido e comunque nessuno lo verifica.
        val moltoDopo = archivio(adesso = 1_912_000_000_000L).caricaOCrea(indirizzi)

        assertTrue(prima.certificato.encoded contentEquals moltoDopo.certificato.encoded)
    }

    @Test
    fun `gli indirizzi si chiedono solo quando si genera`() {
        var chiamate = 0
        val conteggio = { chiamate++; listOf(InetAddress.getByName("192.168.1.42")) }

        archivio().caricaOCrea(conteggio)
        assertEquals(1, chiamate, "la prima volta serve davvero")

        archivio().caricaOCrea(conteggio)
        assertEquals(1, chiamate, "la seconda si legge da disco: chiederli darebbe l'idea che servano")
    }

    @Test
    fun `il loopback finisce sempre nel certificato`() {
        // Serve alla prova via `adb forward`, che passa da 127.0.0.1: un certificato che vale in
        // prova ma non in campo, o viceversa, non prova niente.
        val identita = archivio().caricaOCrea(indirizzi)
        val ip = identita.certificato.subjectAlternativeNames.orEmpty()
            .filter { it[0] == 7 }
            .map { it[1] as String }

        assertContains(ip, "127.0.0.1")
        assertContains(ip, "192.168.1.42")
    }

    @Test
    fun `un file rotto si rigenera invece di tenere la porta chiusa per sempre`() {
        val prima = archivio().caricaOCrea(indirizzi)
        File(cartella, "chiave.der").writeBytes(byteArrayOf(1, 2, 3))

        val dopo = archivio().caricaOCrea(indirizzi)

        assertFalse(
            prima.certificato.encoded contentEquals dopo.certificato.encoded,
            "byte irrecuperabili: l'unica uscita è rigenerare"
        )
        // E il nuovo dev'essere di nuovo persistito, altrimenti si rigenererebbe a ogni avvio.
        val dopoAncora = archivio().caricaOCrea(indirizzi)
        assertTrue(dopo.certificato.encoded contentEquals dopoAncora.certificato.encoded)
    }

    @Test
    fun `i file non sono leggibili da altri`() {
        archivio().caricaOCrea(indirizzi)
        val chiave = File(cartella, "chiave.der")

        // Su Android `filesDir` è già privata; qui gira su desktop, dove decide la umask.
        assertTrue(chiave.canRead(), "il proprietario deve poterla leggere")
        val permessi = java.nio.file.Files.getPosixFilePermissions(chiave.toPath())
        assertTrue(
            permessi.none { it.name.startsWith("GROUP") || it.name.startsWith("OTHERS") },
            "la chiave privata non deve essere leggibile da altri: $permessi"
        )
    }

    @Test
    fun `una cartella impossibile da creare diventa un errore, non un certificato effimero`() {
        // Generare in memoria senza poter salvare farebbe ricomparire l'avviso a ogni avvio,
        // silenziosamente. Meglio un guasto che `WebService` trasforma in un messaggio.
        val impossibile = File(cartella, "file-non-cartella")
        cartella.mkdirs()
        impossibile.writeText("sono un file")

        assertFailsWith<IOException> {
            CertificateStore(File(impossibile, "sotto")).caricaOCrea(indirizzi)
        }
    }
}

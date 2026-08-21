package it.agoldoni.reminder.web

import java.net.InetAddress
import java.security.MessageDigest
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TC-03/04/05 — il certificato letto con gli occhi della piattaforma, non con i propri.
 *
 * Nessuna di queste asserzioni confronta byte attesi: confrontare l'output del proprio
 * codificatore con byte prodotti da quello stesso codificatore congelerebbe gli errori invece di
 * trovarli. Qui il certificato viene **riletto da `CertificateFactory`** e interrogato attraverso
 * l'API standard: se una struttura è storta, o non si rilegge affatto, o risponde una cosa diversa
 * da quella che ci si è scritti dentro.
 *
 * La prova che conta davvero resta l'handshake di `TlsHandshakeTest`.
 */
class SelfSignedCertificateTest {

    private val adesso = 1_755_000_000_000L // 2025-08-12T12:00:00Z
    private val indirizzi = listOf(
        InetAddress.getByName("127.0.0.1"),
        InetAddress.getByName("192.168.1.42")
    )

    private fun genera(giorni: Int = 3650) =
        SelfSignedCertificate.genera("Promemoria", indirizzi, adesso, giorni)

    @Test
    fun `si rilegge, si verifica con la propria chiave ed è un v3`() {
        val identita = genera()
        val certificato = identita.certificato

        // Se la firma non tornasse, o la struttura fosse storta, `verify` solleverebbe.
        certificato.verify(certificato.publicKey)
        assertEquals(3, certificato.version, "servono le estensioni, quindi serve la v3")
        assertEquals(
            certificato.subjectX500Principal,
            certificato.issuerX500Principal,
            "autofirmato: emittente e soggetto coincidono"
        )
        assertContains(certificato.subjectX500Principal.name, "Promemoria")
    }

    @Test
    fun `l'algoritmo di firma è ECDSA con SHA-256 e non porta parametri`() {
        val certificato = genera().certificato
        assertEquals("1.2.840.10045.4.3.2", certificato.sigAlgOID)
        // Il punto vero: per ECDSA la RFC 5758 vuole il campo `parameters` **assente**, non un
        // `NULL` come per RSA. Un `NULL` di troppo produce un certificato che qualche verificatore
        // accetta e qualche altro rifiuta — il difetto che si manifesta solo sul dispositivo altrui.
        assertNull(certificato.sigAlgParams, "i parametri devono essere assenti, non NULL")
    }

    @Test
    fun `la validità parte da ieri e dura quanto richiesto`() {
        val certificato = genera(giorni = 90).certificato
        val giorno = 24L * 60 * 60 * 1000

        certificato.checkValidity(java.util.Date(adesso))
        assertEquals(adesso - giorno, certificato.notBefore.time, "retrodatato di un giorno")
        assertEquals(adesso + 90 * giorno, certificato.notAfter.time)

        // Ed è già valido un istante dopo la generazione, che è il caso reale: l'app accende la
        // porta e il browser si collega subito.
        certificato.checkValidity(java.util.Date(adesso + 1000))
    }

    @Test
    fun `il subjectAltName porta gli indirizzi come indirizzi, non come nomi`() {
        val certificato = genera().certificato
        val alternativi = certificato.subjectAlternativeNames.orEmpty()

        val diTipoIp = alternativi.filter { it[0] == 7 }.map { it[1] as String }
        assertEquals(listOf("127.0.0.1", "192.168.1.42"), diTipoIp)
        assertTrue(
            alternativi.none { it[0] == 2 },
            "nessun nome DNS: l'app mostra sempre un indirizzo numerico"
        )
    }

    @Test
    fun `non è una CA e la chiave serve solo a firmare lo scambio`() {
        val certificato = genera().certificato

        assertEquals(-1, certificato.basicConstraints, "-1 significa: non è una CA")

        val usi = certificato.keyUsage
        assertTrue(usi[0], "digitalSignature")
        assertFalse(usi[2], "keyEncipherment sarebbe una dichiarazione falsa con ECDHE_ECDSA")
        assertFalse(usi[5], "keyCertSign: questo certificato non ne firma altri")

        assertEquals(listOf("1.3.6.1.5.5.7.3.1"), certificato.extendedKeyUsage, "serverAuth")
    }

    @Test
    fun `le estensioni critiche sono quelle che negano una capacità`() {
        val certificato = genera().certificato
        val critiche = certificato.criticalExtensionOIDs.orEmpty()

        assertContains(critiche, "2.5.29.19", "basicConstraints")
        assertContains(critiche, "2.5.29.15", "keyUsage")
        // Il subjectAltName è critico solo quando il soggetto è vuoto, e qui non lo è.
        assertFalse("2.5.29.17" in critiche, "subjectAltName non deve essere critica")
    }

    @Test
    fun `il seriale è positivo e sta in venti byte`() {
        repeat(20) {
            val seriale = genera().certificato.serialNumber
            assertTrue(seriale.signum() > 0, "un seriale negativo è fuori specifica: $seriale")
            assertTrue(seriale.toByteArray().size <= 20, "troppo lungo: ${seriale.toByteArray().size}")
        }
    }

    @Test
    fun `l'impronta è quella del certificato, nel formato che mostrano i browser`() {
        val identita = genera()
        val atteso = MessageDigest.getInstance("SHA-256")
            .digest(identita.certificato.encoded)
            .joinToString(":") { "%02X".format(Locale.ROOT, it) }

        assertEquals(atteso, identita.impronta)
        assertEquals(95, identita.impronta.length, "32 coppie e 31 separatori")
    }

    @Test
    fun `due generazioni non producono lo stesso certificato`() {
        // Non è idempotenza — quella è compito di CertificateStore — ma il contrario: qui si
        // verifica che chiave e seriale siano davvero casuali, così un difetto del generatore non
        // passi inosservato dietro alla cache dell'archivio.
        val uno = genera()
        val due = genera()
        assertFalse(uno.certificato.encoded contentEquals due.certificato.encoded)
        assertFalse(uno.certificato.serialNumber == due.certificato.serialNumber)
    }

    @Test
    fun `si abilita l'intersezione fra ciò che è ammesso e ciò che la piattaforma offre`() {
        // Su API 26 l'elenco si ferma a TLSv1.2: pretendere 1.3 farebbe fallire l'apertura del
        // socket con un errore che non nomina la versione di Android. Verificato sul dispositivo.
        val api26 = arrayOf("TLSv1", "TLSv1.1", "TLSv1.2")
        val api33 = arrayOf("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3")

        assertEquals(listOf("TLSv1.2"), TlsIdentity.protocolliDaAbilitare(api26).toList())
        assertEquals(
            listOf("TLSv1.2", "TLSv1.3"),
            TlsIdentity.protocolliDaAbilitare(api33).toList().sorted()
        )
    }
}

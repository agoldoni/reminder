package it.agoldoni.reminder.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **V-01 della feature 003 — verifica bloccante.**
 *
 * Il server della web app deve presentare un certificato generato dall'app stessa. Sul desktop la
 * catena «archivio chiavi in memoria → `KeyManagerFactory` → `SSLContext` → `SSLServerSocket`»
 * funziona di sicuro, ma i test del progetto girano **solo** lì: su Android il provider è un altro
 * (Conscrypt e BC al posto di SUN/SunJSSE) e il tipo di archivio predefinito è storicamente
 * diverso da quello del JDK. Se un anello di quella catena non reggesse sul dispositivo, l'intera
 * feature non partirebbe — e lo si scoprirebbe dopo aver scritto il codificatore DER.
 *
 * Qui la catena si prova **sul dispositivo**, con un certificato di comodo prodotto fuori (non
 * serve il nostro codificatore per rispondere alla domanda: la domanda riguarda la piattaforma,
 * non il certificato). Chiave e certificato arrivano nella stessa forma in cui li custodirà l'app
 * — PKCS#8 e X.509, entrambi in DER — così anche la ricostruzione è quella vera.
 *
 * Il test copre anche **V-02**: stampa i protocolli che questa versione di Android offre davvero,
 * perché l'elenco da abilitare va calcolato come intersezione con {TLS 1.2, TLS 1.3} e non scritto
 * a mano — su API 26 TLS 1.3 non c'è, e pretenderlo farebbe fallire l'apertura del socket.
 */
@RunWith(AndroidJUnit4::class)
class TlsPiattaformaTest {

    @Test
    fun laCatenaTlsReggeSulDispositivo() {
        val certificato = certificatoDiComodo()
        val chiave = chiaveDiComodo()

        // 1. Archivio chiavi **in memoria**: `load(null, null)` invece di un file. È la forma che
        //    userà l'app, che tiene chiave e certificato su due file DER e non in un PKCS#12.
        val archivio = KeyStore.getInstance("PKCS12")
        archivio.load(null, null)
        archivio.setKeyEntry("promemoria", chiave, CharArray(0), arrayOf(certificato))

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(archivio, CharArray(0))

        val contesto = SSLContext.getInstance("TLS")
        contesto.init(kmf.keyManagers, null, null)

        val server = contesto.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        println("V-01 · archivio: PKCS12 (predefinito: ${KeyStore.getDefaultType()})")
        println("V-01 · KeyManagerFactory: ${KeyManagerFactory.getDefaultAlgorithm()}")
        println("V-02 · protocolli supportati: ${server.supportedProtocols.joinToString()}")
        println("V-02 · protocolli abilitati:  ${server.enabledProtocols.joinToString()}")

        // 2. Un handshake vero. È l'unica prova che dice qualcosa: che le classi esistano non
        //    significa che il provider sappia negoziare con una chiave EC.
        val risposta = server.use { ascolto ->
            val servitore = Thread {
                runCatching {
                    (ascolto.accept() as SSLSocket).use { client ->
                        BufferedReader(InputStreamReader(client.inputStream)).readLine()
                        client.outputStream.write("pong\n".toByteArray())
                        client.outputStream.flush()
                    }
                }
            }
            servitore.start()

            val cliente = contestoDiFiducia(certificato).socketFactory
                .createSocket("127.0.0.1", ascolto.localPort) as SSLSocket
            val letta = cliente.use { socket ->
                socket.soTimeout = 10_000
                socket.outputStream.write("ping\n".toByteArray())
                socket.outputStream.flush()
                println("V-01 · protocollo negoziato: ${socket.session.protocol}")
                println("V-01 · suite negoziata: ${socket.session.cipherSuite}")
                BufferedReader(InputStreamReader(socket.inputStream)).readLine()
            }
            servitore.join(10_000)
            letta
        }

        assertEquals("pong", risposta, "l'handshake non ha prodotto uno scambio utilizzabile")

        val intersezione = server.supportedProtocols.toSet() intersect setOf("TLSv1.2", "TLSv1.3")
        assertTrue(
            "TLSv1.2" in intersezione,
            "senza TLS 1.2 non c'è niente da abilitare: ${server.supportedProtocols.joinToString()}"
        )
    }

    /** Si fida **solo** di quel certificato: è il lato client, e qui non serve altro. */
    private fun contestoDiFiducia(certificato: X509Certificate): SSLContext {
        val archivio = KeyStore.getInstance("PKCS12")
        archivio.load(null, null)
        archivio.setCertificateEntry("promemoria", certificato)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(archivio)
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    }

    private fun certificatoDiComodo(): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(Base64.getDecoder().decode(CERTIFICATO_DER).inputStream())
                as X509Certificate

    private fun chiaveDiComodo() = KeyFactory.getInstance("EC")
        .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(CHIAVE_PKCS8)))

    private companion object {
        /**
         * P-256 autofirmato con `subjectAltName` `IP:127.0.0.1`, prodotto con `openssl` fuori dal
         * progetto: qui interessa la piattaforma, non chi ha scritto i byte. Materiale di prova,
         * non un segreto — la chiave privata di questo certificato non protegge nulla.
         */
        const val CHIAVE_PKCS8 =
            "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgaodq5twWWW/1+jYIdyoal5KdCsMz2HX" +
                "Luzt7SiF6/9OhRANCAAR5al3pBLgHtwk/gLbX7jz/jnRFZ6v5Mj3I88sWKqFDamDan9TikdhOQIsq" +
                "2TFS4ENUtVmv2gOpTBpJNRq9vdnT"

        const val CERTIFICATO_DER =
            "MIIBjzCCATagAwIBAgIUcd87Uibsu3xuBATLjBgRt+Ncq7wwCgYIKoZIzj0EAwIwFTETMBEGA1UEAww" +
                "KUHJvbWVtb3JpYTAeFw0yNjA4MjExNzQxNDdaFw0zNjA4MTgxNzQxNDdaMBUxEzARBgNVBAMMClBy" +
                "b21lbW9yaWEwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAR5al3pBLgHtwk/gLbX7jz/jnRFZ6v5M" +
                "j3I88sWKqFDamDan9TikdhOQIsq2TFS4ENUtVmv2gOpTBpJNRq9vdnTo2QwYjAdBgNVHQ4EFgQUmZ" +
                "eSKFrL6QqX00ujUvasRu5XzJcwHwYDVR0jBBgwFoAUmZeSKFrL6QqX00ujUvasRu5XzJcwDwYDVR0" +
                "TAQH/BAUwAwEB/zAPBgNVHREECDAGhwR/AAABMAoGCCqGSM49BAMCA0cAMEQCIAUgKl0/SMqeKk1D" +
                "vfKA9PiROMZC8C6Ky80WSRRdSu+NAiA0NtBtHM+qzRM/qUP/BxYiGtULqvN1NbU6HJQlrOoSbA=="
    }

    /**
     * **T-13 — la misura che chiude la decisione D-03.**
     *
     * `SezioneWebApp` chiama `enable()` dal thread principale, e da lì si arriva a generare una
     * chiave, firmare un certificato e scrivere due file. Il piano prevedeva di *misurare prima e
     * complicare dopo*: se il costo è nell'ordine delle decine di millisecondi, spostare `enable()`
     * su `Dispatchers.IO` — che vuol dire renderlo asincrono e toccare anche il ViewModel — non si
     * ripaga. Il numero esce nel log, e la soglia sotto è larga apposta: serve a far fallire il
     * test se un giorno qualcosa qui dentro diventasse costoso davvero, non a certificare i
     * millisecondi.
     */
    @Test
    fun quantoCostaLaPrimaAccensione() {
        val cartella = java.io.File.createTempFile("misura-tls", "").let { it.delete(); java.io.File(it.absolutePath) }
        try {
            val indirizzi = { listOf(InetAddress.getByName("192.168.1.42")) }

            val inizioGenerazione = System.nanoTime()
            val identita = CertificateStore(cartella).caricaOCrea(indirizzi)
            val generazione = (System.nanoTime() - inizioGenerazione) / 1_000_000.0

            val inizioRilettura = System.nanoTime()
            CertificateStore(cartella).caricaOCrea(indirizzi)
            val rilettura = (System.nanoTime() - inizioRilettura) / 1_000_000.0

            val inizioSocket = System.nanoTime()
            identita.apriSocket(0).close()
            val socket = (System.nanoTime() - inizioSocket) / 1_000_000.0

            // La riapertura è il caso che si ripete: `resume()` arriva da `MainActivity.onStart()`.
            val inizioRiapertura = System.nanoTime()
            identita.apriSocket(0).close()
            val riapertura = (System.nanoTime() - inizioRiapertura) / 1_000_000.0

            println("T-13 · prima generazione + scrittura: %.1f ms".format(generazione))
            println("T-13 · rilettura da disco (ogni avvio): %.1f ms".format(rilettura))
            println("T-13 · apertura del socket TLS: %.1f ms".format(socket))
            println("T-13 · riapertura del socket (ogni onStart): %.1f ms".format(riapertura))

            assertTrue(
                generazione < 1000,
                "generare il certificato non deve costare quasi un secondo: $generazione ms"
            )
        } finally {
            cartella.listFiles()?.forEach { it.delete() }
            cartella.delete()
        }
    }
}

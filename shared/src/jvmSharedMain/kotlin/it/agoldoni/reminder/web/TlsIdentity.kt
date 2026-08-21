package it.agoldoni.reminder.web

import java.net.ServerSocket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/**
 * L'identità con cui il server si presenta: chiave privata, certificato, e il socket TLS che ne
 * discende.
 *
 * **Perché l'archivio chiavi si chiede per nome.** `KeyStore.getInstance("PKCS12")` e non
 * `getDefaultType()`: su Android il tipo predefinito è **BKS**, verificato su API 26, 29 e 33. Con
 * il valore predefinito la catena si romperebbe solo sul dispositivo, cioè dove i test di questo
 * progetto non arrivano — girano tutti sul target desktop, dove il predefinito è un altro.
 * L'archivio è **in memoria** (`load(null, null)`): chiave e certificato vivono su due file DER,
 * non in un PKCS#12 su disco, così non serve una password che non proteggerebbe nulla.
 */
internal class TlsIdentity(
    val chiavePrivata: PrivateKey,
    val certificato: X509Certificate
) {

    /**
     * Impronta SHA-256 nella forma in cui la mostrano i browser: coppie esadecimali maiuscole
     * separate da due punti. Esiste per essere **confrontata da un essere umano** — è l'unico modo
     * di sapere che dall'altra parte c'è davvero questo telefono e non chi si è messo in mezzo,
     * visto che scavalcando l'avviso si accetta qualunque certificato. È lo stesso argomento del
     * confronto a vista già adottato da `Pairing` per l'associazione fra dispositivi.
     */
    val impronta: String by lazy {
        MessageDigest.getInstance("SHA-256")
            .digest(certificato.encoded)
            .joinToString(":") { "%02X".format(Locale.ROOT, it) }
    }

    /**
     * Apre il socket d'ascolto TLS. È questa la funzione che `WebService` passa a `HttpServer`, il
     * quale resta ignaro di che cosa sia TLS: sa solo di ricevere un `ServerSocket`.
     */
    fun apriSocket(porta: Int): ServerSocket {
        val socket = contesto.serverSocketFactory.createServerSocket(porta) as SSLServerSocket
        // **Va fatto qui e non altrove:** i socket accettati ereditano ciò che è abilitato su
        // quello d'ascolto. Verificato su API 26, 29 e 33 che l'elenco predefinito comprende
        // TLSv1 e TLSv1.1, cioè due protocolli obsoleti accesi senza che nessuno li chieda —
        // restringere non è cosmesi.
        socket.enabledProtocols = protocolliDaAbilitare(socket.supportedProtocols)
        return socket
    }

    /**
     * Costruito **una volta sola**. Non è un'ottimizzazione gratuita: `apriSocket` viene chiamato
     * da `WebService.resume()`, cioè da `MainActivity.onStart()` — a ogni ritorno in primo piano e
     * a ogni rotazione dello schermo, sul thread principale. Misurato sul telefono di prova
     * (API 29), ricostruirlo ogni volta costava una cinquantina di millisecondi buttati.
     *
     * In più un `SSLContext` riusato tiene la propria cache di sessioni TLS, e qui serve davvero:
     * il server non fa keep-alive — una richiesta per connessione — quindi una pagina con sei
     * asset paga sei handshake, che con la ripresa di sessione diventano molto più corti.
     */
    private val contesto: SSLContext by lazy { costruisciContesto() }

    private fun costruisciContesto(): SSLContext {
        val archivio = KeyStore.getInstance("PKCS12")
        archivio.load(null, null)
        archivio.setKeyEntry(ALIAS, chiavePrivata, PASSWORD_VUOTA, arrayOf(certificato))
        val chiavi = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        chiavi.init(archivio, PASSWORD_VUOTA)
        return SSLContext.getInstance("TLS").apply { init(chiavi.keyManagers, null, null) }
    }

    internal companion object {
        private const val ALIAS = "promemoria"
        private val PASSWORD_VUOTA = CharArray(0)

        /** Nient'altro: TLS 1.0 e 1.1 sono ritirati, e 1.3 non c'è prima dell'API 29. */
        private val AMMESSI = setOf("TLSv1.3", "TLSv1.2")

        /**
         * L'**intersezione** con ciò che la piattaforma offre, non un elenco fisso: su API 26 TLS
         * 1.3 non esiste — verificato, l'elenco lì si ferma a `TLSv1.2` — e pretenderlo farebbe
         * fallire l'apertura del socket con un errore che non nomina la versione di Android.
         */
        fun protocolliDaAbilitare(supportati: Array<String>): Array<String> =
            supportati.filter { it in AMMESSI }.toTypedArray()
    }
}

package it.agoldoni.reminder.web

import it.agoldoni.reminder.platform.nowMillis
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/** Dieci anni. Oltre il 2049 `UTCTime` non basterebbe più: vedi [Der.utcTime]. */
private const val VALIDITA_GIORNI = 3650

/**
 * Custodisce chiave e certificato del server, e **garantisce che nascano una volta sola**.
 *
 * **È questo il punto più delicato dell'intera feature 003**, e non per motivi crittografici. Le
 * eccezioni che l'utente concede al browser sono legate al *singolo certificato*: rigenerarlo
 * significa rimetterlo davanti all'avviso. E le occasioni per rigenerarlo per sbaglio sono molte —
 * `WebService.apri()` viene chiamato da `enable()` **e** da `resume()`, e `resume()` arriva da
 * `MainActivity.onStart()`, cioè a ogni rotazione dello schermo. Il difetto sarebbe anche
 * invisibile in prova, perché chi verifica accetta l'avviso per riflesso senza accorgersi che è
 * ricomparso.
 *
 * Per questo la guardia sta **in un punto solo** — [caricaOCrea] — e l'unica condizione che fa
 * nascere un certificato nuovo è che non ce ne sia già uno. Non la scadenza (un certificato che
 * nessuno valida continua a funzionare anche scaduto, e riemetterlo sarebbe un avviso regalato),
 * non il cambio di indirizzo (il `subjectAltName` non viene verificato da nessuno), non lo
 * spegnimento dell'interruttore (che dalla feature 006 non invalida più nemmeno i token: chiude la
 * porta e basta).
 *
 * **Il fratello di questa classe è [ChiaveFirma]**, che custodisce la chiave con cui si firmano i
 * token d'accesso e ha la stessa guardia per la stessa ragione — con la posta più alta: un
 * certificato rigenerato rimette l'utente davanti a un avviso, una chiave di firma rigenerata
 * butta fuori tutti i browser insieme. Un caso in cui le due divergono: un file rotto qui si
 * rigenera e si tira avanti, là no.
 *
 * **Due file DER e non un PKCS#12:** un archivio su file vuole una password, e una password che
 * sta nel sorgente dichiara una protezione che non esiste. Qui la protezione sono i permessi —
 * su Android `filesDir` è già privata all'app — ed è la stessa scelta, dichiarata per quello che
 * è, già fatta per il `sharedSecret` nella tabella `peers`.
 */
internal class CertificateStore(
    private val cartella: File,
    private val commonName: String = "Promemoria",
    private val now: () -> Long = ::nowMillis
) {

    private val fileChiave get() = File(cartella, "chiave.der")
    private val fileCertificato get() = File(cartella, "certificato.der")

    /** Una volta caricata non si rilegge: `apri()` passa di qui a ogni ritorno in primo piano. */
    private var inMemoria: TlsIdentity? = null

    /**
     * L'identità del server, letta da disco o creata se non c'è ancora.
     *
     * [indirizzi] viene interrogato **solo** quando si genera: è il `subjectAltName` di un
     * certificato che nessuno valida, e chiamarlo a ogni avvio darebbe l'impressione che serva.
     */
    fun caricaOCrea(indirizzi: () -> List<InetAddress>): TlsIdentity {
        inMemoria?.let { return it }
        val identita = leggi() ?: crea(indirizzi())
        inMemoria = identita
        return identita
    }

    private fun leggi(): TlsIdentity? {
        if (!fileChiave.isFile || !fileCertificato.isFile) return null
        return runCatching {
            val chiave = KeyFactory.getInstance("EC")
                .generatePrivate(PKCS8EncodedKeySpec(fileChiave.readBytes()))
            val certificato = CertificateFactory.getInstance("X.509")
                .generateCertificate(fileCertificato.inputStream()) as X509Certificate
            TlsIdentity(chiave, certificato)
        }.getOrNull()
        // Un file illeggibile o troncato non è recuperabile: si rigenera. Costa all'utente un
        // avviso in più una volta, mentre insistere su byte rotti terrebbe la porta chiusa per
        // sempre.
    }

    private fun crea(indirizzi: List<InetAddress>): TlsIdentity {
        val identita = SelfSignedCertificate.genera(
            commonName = commonName,
            // Il loopback c'è **sempre**, anche quando il telefono ha un indirizzo di rete: è
            // quello che si usa in prova via `adb forward`, e un certificato che vale in prova ma
            // non in campo — o viceversa — non prova niente.
            indirizzi = (indirizzi + InetAddress.getByName("127.0.0.1")).distinct(),
            adessoMillis = now(),
            validoPerGiorni = VALIDITA_GIORNI
        )
        salva(identita)
        return identita
    }

    private fun salva(identita: TlsIdentity) {
        if (!cartella.isDirectory && !cartella.mkdirs()) {
            throw IOException("non si riesce a creare la cartella ${cartella.absolutePath}")
        }
        soloPerNoi(cartella)
        fileChiave.writeBytes(identita.chiavePrivata.encoded)
        soloPerNoi(fileChiave)
        fileCertificato.writeBytes(identita.certificato.encoded)
    }

}

/**
 * Toglie i permessi a tutti e li ridà solo al proprietario. Su Android è già così — `filesDir`
 * nasce privata — ma il codice gira anche sul target desktop, dove la `umask` decide e può essere
 * permissiva. Il certificato è pubblico per definizione e non ha bisogno di nulla.
 *
 * **Sta fuori da [CertificateStore] perché i segreti in questa cartella sono due**: la chiave TLS e
 * la chiave di firma dei token ([ChiaveFirma]). Copiarne una versione per ciascuno vorrebbe dire
 * avere due posti in cui stringere i permessi, e quindi due posti in cui dimenticarselo.
 */
internal fun soloPerNoi(file: File) {
    file.setReadable(false, false)
    file.setWritable(false, false)
    file.setExecutable(false, false)
    file.setReadable(true, true)
    file.setWritable(true, true)
    if (file.isDirectory) file.setExecutable(true, true)
}

package it.agoldoni.reminder.web

import it.agoldoni.reminder.sync.randomBytes
import java.io.File
import java.io.IOException

/**
 * Trentadue byte: la lunghezza naturale della chiave di HMAC-SHA256. Un file di lunghezza diversa
 * non è una chiave più debole, è un file rotto.
 */
private const val LUNGHEZZA = 32

/**
 * La chiave con cui si firmano i token d'accesso alla web app, e **la sola cosa che può revocarli**.
 *
 * **È il pezzo più delicato di questa feature, e non per motivi crittografici** — è la stessa
 * ragione per cui `CertificateStore` lo è della 003, con la posta più alta. Rigenerare il
 * certificato costa all'utente un avviso del browser; rigenerare *questa* chiave butta fuori **tutti
 * i browser insieme**, e lo fa senza rumore: chi guardava la pagina si ritrova un indirizzo scaduto
 * e non ha modo di sapere perché.
 *
 * E le occasioni per rigenerarla per sbaglio sono le stesse di allora: `WebService.apri()` è
 * chiamata da `enable()` **e** da `resume()`, e `resume()` arriva da `MainActivity.onStart()`, cioè
 * a ogni rotazione dello schermo.
 *
 * Per questo esistono **due sole porte** da cui una chiave nuova può entrare, e sono entrambe qui:
 *
 * 1. [caricaOCrea] quando non c'è ancora nessun file — la prima accensione;
 * 2. [revoca], che è un gesto esplicito dell'utente.
 *
 * Non ce n'è una terza. In particolare **un file illeggibile non ne apre una**: vedi [caricaOCrea].
 *
 * **Un file DER accanto agli altri due, e nessuna password.** Vale la scelta già fatta per la chiave
 * TLS e per il `sharedSecret` nella tabella `peers`: protegge i permessi del file, non una cifratura
 * a riposo, perché una password scritta nel sorgente dichiarerebbe una protezione che non esiste.
 */
internal class ChiaveFirma(
    private val cartella: File,
    /** Sostituibile nei test: una chiave prevedibile rende leggibili gli assert. */
    private val casuale: (Int) -> ByteArray = ::randomBytes
) {

    private val file get() = File(cartella, "firma.key")

    /** Una volta caricata non si rilegge: `apri()` passa di qui a ogni ritorno in primo piano. */
    private var inMemoria: ByteArray? = null

    /**
     * La chiave, letta da disco o creata se non c'è ancora.
     *
     * **Un file che c'è ma non si può usare non viene rigenerato al volo**, ed è la differenza che
     * conta rispetto a `CertificateStore.leggi()`, che in un caso analogo si rigenera e tira
     * avanti. Ricreare questa chiave è una **revoca di tutti gli accessi**, e una revoca di massa
     * non deve poter avvenire in silenzio nel mezzo di una sessione. Quindi:
     *
     * ```
     * illeggibile o di lunghezza sbagliata  →  si cancella il file
     *                                       →  si solleva: la porta non si apre
     *                                       →  al riavvio non c'è più niente di rotto da leggere,
     *                                          e si passa dal percorso normale di creazione
     * ```
     *
     * **Cancellare è ciò che impedisce il ciclo.** Senza, ogni `resume()` — cioè ogni ritorno in
     * primo piano — ritroverebbe lo stesso file rotto e ripeterebbe lo stesso rifiuto per sempre.
     * Con la cancellazione il rifiuto avviene una volta sola, e il gesto che lo risolve è il più
     * piccolo che rende visibile all'utente che i suoi indirizzi sono morti: riaprire l'app.
     */
    fun caricaOCrea(): ByteArray {
        inMemoria?.let { return it }

        if (file.exists()) {
            val letta = runCatching { file.readBytes() }.getOrNull()
            if (letta != null && letta.size == LUNGHEZZA) {
                inMemoria = letta
                return letta
            }
            // Il tentativo di cancellazione può fallire — un file che non si riesce a leggere spesso
            // non si riesce nemmeno a togliere — e in quel caso il messaggio resterà quello giusto
            // comunque: l'utente riavvia, non funziona, e il guasto è del disco, non nostro.
            runCatching { file.delete() }
            throw IOException(
                "La chiave d'accesso era illeggibile ed è stata rimossa. Riavvia l'app per " +
                    "ricrearla: gli indirizzi consegnati finora andranno riconsegnati."
            )
        }

        return scrivi(casuale(LUNGHEZZA))
    }

    /**
     * Una chiave nuova, e con essa la morte di ogni token consegnato finora.
     *
     * È l'unica revoca che esiste: non c'è una lista di token emessi da cui togliere una riga,
     * perché non c'è una lista di token emessi. Trentadue byte diversi e tutto ciò che era stato
     * firmato con i precedenti smette di verificarsi nello stesso istante.
     */
    fun revoca(): ByteArray {
        inMemoria = null
        return scrivi(casuale(LUNGHEZZA))
    }

    private fun scrivi(chiave: ByteArray): ByteArray {
        if (!cartella.isDirectory && !cartella.mkdirs()) {
            throw IOException(
                "Non si riesce a creare ${cartella.absolutePath}: la porta resta chiusa."
            )
        }
        soloPerNoi(cartella)
        // Un guasto qui **non** va degradato a una chiave tenuta solo in memoria: sembrerebbe
        // funzionare e non funzionerebbe, perché al riavvio dell'app l'indirizzo appena consegnato
        // sarebbe già morto. È esattamente il difetto che questa feature esiste per togliere.
        runCatching { file.writeBytes(chiave) }.getOrElse { errore ->
            throw IOException(
                "Non si riesce a scrivere la chiave d'accesso: la porta resta chiusa. " +
                    "(${errore.message ?: "errore di scrittura"})"
            )
        }
        soloPerNoi(file)
        inMemoria = chiave
        return chiave
    }
}

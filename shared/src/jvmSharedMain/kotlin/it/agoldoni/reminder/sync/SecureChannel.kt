package it.agoldoni.reminder.sync

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Chi ha aperto la connessione e chi l'ha ricevuta: decide quale delle due chiavi si usa per inviare. */
enum class ChannelRole { INITIATOR, RESPONDER }

/** Oltre questa soglia il frame si rifiuta senza allocarlo: un mittente ostile non deve poter
 *  far allocare memoria arbitraria dichiarando una lunghezza enorme. */
private const val MAX_FRAME_BYTES = 4 * 1024 * 1024

private const val GCM_TAG_BITS = 128
private const val NONCE_BYTES = 12
private const val KEY_BYTES = 32

/** Messaggi in chiaro, con la sola lunghezza davanti: è la forma dei primi scambi, prima che ci sia una chiave. */
object Frames {
    fun write(output: OutputStream, payload: ByteArray) {
        require(payload.size <= MAX_FRAME_BYTES) { "Frame troppo grande: ${payload.size} byte" }
        DataOutputStream(output).apply {
            writeInt(payload.size)
            write(payload)
            flush()
        }
    }

    fun read(input: InputStream): ByteArray {
        val stream = DataInputStream(input)
        val size = stream.readInt()
        if (size !in 0..MAX_FRAME_BYTES) {
            throw SyncProtocolException("Frame di lunghezza non accettabile: $size byte.")
        }
        return ByteArray(size).also { stream.readFully(it) }
    }
}

/**
 * Il canale su cui viaggia tutto ciò che segue l'associazione. AES-256-GCM con:
 *
 * - **una chiave per direzione**, così i contatori dei due lati non possono generare lo stesso
 *   nonce con la stessa chiave, che in GCM è la falla che azzera ogni garanzia;
 * - **il numero di sequenza come dato autenticato**, così un frame ripetuto o spostato di posto
 *   non si decifra invece di passare inosservato.
 *
 * Le chiavi valgono per una sola sessione: si derivano dal segreto dell'associazione mescolato ai
 * nonce che i due lati si scambiano all'apertura.
 */
class SecureChannel internal constructor(
    private val input: InputStream,
    private val output: OutputStream,
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray
) {

    private var sendSequence = 0L
    private var receiveSequence = 0L

    fun send(payload: ByteArray) {
        val sequence = sendSequence++
        Frames.write(output, cipher(Cipher.ENCRYPT_MODE, sendKey, sequence).doFinal(payload))
    }

    fun receive(): ByteArray {
        val sequence = receiveSequence++
        val frame = try {
            Frames.read(input)
        } catch (end: EOFException) {
            throw SyncProtocolException("La connessione si è chiusa a metà messaggio.", end)
        }
        return try {
            cipher(Cipher.DECRYPT_MODE, receiveKey, sequence).doFinal(frame)
        } catch (error: javax.crypto.AEADBadTagException) {
            // Chiave sbagliata, frame alterato, ripetuto o fuori posto: sono lo stesso errore, e
            // distinguerli servirebbe solo a chi sta provando ad attaccare.
            throw SyncProtocolException("Messaggio non autentico: la sessione va interrotta.", error)
        }
    }

    private fun cipher(mode: Int, key: ByteArray, sequence: Long): Cipher {
        val nonce = ByteArray(NONCE_BYTES)
        for (i in 0 until 8) nonce[NONCE_BYTES - 1 - i] = (sequence ushr (8 * i)).toByte()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAAD(nonce)
        }
    }

    companion object {
        /**
         * Deriva le due chiavi direzionali e apre il canale. [sessionSalt] sono i nonce scambiati
         * all'apertura: è ciò che rende diverse le chiavi di due sessioni fra gli stessi dispositivi.
         */
        fun open(
            input: InputStream,
            output: OutputStream,
            sessionSecret: ByteArray,
            sessionSalt: ByteArray,
            role: ChannelRole
        ): SecureChannel {
            val toResponder = Hkdf.derive(sessionSecret, sessionSalt, INFO_TO_RESPONDER, KEY_BYTES)
            val toInitiator = Hkdf.derive(sessionSecret, sessionSalt, INFO_TO_INITIATOR, KEY_BYTES)
            return when (role) {
                ChannelRole.INITIATOR -> SecureChannel(input, output, toResponder, toInitiator)
                ChannelRole.RESPONDER -> SecureChannel(input, output, toInitiator, toResponder)
            }
        }

        private const val INFO_TO_RESPONDER = "promemoria-canale-v1 iniziatore-risponditore"
        private const val INFO_TO_INITIATOR = "promemoria-canale-v1 risponditore-iniziatore"
    }
}

/** Qualunque cosa vada storta nel dialogo fra due dispositivi, con un messaggio in italiano. */
class SyncProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

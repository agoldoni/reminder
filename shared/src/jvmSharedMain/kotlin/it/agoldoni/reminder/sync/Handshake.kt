package it.agoldoni.reminder.sync

import it.agoldoni.reminder.data.PeerEntity
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.json.Json

/** Come questo dispositivo si presenta nel dialogo. */
data class LocalIdentity(val deviceId: String, val displayName: String)

/** Chi si è trovato dall'altra parte, prima che ci sia un'associazione. */
data class PeerIdentity(val deviceId: String, val displayName: String)

/**
 * Chiede all'utente di confrontare il codice. Riceve il codice a sei cifre e chi dice di essere
 * l'altro; risponde `true` solo se l'utente ha visto **lo stesso** codice sull'altro schermo.
 */
typealias PairingApproval = suspend (code: String, peer: PeerIdentity) -> Boolean

sealed interface PairingOutcome {
    data class Paired(val peer: PeerEntity) : PairingOutcome

    /** Nessun segreto è stato salvato; [reason] è già in italiano e mostrabile. */
    data class Refused(val reason: String) : PairingOutcome
}

internal val protocolJson = Json { classDiscriminator = "type"; encodeDefaults = true }

internal fun OutputStream.sendMessage(message: SyncMessage) =
    Frames.write(this, protocolJson.encodeToString(message).encodeToByteArray())

internal fun InputStream.receiveMessage(): SyncMessage =
    try {
        protocolJson.decodeFromString<SyncMessage>(Frames.read(this).decodeToString())
    } catch (error: Exception) {
        if (error is SyncProtocolException) throw error
        throw SyncProtocolException("Messaggio non riconosciuto dall'altro dispositivo.", error)
    }

/**
 * Un rifiuto dell'altro lato non è un errore: è uno degli esiti previsti — l'utente che non
 * conferma, un dispositivo non associato — e va restituito come tale a chi ha chiesto
 * l'associazione, non fatto risalire come guasto.
 */
internal class RemoteRefusalException(val reason: String) : Exception(reason)

internal inline fun <reified T : SyncMessage> SyncMessage.expect(): T = when (this) {
    is T -> this
    is Rejected -> throw RemoteRefusalException(reason)
    else -> throw SyncProtocolException("Sequenza inattesa nel dialogo con l'altro dispositivo.")
}

/**
 * Associazione fra due dispositivi.
 *
 * Il modello è quello del **confronto a vista**: i due lati ricavano dallo scambio uno stesso
 * codice a sei cifre e lo mostrano entrambi; l'utente conferma di vedere lo stesso numero sui due
 * schermi. Chi si mettesse in mezzo negozierebbe due scambi distinti e i due codici non
 * coinciderebbero, e ha una probabilità su un milione di indovinare.
 *
 * L'alternativa — un codice mostrato da un lato e digitato sull'altro — sembra equivalente ma non
 * lo è: chi è in mezzo cattura lo scambio e prova offline tutti i milione di codici in
 * millisecondi. Renderla sicura richiederebbe un PAKE vero, molto più codice crittografico di
 * quanto ne meriti un'app personale.
 *
 * Le chiavi dello scambio sono effimere: quello che sopravvive è solo il segreto derivato.
 */
object Pairing {

    suspend fun initiate(
        input: InputStream,
        output: OutputStream,
        identity: LocalIdentity,
        approval: PairingApproval,
        nowMillis: Long
    ): PairingOutcome = exchange(
        input = input,
        output = output,
        identity = identity,
        approval = approval,
        nowMillis = nowMillis,
        role = ChannelRole.INITIATOR
    )

    suspend fun accept(
        input: InputStream,
        output: OutputStream,
        identity: LocalIdentity,
        approval: PairingApproval,
        nowMillis: Long
    ): PairingOutcome = exchange(
        input = input,
        output = output,
        identity = identity,
        approval = approval,
        nowMillis = nowMillis,
        role = ChannelRole.RESPONDER
    )

    private suspend fun exchange(
        input: InputStream,
        output: OutputStream,
        identity: LocalIdentity,
        approval: PairingApproval,
        nowMillis: Long,
        role: ChannelRole
    ): PairingOutcome = try {
        negotiate(input, output, identity, approval, nowMillis, role)
    } catch (refusal: RemoteRefusalException) {
        PairingOutcome.Refused(refusal.reason)
    }

    private suspend fun negotiate(
        input: InputStream,
        output: OutputStream,
        identity: LocalIdentity,
        approval: PairingApproval,
        nowMillis: Long,
        role: ChannelRole
    ): PairingOutcome {
        val peer = greet(input, output, identity, role) ?: return PairingOutcome.Refused(VERSIONE_INCOMPATIBILE)

        val mine = EphemeralKeyPair.generate()
        val theirPublicKey = when (role) {
            ChannelRole.INITIATOR -> {
                output.sendMessage(PairBegin(mine.publicKeyBytes.toHex()))
                input.receiveMessage().expect<PairKey>().publicKey
            }

            ChannelRole.RESPONDER -> {
                val begin = input.receiveMessage().expect<PairBegin>()
                output.sendMessage(PairKey(mine.publicKeyBytes.toHex()))
                begin.publicKey
            }
        }.hexToBytes()

        val ecdh = try {
            mine.sharedSecretWith(theirPublicKey)
        } catch (error: Exception) {
            output.sendMessage(Rejected(CHIAVE_NON_VALIDA))
            return PairingOutcome.Refused(CHIAVE_NON_VALIDA)
        }
        val transcript = canonicalTranscript(mine.publicKeyBytes, theirPublicKey)

        if (!approval(sasCode(ecdh, transcript), peer)) {
            output.sendMessage(Rejected(NON_CONFERMATO))
            return PairingOutcome.Refused("Associazione annullata: il codice non è stato confermato.")
        }

        // Prova incrociata: ciascuno dimostra all'altro di avere lo stesso segreto. Non protegge
        // dall'uomo nel mezzo, che è già escluso dal confronto del codice; serve a non salvare
        // un'associazione fra due lati che hanno derivato segreti diversi.
        val proofSent = macFor(ecdh, transcript, role)
        val proofExpected = macFor(ecdh, transcript, otherRole(role))
        val received = when (role) {
            ChannelRole.INITIATOR -> {
                output.sendMessage(PairConfirm(proofSent.toHex()))
                input.receiveMessage().expect<PairConfirm>().mac
            }

            ChannelRole.RESPONDER -> {
                val theirs = input.receiveMessage().expect<PairConfirm>().mac
                output.sendMessage(PairConfirm(proofSent.toHex()))
                theirs
            }
        }
        if (!constantTimeEquals(received.hexToBytes(), proofExpected)) {
            output.sendMessage(Rejected(PROVA_NON_VALIDA))
            return PairingOutcome.Refused(PROVA_NON_VALIDA)
        }

        if (role == ChannelRole.INITIATOR) {
            output.sendMessage(PairDone)
        } else {
            input.receiveMessage().expect<PairDone>()
        }

        return PairingOutcome.Paired(
            PeerEntity(
                deviceId = peer.deviceId,
                displayName = peer.displayName,
                sharedSecret = sharedSecret(ecdh, transcript).toHex(),
                pairedAt = nowMillis
            )
        )
    }

    private const val CHIAVE_NON_VALIDA =
        "La chiave inviata dall'altro dispositivo non è valida: associazione interrotta."
    private const val NON_CONFERMATO = "L'altro lato non ha confermato il codice."
    private const val PROVA_NON_VALIDA =
        "I due dispositivi non hanno ricavato lo stesso segreto: associazione interrotta."

}

internal const val VERSIONE_INCOMPATIBILE =
    "Versione del protocollo incompatibile fra i due dispositivi. " +
        "Aggiorna l'app sul dispositivo più vecchio."

internal fun messaggioVersione(loro: Int) =
    "Versione del protocollo incompatibile: qui $PROTOCOL_VERSION, sull'altro dispositivo $loro. " +
        "Aggiorna l'app sul dispositivo più vecchio."

/**
 * Scambio delle identità e confronto della versione, prima di qualunque crittografia. È il primo
 * atto di ogni connessione, che porti a un'associazione o a una sincronizzazione.
 */
internal fun greet(
    input: InputStream,
    output: OutputStream,
    identity: LocalIdentity,
    role: ChannelRole
): PeerIdentity? = when (role) {
    ChannelRole.INITIATOR -> {
        output.sendMessage(Hello(PROTOCOL_VERSION, identity.deviceId, identity.displayName))
        val ack = input.receiveMessage().expect<HelloAck>()
        if (ack.protocolVersion != PROTOCOL_VERSION) null
        else PeerIdentity(ack.deviceId, ack.displayName)
    }

    ChannelRole.RESPONDER -> {
        val hello = input.receiveMessage().expect<Hello>()
        if (hello.protocolVersion != PROTOCOL_VERSION) {
            output.sendMessage(Rejected(messaggioVersione(hello.protocolVersion)))
            null
        } else {
            output.sendMessage(HelloAck(PROTOCOL_VERSION, identity.deviceId, identity.displayName))
            PeerIdentity(hello.deviceId, hello.displayName)
        }
    }
}

internal fun otherRole(role: ChannelRole) =
    if (role == ChannelRole.INITIATOR) ChannelRole.RESPONDER else ChannelRole.INITIATOR

/**
 * Ordine indipendente da chi ha iniziato: entrambi i lati devono ricavare lo stesso codice, e
 * l'unico modo è concatenare le due chiavi sempre nello stesso ordine.
 */
internal fun canonicalTranscript(mine: ByteArray, theirs: ByteArray): ByteArray {
    val primo = if (compareBytes(mine, theirs) <= 0) mine else theirs
    val secondo = if (primo === mine) theirs else mine
    return primo + secondo
}

private fun compareBytes(a: ByteArray, b: ByteArray): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val diff = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
        if (diff != 0) return diff
    }
    return a.size - b.size
}

/** Le sei cifre che l'utente confronta sui due schermi. */
internal fun sasCode(ecdh: ByteArray, transcript: ByteArray): String {
    val bytes = Hkdf.derive(ecdh, transcript, "promemoria-codice-v1", 8)
    var value = 0L
    for (b in bytes) value = (value shl 8) or (b.toLong() and 0xff)
    return ((value ushr 1) % 1_000_000).toString().padStart(6, '0')
}

/** Il segreto che sopravvive all'associazione: è questo, non l'ECDH, a finire nel database. */
internal fun sharedSecret(ecdh: ByteArray, transcript: ByteArray): ByteArray =
    Hkdf.derive(ecdh, transcript, "promemoria-associazione-v1", 32)

internal fun macFor(ecdh: ByteArray, transcript: ByteArray, role: ChannelRole): ByteArray {
    val key = Hkdf.derive(ecdh, transcript, "promemoria-prova-v1", 32)
    return Hkdf.hmac(key, role.name.encodeToByteArray() + transcript)
}

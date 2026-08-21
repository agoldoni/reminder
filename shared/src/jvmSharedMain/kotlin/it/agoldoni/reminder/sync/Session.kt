package it.agoldoni.reminder.sync

import it.agoldoni.reminder.data.PeerEntity
import java.io.InputStream
import java.io.OutputStream

sealed interface SessionOutcome {
    /** Da qui in poi si parla solo attraverso [channel]. */
    data class Open(val channel: SecureChannel, val peer: PeerEntity) : SessionOutcome

    data class Refused(val reason: String) : SessionOutcome
}

/**
 * Apertura di una sessione fra dispositivi **già associati**. Nessuno dei due si fida della sola
 * identità dichiarata nell'`HELLO`, che è in chiaro e chiunque potrebbe dichiarare: ciascuno
 * dimostra all'altro di possedere il segreto stabilito durante l'associazione.
 *
 * I nonce scambiati qui non servono solo all'autenticazione: entrano nella derivazione delle
 * chiavi del canale, così due sessioni fra gli stessi dispositivi non riusano mai le stesse chiavi
 * e la registrazione di una sessione non permette di rigiocarne un'altra.
 */
object Session {

    suspend fun initiate(
        input: InputStream,
        output: OutputStream,
        identity: LocalIdentity,
        knownPeer: suspend (deviceId: String) -> PeerEntity?
    ): SessionOutcome = open(input, output, identity, knownPeer, ChannelRole.INITIATOR)

    suspend fun accept(
        input: InputStream,
        output: OutputStream,
        identity: LocalIdentity,
        knownPeer: suspend (deviceId: String) -> PeerEntity?
    ): SessionOutcome = open(input, output, identity, knownPeer, ChannelRole.RESPONDER)

    private suspend fun open(
        input: InputStream,
        output: OutputStream,
        identity: LocalIdentity,
        knownPeer: suspend (deviceId: String) -> PeerEntity?,
        role: ChannelRole
    ): SessionOutcome = try {
        negotiate(input, output, identity, knownPeer, role)
    } catch (refusal: RemoteRefusalException) {
        SessionOutcome.Refused(refusal.reason)
    }

    private suspend fun negotiate(
        input: InputStream,
        output: OutputStream,
        identity: LocalIdentity,
        knownPeer: suspend (deviceId: String) -> PeerEntity?,
        role: ChannelRole
    ): SessionOutcome {
        val declared = greet(input, output, identity, role)
            ?: return SessionOutcome.Refused(VERSIONE_INCOMPATIBILE)

        val peer = knownPeer(declared.deviceId)
        if (peer == null) {
            // Il rifiuto arriva prima dei nonce: a un dispositivo sconosciuto non si dà nemmeno
            // materiale su cui lavorare.
            output.sendMessage(Rejected(NON_ASSOCIATO))
            return SessionOutcome.Refused(NON_ASSOCIATO)
        }
        val secret = peer.sharedSecret.hexToBytes()

        val myNonce = randomBytes(NONCE_BYTES)
        val salt: ByteArray
        when (role) {
            ChannelRole.INITIATOR -> {
                output.sendMessage(SessionBegin(myNonce.toHex()))
                val accept = input.receiveMessage().expect<SessionAccept>()
                salt = myNonce + accept.nonce.hexToBytes()
                if (!constantTimeEquals(
                        accept.mac.hexToBytes(),
                        sessionMac(secret, salt, ChannelRole.RESPONDER)
                    )
                ) {
                    output.sendMessage(Rejected(PROVA_NON_VALIDA))
                    return SessionOutcome.Refused(PROVA_NON_VALIDA)
                }
                output.sendMessage(
                    SessionConfirm(sessionMac(secret, salt, ChannelRole.INITIATOR).toHex())
                )
            }

            ChannelRole.RESPONDER -> {
                val begin = input.receiveMessage().expect<SessionBegin>()
                salt = begin.nonce.hexToBytes() + myNonce
                output.sendMessage(
                    SessionAccept(
                        nonce = myNonce.toHex(),
                        mac = sessionMac(secret, salt, ChannelRole.RESPONDER).toHex()
                    )
                )
                val confirm = input.receiveMessage().expect<SessionConfirm>()
                if (!constantTimeEquals(
                        confirm.mac.hexToBytes(),
                        sessionMac(secret, salt, ChannelRole.INITIATOR)
                    )
                ) {
                    output.sendMessage(Rejected(PROVA_NON_VALIDA))
                    return SessionOutcome.Refused(PROVA_NON_VALIDA)
                }
            }
        }

        return SessionOutcome.Open(
            channel = SecureChannel.open(input, output, secret, salt, role),
            peer = peer.copy(displayName = declared.displayName)
        )
    }

    private fun sessionMac(secret: ByteArray, salt: ByteArray, role: ChannelRole): ByteArray {
        val key = Hkdf.derive(secret, salt, "promemoria-sessione-auth-v1", 32)
        return Hkdf.hmac(key, role.name.encodeToByteArray() + salt)
    }

    internal const val NON_ASSOCIATO =
        "Dispositivo non associato: esegui prima l'associazione dalle impostazioni."
    private const val PROVA_NON_VALIDA =
        "L'altro dispositivo non ha dimostrato di conoscere il segreto dell'associazione."
    private const val NONCE_BYTES = 16
}

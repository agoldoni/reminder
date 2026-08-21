package it.agoldoni.reminder.sync

/** Esito di uno scambio completo con un peer. */
data class SyncResult(
    val received: MergeReport,
    val sent: Int,
    /** Nuovo watermark verso quel peer: l'istante dichiarato dal mittente nel proprio tempo. */
    val watermark: Long
)

/**
 * Lo scambio vero e proprio, sopra un canale già cifrato e autenticato.
 *
 * I due lati si scambiano **prima le domande e poi le risposte**, e non una coppia
 * domanda-risposta per volta. L'ordine sembra un dettaglio ed è invece la sostanza: chi rispondesse
 * dopo aver già applicato il lotto dell'altro gli rimanderebbe indietro i suoi stessi eventi, e il
 * watermark finirebbe per riflettere l'orologio del destinatario invece che quello del mittente —
 * che è esattamente il problema di clock skew che questo protocollo vuole evitare.
 *
 *     iniziatore              risponditore
 *         │── Pull(sinceA) ──────►│
 *         │◄────────── Pull(sinceB)│   entrambi ora sanno cosa mandare, e lo calcolano
 *         │── Push(perB, upTo) ──►│    prima di toccare il proprio database
 *         │◄───── Push(perA, upTo)│
 *         │── Ack(n) ────────────►│
 *         │◄──────────────── Ack(n)│
 *
 * Le scritture e le letture dei due lati sono speculari a ogni passo, quindi lo scambio non si
 * blocca nemmeno con buffer piccoli.
 */
object SyncConversation {

    suspend fun initiate(channel: SecureChannel, engine: SyncEngine, since: Long): SyncResult {
        channel.sendMessage(Pull(since))
        val theirPull = channel.receiveMessage().expect<Pull>()

        val outgoing = engine.changesSince(theirPull.since)
        channel.sendMessage(Push(outgoing.events, outgoing.upTo))
        val incoming = channel.receiveMessage().expect<Push>()

        val report = engine.apply(incoming.events)
        channel.sendMessage(Ack(report.applied))
        channel.receiveMessage().expect<Ack>()

        return SyncResult(report, outgoing.events.size, incoming.upTo)
    }

    suspend fun accept(channel: SecureChannel, engine: SyncEngine, since: Long): SyncResult {
        val theirPull = channel.receiveMessage().expect<Pull>()
        channel.sendMessage(Pull(since))

        // Calcolato adesso, sul database ancora intatto: applicare prima significherebbe
        // rimandare al mittente ciò che si è appena ricevuto da lui.
        val outgoing = engine.changesSince(theirPull.since)
        val incoming = channel.receiveMessage().expect<Push>()
        channel.sendMessage(Push(outgoing.events, outgoing.upTo))

        val report = engine.apply(incoming.events)
        channel.receiveMessage().expect<Ack>()
        channel.sendMessage(Ack(report.applied))

        return SyncResult(report, outgoing.events.size, incoming.upTo)
    }
}

internal fun SecureChannel.sendMessage(message: SyncMessage) =
    send(protocolJson.encodeToString<SyncMessage>(message).encodeToByteArray())

internal fun SecureChannel.receiveMessage(): SyncMessage =
    try {
        protocolJson.decodeFromString<SyncMessage>(receive().decodeToString())
    } catch (error: Exception) {
        if (error is SyncProtocolException) throw error
        throw SyncProtocolException("Messaggio non riconosciuto dall'altro dispositivo.", error)
    }

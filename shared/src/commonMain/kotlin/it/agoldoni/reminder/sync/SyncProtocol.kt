package it.agoldoni.reminder.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Versione del dialogo fra dispositivi. Viaggia nel primo messaggio e viene confrontata prima di
 * qualunque altra cosa: due versioni diverse si rifiutano con un errore esplicito invece di
 * provare a capirsi e corrompere i dati.
 */
const val PROTOCOL_VERSION = 1

@Serializable
sealed interface SyncMessage

/** Primo messaggio di ogni connessione, in chiaro: prima di questo non c'è ancora una chiave. */
@Serializable
@SerialName("hello")
data class Hello(
    val protocolVersion: Int,
    val deviceId: String,
    val displayName: String
) : SyncMessage

@Serializable
@SerialName("hello-ack")
data class HelloAck(
    val protocolVersion: Int,
    val deviceId: String,
    val displayName: String
) : SyncMessage

/** Rifiuto esplicito, con un motivo in italiano da mostrare all'utente. */
@Serializable
@SerialName("rifiuto")
data class Rejected(val reason: String) : SyncMessage

// --- Associazione -----------------------------------------------------------------------------

/** Apre l'associazione con la chiave pubblica effimera di chi ha iniziato. */
@Serializable
@SerialName("pair-begin")
data class PairBegin(val publicKey: String) : SyncMessage

/** Risposta con la chiave pubblica effimera dell'altro lato. */
@Serializable
@SerialName("pair-key")
data class PairKey(val publicKey: String) : SyncMessage

/**
 * Prova di aver ricavato lo stesso segreto. Non è ciò che protegge dall'uomo nel mezzo — quello lo
 * fa il codice confrontato a vista — ma smaschera un disallineamento prima che venga salvato.
 */
@Serializable
@SerialName("pair-confirm")
data class PairConfirm(val mac: String) : SyncMessage

/** Associazione conclusa: da qui in poi il segreto è salvato su entrambi i lati. */
@Serializable
@SerialName("pair-done")
data object PairDone : SyncMessage

// --- Sessione fra dispositivi già associati ---------------------------------------------------
//
// L'identità dei due lati arriva da Hello/HelloAck e non viene ripetuta qui: due dichiarazioni
// della stessa cosa vorrebbero dire decidere quale delle due fa fede.

@Serializable
@SerialName("session-begin")
data class SessionBegin(val nonce: String) : SyncMessage

@Serializable
@SerialName("session-accept")
data class SessionAccept(val nonce: String, val mac: String) : SyncMessage

@Serializable
@SerialName("session-confirm")
data class SessionConfirm(val mac: String) : SyncMessage

// --- Replica -----------------------------------------------------------------------------------
//
// Ogni lato tiene **un solo** watermark per peer: fin dove ha ricevuto da quel peer, espresso nel
// tempo di quel peer. È il lato che riceve a chiedere, e il push è sempre la risposta a un pull.
//
// Questo elimina per costruzione l'effetto che il clock skew avrebbe su un watermark unico
// condiviso: se l'altro dispositivo avesse l'orologio avanti di un'ora, un watermark comune
// salterebbe in avanti e le modifiche locali fatte «prima» non verrebbero più inviate. Chiedendo
// ciascuno con il proprio metro, il problema non si pone.

/** Manda tutto ciò che da te è cambiato da [since] in poi, estremo incluso, tombstone compresi. */
@Serializable
@SerialName("pull")
data class Pull(val since: Long) : SyncMessage

/**
 * Gli eventi richiesti, più l'istante fino al quale il **mittente** garantisce di aver dato tutto.
 * È [upTo] a diventare il watermark di chi riceve, non il massimo `updatedAt` del lotto: nel lotto
 * possono esserci eventi nati altrove, con l'orologio di un altro dispositivo, e prenderli come
 * riferimento farebbe rientrare dalla finestra il problema del clock skew.
 */
@Serializable
@SerialName("push")
data class Push(val events: List<SyncEvent>, val upTo: Long) : SyncMessage

/** Applicati [applied] eventi: il turno può passare all'altro lato. */
@Serializable
@SerialName("ack")
data class Ack(val applied: Int) : SyncMessage

/**
 * Un evento come viaggia sul filo. Deliberatamente **non** è `EventEntity`: l'id è locale e non
 * ha senso altrove, e il formato di scambio non deve cambiare ogni volta che cambia lo schema.
 */
@Serializable
data class SyncEvent(
    val uuid: String,
    val title: String,
    val description: String? = null,
    val dateTimeMillis: Long,
    val advanceMinutes: Int = 0,
    val completed: Boolean = false,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
    val origin: String = ""
)

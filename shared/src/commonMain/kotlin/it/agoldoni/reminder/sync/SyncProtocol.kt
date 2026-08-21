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

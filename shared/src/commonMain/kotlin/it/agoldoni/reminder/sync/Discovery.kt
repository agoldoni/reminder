package it.agoldoni.reminder.sync

import kotlinx.coroutines.flow.StateFlow

/**
 * Tipo di servizio annunciato su mDNS. Le due implementazioni lo completano a modo loro:
 * `NsdManager` vuole `_promemoria-sync._tcp`, jmdns vuole il dominio finale `.local.`.
 */
const val SYNC_SERVICE_TYPE = "_promemoria-sync._tcp"

/**
 * Porta su cui si mette in ascolto chi può farlo. Fissa, così chi deve digitarla a mano quando la
 * ricerca automatica non passa ha un valore da digitare. Sta qui e non nel trasporto perché la
 * schermata di inserimento manuale, che vive in `commonMain`, la propone come predefinita.
 */
const val SYNC_PORT = 47653

/** Attributo TXT con cui ogni annuncio porta la propria identità. */
const val TXT_DEVICE_ID = "deviceId"

/** Come questo dispositivo si presenta sulla rete. */
data class Advertisement(
    val deviceId: String,
    val displayName: String,
    val port: Int
)

/** Da dove viene un dispositivo dell'elenco: trovato da solo o digitato dall'utente. */
enum class PeerSource { MDNS, MANUAL }

/**
 * Un dispositivo raggiungibile. Non dice ancora nulla sull'associazione: è solo un indirizzo
 * a cui provare a parlare.
 */
data class DiscoveredPeer(
    /**
     * Noto solo per i peer annunciati via mDNS. Quelli inseriti a mano si identificano al primo
     * `HELLO`: prima di allora l'unica cosa che si sa di loro è host e porta.
     */
    val deviceId: String? = null,
    val displayName: String,
    val host: String,
    val port: Int,
    val source: PeerSource
)

/** Stato dell'annuncio e della ricerca, per poter spiegare all'utente perché non vede nulla. */
sealed interface DiscoveryStatus {
    data object Stopped : DiscoveryStatus

    /** In ascolto: l'elenco dei peer si popola man mano che arrivano gli annunci. */
    data object Searching : DiscoveryStatus

    /**
     * mDNS non è utilizzabile su questa rete — succede con l'isolamento fra client degli access
     * point, sulle reti ospiti e quando il multicast è filtrato. Il [message] è in italiano e va
     * mostrato accanto all'inserimento manuale di host e porta, che resta l'unica strada.
     */
    data class Unavailable(val message: String) : DiscoveryStatus
}

/**
 * Scoperta dei dispositivi sulla rete locale: `NsdManager` su Android, jmdns su desktop.
 * Annuncio e ricerca partono insieme perché l'associazione può cominciare da entrambi i lati.
 */
interface Discovery {
    val peers: StateFlow<List<DiscoveredPeer>>
    val status: StateFlow<DiscoveryStatus>

    /** Comincia a cercare; con [advertisement] non nullo annuncia anche questo dispositivo. */
    fun start(advertisement: Advertisement? = null)

    fun stop()
}

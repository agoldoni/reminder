package it.agoldoni.reminder.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * L'elenco dei dispositivi come lo vede l'utente: quelli trovati da soli più quelli inseriti a
 * mano quando mDNS non passa. Il fallback manuale non è una modalità separata, è una sorgente in
 * più della stessa lista, così il resto dell'app non deve sapere da dove arriva un indirizzo.
 *
 * I peer manuali non vengono salvati: finché non c'è un'associazione non c'è nulla da conservare,
 * e l'associazione persiste già host e porta del peer.
 */
class PeerDirectory(
    private val discovery: Discovery,
    scope: CoroutineScope
) {

    private val manual = MutableStateFlow<List<DiscoveredPeer>>(emptyList())

    val status: StateFlow<DiscoveryStatus> = discovery.status

    val peers: StateFlow<List<DiscoveredPeer>> =
        combine(discovery.peers, manual) { trovati, digitati ->
            val indirizzi = trovati.map { it.host to it.port }.toSet()
            // Se lo stesso indirizzo si annuncia anche da solo vince l'annuncio: porta il deviceId
            // e un nome leggibile, mentre della riga digitata si conoscono solo host e porta.
            (trovati + digitati.filterNot { (it.host to it.port) in indirizzi })
                .sortedWith(compareBy({ it.source }, { it.displayName.lowercase() }))
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun start(advertisement: Advertisement? = null) = discovery.start(advertisement)

    fun stop() = discovery.stop()

    /**
     * Aggiunge un dispositivo digitato dall'utente. Valida solo la forma: che l'indirizzo risponda
     * davvero si scopre al primo tentativo di connessione, non qui.
     */
    fun addManual(host: String, port: Int): Result<DiscoveredPeer> {
        val indirizzo = host.trim()
        if (indirizzo.isEmpty()) {
            return Result.failure(IllegalArgumentException("Indirizzo mancante."))
        }
        if (indirizzo.any { it.isWhitespace() }) {
            return Result.failure(IllegalArgumentException("L'indirizzo non può contenere spazi."))
        }
        if (port !in 1..65535) {
            return Result.failure(IllegalArgumentException("La porta deve essere fra 1 e 65535."))
        }
        val peer = DiscoveredPeer(
            displayName = "$indirizzo:$port",
            host = indirizzo,
            port = port,
            source = PeerSource.MANUAL
        )
        if (manual.value.any { it.host == peer.host && it.port == peer.port }) {
            return Result.failure(IllegalArgumentException("Questo indirizzo è già nell'elenco."))
        }
        manual.value = manual.value + peer
        return Result.success(peer)
    }

    fun removeManual(peer: DiscoveredPeer) {
        manual.value = manual.value.filterNot { it.host == peer.host && it.port == peer.port }
    }
}

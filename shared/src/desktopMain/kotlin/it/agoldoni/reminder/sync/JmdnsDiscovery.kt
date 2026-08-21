package it.agoldoni.reminder.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Scoperta su desktop. jmdns vuole il tipo completo di dominio (`_promemoria-sync._tcp.local.`)
 * là dove `NsdManager` si accontenta del solo servizio.
 */
private const val JMDNS_SERVICE_TYPE = "$SYNC_SERVICE_TYPE.local."

class JmdnsDiscovery(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : Discovery {

    private val _peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val peers: StateFlow<List<DiscoveredPeer>> = _peers.asStateFlow()

    private val _status = MutableStateFlow<DiscoveryStatus>(DiscoveryStatus.Stopped)
    override val status: StateFlow<DiscoveryStatus> = _status.asStateFlow()

    /** Chiave: il nome qualificato del servizio, che è ciò che jmdns riporta anche in rimozione. */
    private val found = ConcurrentHashMap<String, DiscoveredPeer>()

    private var jmdns: JmDNS? = null
    private var listener: ServiceListener? = null
    private var ownDeviceId: String? = null

    override fun start(advertisement: Advertisement?) {
        if (jmdns != null) return
        ownDeviceId = advertisement?.deviceId
        scope.launch {
            try {
                // Senza indirizzo esplicito jmdns usa InetAddress.getLocalHost(), che su Linux
                // risolve spesso in 127.0.1.1 da /etc/hosts: l'annuncio non uscirebbe dalla macchina.
                val instance = JmDNS.create(siteAddress(), advertisement?.displayName)
                jmdns = instance
                advertisement?.let { instance.registerService(serviceInfo(it)) }
                listener = browseListener().also { instance.addServiceListener(JMDNS_SERVICE_TYPE, it) }
                _status.value = DiscoveryStatus.Searching
            } catch (error: IOException) {
                _status.value = DiscoveryStatus.Unavailable(
                    "Non è stato possibile avviare la ricerca automatica sulla rete " +
                        "(${error.message ?: "errore di rete"}). Inserisci a mano indirizzo e " +
                        "porta dell'altro dispositivo."
                )
            }
        }
    }

    override fun stop() {
        val instance = jmdns ?: return
        jmdns = null
        val current = listener
        listener = null
        scope.launch {
            runCatching {
                current?.let { instance.removeServiceListener(JMDNS_SERVICE_TYPE, it) }
                instance.unregisterAllServices()
                instance.close()
            }
        }
        found.clear()
        _peers.value = emptyList()
        _status.value = DiscoveryStatus.Stopped
    }

    private fun serviceInfo(advertisement: Advertisement): ServiceInfo = ServiceInfo.create(
        JMDNS_SERVICE_TYPE,
        advertisement.displayName,
        advertisement.port,
        0,
        0,
        mapOf(TXT_DEVICE_ID to advertisement.deviceId)
    )

    private fun browseListener() = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            // L'annuncio iniziale porta solo il nome: la richiesta esplicita fa arrivare
            // indirizzo, porta e record TXT tramite serviceResolved.
            jmdns?.requestServiceInfo(event.type, event.name, RESOLVE_TIMEOUT_MILLIS)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            found.remove(event.info?.qualifiedName ?: event.name)
            publish()
        }

        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info ?: return
            val deviceId = info.getPropertyString(TXT_DEVICE_ID)
            // Il proprio annuncio torna indietro come quello degli altri: va scartato.
            if (deviceId != null && deviceId == ownDeviceId) return
            val host = info.inet4Addresses.firstOrNull()?.hostAddress
                ?: info.inetAddresses.firstOrNull()?.hostAddress
                ?: return
            found[info.qualifiedName] = DiscoveredPeer(
                deviceId = deviceId,
                displayName = info.name,
                host = host,
                port = info.port,
                source = PeerSource.MDNS
            )
            publish()
        }
    }

    private fun publish() {
        _peers.value = found.values.toList()
    }

    private companion object {
        const val RESOLVE_TIMEOUT_MILLIS = 3_000L
    }
}

/**
 * Primo indirizzo IPv4 di un'interfaccia attiva e non di loopback: è quello su cui gli altri
 * dispositivi della rete possono davvero raggiungerci.
 */
internal fun siteAddress(): InetAddress =
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLinkLocalAddress }
        ?: InetAddress.getLocalHost()

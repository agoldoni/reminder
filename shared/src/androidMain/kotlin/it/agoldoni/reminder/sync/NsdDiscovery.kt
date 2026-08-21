package it.agoldoni.reminder.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Scoperta su Android. Due dettagli fanno la differenza fra funzionare e non funzionare:
 *
 * 1. **Multicast lock.** Il Wi-Fi di Android scarta i pacchetti multicast non indirizzati al
 *    dispositivo per risparmiare batteria: senza il lock gli annunci mDNS non arrivano proprio.
 * 2. **Una `resolveService` alla volta.** `NsdManager` ne accetta una sola in volo e risponde
 *    `FAILURE_ALREADY_ACTIVE` alle altre, quindi le richieste passano da una coda.
 */
class NsdDiscovery(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : Discovery {

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val multicastLock =
        (appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock(MULTICAST_LOCK_TAG)
            .apply { setReferenceCounted(true) }

    private val _peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val peers: StateFlow<List<DiscoveredPeer>> = _peers.asStateFlow()

    private val _status = MutableStateFlow<DiscoveryStatus>(DiscoveryStatus.Stopped)
    override val status: StateFlow<DiscoveryStatus> = _status.asStateFlow()

    /** Chiave: il nome del servizio, l'unica cosa che `onServiceLost` fornisce. */
    private val found = ConcurrentHashMap<String, DiscoveredPeer>()

    private val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)

    private var ownDeviceId: String? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveJob: kotlinx.coroutines.Job? = null

    override fun start(advertisement: Advertisement?) {
        if (discoveryListener != null) return
        ownDeviceId = advertisement?.deviceId
        multicastLock.acquire()
        advertisement?.let(::register)
        resolveJob = scope.launch { drainResolveQueue() }
        discoveryListener = browseListener().also {
            nsdManager.discoverServices(SYNC_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, it)
        }
    }

    override fun stop() {
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
        discoveryListener = null
        registrationListener = null
        resolveJob?.cancel()
        resolveJob = null
        if (multicastLock.isHeld) multicastLock.release()
        found.clear()
        _peers.value = emptyList()
        _status.value = DiscoveryStatus.Stopped
    }

    private fun register(advertisement: Advertisement) {
        val info = NsdServiceInfo().apply {
            serviceName = advertisement.displayName
            serviceType = SYNC_SERVICE_TYPE
            port = advertisement.port
            setAttribute(TXT_DEVICE_ID, advertisement.deviceId)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                _status.value = unavailable(errorCode)
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun browseListener() = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            _status.value = DiscoveryStatus.Searching
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            _status.value = unavailable(errorCode)
            runCatching { nsdManager.stopServiceDiscovery(this) }
        }

        override fun onServiceFound(info: NsdServiceInfo) {
            resolveQueue.trySend(info)
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            found.remove(info.serviceName)
            publish()
        }

        override fun onDiscoveryStopped(serviceType: String) {
            _status.value = DiscoveryStatus.Stopped
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    /** Una risoluzione per volta: `NsdManager` non ne accetta di parallele. */
    private suspend fun drainResolveQueue() {
        for (info in resolveQueue) {
            val resolved = resolve(info) ?: continue
            val deviceId = resolved.attributes[TXT_DEVICE_ID]?.decodeToString()
            // Il proprio annuncio torna indietro come quello degli altri: va scartato.
            if (deviceId != null && deviceId == ownDeviceId) continue
            val host = resolved.host?.hostAddress ?: continue
            found[resolved.serviceName] = DiscoveredPeer(
                deviceId = deviceId,
                displayName = resolved.serviceName,
                host = host,
                port = resolved.port,
                source = PeerSource.MDNS
            )
            publish()
        }
    }

    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(null))
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(info))
                }
            })
        }

    private fun publish() {
        _peers.value = found.values.toList()
    }

    private fun unavailable(errorCode: Int) = DiscoveryStatus.Unavailable(
        "Questa rete non lascia passare la ricerca automatica (codice $errorCode). " +
            "Inserisci a mano indirizzo e porta dell'altro dispositivo."
    )

    private companion object {
        const val MULTICAST_LOCK_TAG = "promemoria-sync"
    }
}

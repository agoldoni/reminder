package it.agoldoni.reminder.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Rete finta: i peer li decide il test invece che gli annunci mDNS. */
class FakeDiscovery : Discovery {

    private val _peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val peers: StateFlow<List<DiscoveredPeer>> = _peers.asStateFlow()

    private val _status = MutableStateFlow<DiscoveryStatus>(DiscoveryStatus.Stopped)
    override val status: StateFlow<DiscoveryStatus> = _status.asStateFlow()

    var startedWith: Advertisement? = null
        private set
    var running: Boolean = false
        private set

    override fun start(advertisement: Advertisement?) {
        running = true
        startedWith = advertisement
        _status.value = DiscoveryStatus.Searching
    }

    override fun stop() {
        running = false
        _peers.value = emptyList()
        _status.value = DiscoveryStatus.Stopped
    }

    fun emit(vararg peers: DiscoveredPeer) {
        _peers.value = peers.toList()
    }

    fun failWith(message: String) {
        _status.value = DiscoveryStatus.Unavailable(message)
    }
}

fun mdnsPeer(name: String, host: String, port: Int = 8765, deviceId: String = name) =
    DiscoveredPeer(
        deviceId = deviceId,
        displayName = name,
        host = host,
        port = port,
        source = PeerSource.MDNS
    )

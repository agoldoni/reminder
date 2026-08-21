package it.agoldoni.reminder.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Dispositivi associati in memoria, con la stessa semantica delle query di [PeerDao]. */
class FakePeerDao(initial: List<PeerEntity> = emptyList()) : PeerDao {

    private val state = MutableStateFlow(initial)

    override fun getAll(): Flow<List<PeerEntity>> =
        state.map { list -> list.sortedBy { it.displayName } }

    override suspend fun list(): List<PeerEntity> = state.value.sortedBy { it.displayName }

    override suspend fun getById(deviceId: String): PeerEntity? =
        state.value.firstOrNull { it.deviceId == deviceId }

    override suspend fun upsert(peer: PeerEntity) {
        state.value = state.value.filterNot { it.deviceId == peer.deviceId } + peer
    }

    override suspend fun delete(deviceId: String) {
        state.value = state.value.filterNot { it.deviceId == deviceId }
    }

    override suspend fun rememberAddress(deviceId: String, host: String, port: Int) {
        edit(deviceId) { it.copy(lastHost = host, lastPort = port) }
    }

    override suspend fun rememberSync(deviceId: String, syncedAt: Long) {
        edit(deviceId) { it.copy(lastSyncAt = syncedAt) }
    }

    private fun edit(deviceId: String, transform: (PeerEntity) -> PeerEntity) {
        state.value = state.value.map { if (it.deviceId == deviceId) transform(it) else it }
    }
}

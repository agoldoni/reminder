package it.agoldoni.reminder.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.agoldoni.reminder.sync.DiscoveredPeer
import it.agoldoni.reminder.sync.DiscoveryStatus
import it.agoldoni.reminder.sync.PairedPeer
import it.agoldoni.reminder.sync.PairingResult
import it.agoldoni.reminder.sync.SyncController
import it.agoldoni.reminder.sync.SyncStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * L'associazione in corso, così come la vede l'utente. Il codice **si confronta**, non si digita:
 * compare uguale sui due dispositivi e l'utente conferma su entrambi. Un campo in cui scriverlo
 * sembrerebbe più naturale ma renderebbe l'associazione forzabile da chi si mette in mezzo — il
 * perché è in `Pairing`.
 */
data class PairingPrompt(
    val code: String,
    val peerName: String,
    /** Chi ha cominciato: cambia solo il testo, non quello che l'utente deve fare. */
    val incoming: Boolean
)

class SyncViewModel(private val controller: SyncController) : ViewModel() {

    val status: StateFlow<SyncStatus> = controller.status
    val discovered: StateFlow<List<DiscoveredPeer>> = controller.discovered
    val discoveryStatus: StateFlow<DiscoveryStatus> = controller.discoveryStatus
    val paired: StateFlow<List<PairedPeer>> = controller.paired

    private val _prompt = MutableStateFlow<PairingPrompt?>(null)
    val prompt: StateFlow<PairingPrompt?> = _prompt.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** La risposta dell'utente al confronto del codice, che l'associazione sta aspettando. */
    private var pending: CompletableDeferred<Boolean>? = null

    /** Aprire la schermata accende ricerca e ascolto; chiuderla li spegne se non si è associato nulla. */
    fun apriSchermata() {
        controller.beginInteractive()
        ascoltaAssociazioniInArrivo(true)
    }

    fun chiudiSchermata() {
        ascoltaAssociazioniInArrivo(false)
        controller.endInteractive()
    }

    fun sincronizzaOra() {
        viewModelScope.launch { controller.syncNow() }
    }

    fun associa(peer: DiscoveredPeer) {
        viewModelScope.launch {
            val esito = controller.pair(peer) { code, peerName ->
                chiediConferma(PairingPrompt(code, peerName, incoming = false))
            }
            _message.value = when (esito) {
                is PairingResult.Paired -> "${esito.peer.displayName} associato."
                is PairingResult.Refused -> esito.reason
            }
        }
    }

    /**
     * Da chiamare mentre la schermata è visibile: da quel momento un'associazione avviata
     * dall'altro dispositivo può essere mostrata all'utente. Alla chiusura si passa `null` e le
     * associazioni in arrivo tornano a essere negate.
     */
    fun ascoltaAssociazioniInArrivo(attivo: Boolean) {
        controller.onIncomingPairing(
            if (attivo) { code, peerName ->
                chiediConferma(PairingPrompt(code, peerName, incoming = true))
            } else null
        )
    }

    override fun onCleared() {
        controller.onIncomingPairing(null)
        pending?.complete(false)
    }

    private suspend fun chiediConferma(prompt: PairingPrompt): Boolean {
        val attesa = CompletableDeferred<Boolean>()
        pending = attesa
        _prompt.value = prompt
        return try {
            attesa.await()
        } finally {
            _prompt.value = null
            pending = null
        }
    }

    fun rispondiAlConfronto(coincide: Boolean) {
        pending?.complete(coincide)
    }

    fun dissocia(deviceId: String) {
        viewModelScope.launch {
            controller.unpair(deviceId)
            _message.value = "Dispositivo dissociato."
        }
    }

    fun aggiungiManuale(host: String, port: Int) {
        controller.addManualPeer(host, port)
            .onFailure { _message.value = it.message }
    }

    fun disattiva() = controller.disable()

    fun consumaMessaggio() {
        _message.value = null
    }
}

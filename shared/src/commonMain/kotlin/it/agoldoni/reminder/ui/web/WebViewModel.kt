package it.agoldoni.reminder.ui.web

import androidx.lifecycle.ViewModel
import it.agoldoni.reminder.web.WebServerController
import it.agoldoni.reminder.web.WebStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * La sezione della web app dentro la schermata Sincronizzazione ha un ViewModel proprio invece di
 * ingrossare `SyncViewModel`: sono due servizi indipendenti che condividono solo lo schermo, e
 * fonderli renderebbe difficile spostarli o spegnerne uno. Due ViewModel in una schermata sono
 * leciti, e questo — come quello della sincronizzazione — non fa che riesporre il controller.
 */
class WebViewModel(private val controller: WebServerController) : ViewModel() {

    val status: StateFlow<WebStatus> = controller.status

    /** Se questa piattaforma ha una web app da accendere: su desktop non è cablata. */
    val supported: Boolean get() = controller.supported

    fun accendi() = controller.enable()

    fun spegni() = controller.disable()

    /**
     * Butta via la chiave di firma: ogni indirizzo consegnato finora smette di funzionare.
     *
     * È un gesto separato da [spegni] e va tenuto separato: l'interruttore chiude la porta, questo
     * cambia le serrature. Confonderli è precisamente l'errore che l'utente commetteva prima,
     * quando spegnere era l'unico modo di togliere un accesso — e toglieva l'accesso a tutti.
     */
    fun revoca() = controller.revoke()
}

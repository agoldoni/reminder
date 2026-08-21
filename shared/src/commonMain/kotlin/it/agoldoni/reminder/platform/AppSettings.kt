package it.agoldoni.reminder.platform

import kotlinx.coroutines.flow.StateFlow

/**
 * Preferenze che sopravvivono alla chiusura dell'app. Ogni piattaforma le tiene a modo suo:
 * `SharedPreferences` su Android, un file di properties accanto al database su desktop.
 */
interface AppSettings {
    /**
     * Interruttore della sincronizzazione, **spento di default**. Finché è spento non si apre
     * nessun socket e non parte nessun annuncio sulla rete: l'app si comporta come se la
     * sincronizzazione non esistesse. Si accende quando l'utente avvia la prima associazione, e
     * spegnerlo è il primo passo del rollback previsto dal piano.
     */
    val syncEnabled: StateFlow<Boolean>

    fun setSyncEnabled(enabled: Boolean)
}

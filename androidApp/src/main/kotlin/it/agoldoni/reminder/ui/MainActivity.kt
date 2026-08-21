package it.agoldoni.reminder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import it.agoldoni.reminder.appContainer
import it.agoldoni.reminder.platform.ReminderRoot

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent {
            ReminderRoot(appContainer)
        }
    }

    /**
     * Il telefono sincronizza qui, al rientro in primo piano, perché è l'unico momento in cui
     * può: Android non lascia tenere un socket in ascolto ad app chiusa. Se la sincronizzazione è
     * spenta o non c'è nessun dispositivo associato, `syncNow()` non fa nulla.
     *
     * Qui la web app riapre la sua porta, se l'interruttore è acceso e il processo è stato
     * ricreato. **Non c'è un `onStop` corrispondente**: la porta resta aperta ad app chiusa, ed è
     * il servizio in primo piano a tenere vivo il processo. Questo è anche l'unico momento in cui
     * quel servizio si può avviare: dall'API 31 il sistema rifiuta di farlo partire da un'app che
     * non è davanti.
     */
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { appContainer.sync.syncNow() }
        appContainer.web.resume()
    }

    /** Il permesso serve a tutta l'app, non alla sola schermata di modifica: si chiede all'avvio. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

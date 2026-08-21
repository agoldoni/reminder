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
     * Per la stessa ragione la web app apre qui la sua porta. A interruttore spento non fa nulla.
     */
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { appContainer.sync.syncNow() }
        appContainer.web.onForeground()
    }

    /**
     * Uscendo dal primo piano la porta si chiude, ma **l'interruttore e il token restano**: questo
     * scatta anche a ogni cambio di configurazione — la rotazione dello schermo, prima di tutte —
     * e rigenerare il token qui vorrebbe dire invalidare a ogni rotazione l'indirizzo che l'utente
     * ha appena digitato sull'altro dispositivo.
     */
    override fun onStop() {
        super.onStop()
        appContainer.web.onBackground()
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

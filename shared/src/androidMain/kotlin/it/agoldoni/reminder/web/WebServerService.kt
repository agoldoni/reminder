package it.agoldoni.reminder.web

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import it.agoldoni.reminder.platform.AndroidAppContainer
import it.agoldoni.reminder.shared.R

/**
 * Tiene vivo il processo mentre la porta della web app è aperta.
 *
 * Senza, il socket resterebbe formalmente aperto ma inutile: un processo in background viene
 * congelato o ucciso e Doze taglia la rete, quindi la pagina smetterebbe di rispondere appena il
 * telefono torna in tasca — che è esattamente il momento in cui serve.
 *
 * La notifica permanente è il prezzo, e insieme il beneficio: è il segnale sempre visibile che una
 * porta è aperta su questo dispositivo, in linea con l'interruttore spento di default. Per la
 * stessa ragione porta un'azione per spegnere senza dover aprire l'app.
 *
 * **`START_NOT_STICKY` di proposito.** Se il sistema uccide il processo, il servizio non deve
 * tornare da solo: il token vive in memoria e ricomincerebbe diverso, quindi la notifica
 * dichiarerebbe raggiungibile un indirizzo che nessuno conosce. Si riparte quando l'utente riapre
 * l'app, che è anche il solo posto dove può leggere il nuovo indirizzo.
 */
class WebServerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == AZIONE_SPEGNI) {
            // Spegne davvero l'interruttore, non solo il servizio: chiudere il servizio lasciando
            // acceso il flag farebbe riaprire la porta alla prossima apertura dell'app.
            AndroidAppContainer.instance.web.disable()
            return START_NOT_STICKY
        }
        avviaInPrimoPiano()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun avviaInPrimoPiano() {
        creaCanale()
        ServiceCompat.startForeground(
            this,
            NOTIFICA_ID,
            notifica(),
            // Il tipo è obbligatorio dall'API 34 e deve combaciare con quello del manifest.
            // `specialUse` e non `dataSync` perché da Android 15 quest'ultimo ha un tetto di sei
            // ore al giorno, che qui vorrebbe dire una porta che si chiude da sola a metà giornata.
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    private fun creaCanale() {
        val canale = NotificationChannel(
            CANALE_ID,
            "Consultazione dal browser",
            // Bassa: è un promemoria di stato, non un allarme. Con IMPORTANCE_DEFAULT suonerebbe.
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Segnala che la porta per consultare i promemoria dal browser è aperta"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(canale)
    }

    private fun notifica(): android.app.Notification {
        // Si punta all'app senza nominarne l'Activity: quella vive nel modulo applicativo, questo
        // codice no, e il launcher intent evita la dipendenza all'indietro.
        val apriApp = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val spegni = PendingIntent.getService(
            this, 1,
            Intent(this, WebServerService::class.java).setAction(AZIONE_SPEGNI),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CANALE_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Promemoria consultabile dal browser")
            // Nessun indirizzo qui dentro: il token finirebbe sulla schermata di blocco.
            .setContentText("La porta è aperta su questa rete. Tocca per vedere l'indirizzo.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .apply { apriApp?.let { setContentIntent(it) } }
            .addAction(0, "Spegni", spegni)
            .build()
    }

    private companion object {
        const val CANALE_ID = "web-app"
        const val NOTIFICA_ID = 0x7EB
        const val AZIONE_SPEGNI = "it.agoldoni.reminder.web.SPEGNI"
    }
}

/**
 * Il custode su Android: accende e spegne [WebServerService].
 *
 * `startForegroundService` da app non in primo piano viene **rifiutato** dal sistema dall'API 31 in
 * poi. Non si tenta di aggirarlo: l'eccezione risale a chi ha chiesto di ingaggiare il custode, che
 * la trasforma in un messaggio per l'utente.
 */
class ForegroundServiceKeeper(private val context: Context) : ProcessKeeper {

    override fun keepAlive(active: Boolean) {
        val intent = Intent(context.applicationContext, WebServerService::class.java)
        if (active) {
            ContextCompat.startForegroundService(context.applicationContext, intent)
        } else {
            context.applicationContext.stopService(intent)
        }
    }
}

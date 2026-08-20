package it.agoldoni.reminder.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import it.agoldoni.reminder.platform.AndroidAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Il DAO viene dal container: evita di aprire un secondo database senza migrazioni
        val dao = AndroidAppContainer.instance.eventDao

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                dao.getFutureEvents(System.currentTimeMillis())
                    .forEach { AlarmScheduler.schedule(context, it) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

package it.agoldoni.reminder.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import it.agoldoni.reminder.data.EventDao
import it.agoldoni.reminder.platform.AlarmScheduler
import it.agoldoni.reminder.platform.AndroidAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // DAO e scheduler vengono dal container: niente secondo database senza migrazioni
        val container = AndroidAppContainer.instance
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                rescheduleFutureAlarms(container.eventDao, container.alarmScheduler)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/**
 * Riprogramma gli allarmi degli eventi ancora futuri. Vive fuori dal receiver perché
 * `goAsync()` funziona solo dentro un vero dispatch di broadcast: così la logica resta testabile.
 */
internal suspend fun rescheduleFutureAlarms(dao: EventDao, scheduler: AlarmScheduler) {
    dao.getFutureEvents(System.currentTimeMillis()).forEach(scheduler::schedule)
}

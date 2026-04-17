package it.agoldoni.reminder.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.room.Room
import it.agoldoni.reminder.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "reminder.db"
                ).build()
                val events = db.eventDao().getFutureEvents(System.currentTimeMillis())
                events.forEach { AlarmScheduler.schedule(context, it) }
                db.close()
            } finally {
                pendingResult.finish()
            }
        }
    }
}

package it.agoldoni.reminder.alarm

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra("notificationId", 0)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(notificationId)

        if (intent.action == "ACTION_SNOOZE") {
            val eventId = intent.getLongExtra("eventId", 0)
            val title = intent.getStringExtra("title") ?: "Promemoria"
            val description = intent.getStringExtra("description")
            val snoozeMinutes = intent.getIntExtra("snoozeMinutes", 5)
            AlarmScheduler.scheduleSnooze(context, eventId, title, description, snoozeMinutes)
        }
    }
}

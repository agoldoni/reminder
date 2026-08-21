package it.agoldoni.reminder.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getLongExtra("eventId", 0)
        val title = intent.getStringExtra("title") ?: "Promemoria"
        val description = intent.getStringExtra("description")
        NotificationHelper.show(context, eventId, title, description)
    }
}

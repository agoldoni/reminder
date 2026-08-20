package it.agoldoni.reminder.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import it.agoldoni.reminder.data.EventEntity

object AlarmScheduler {

    fun schedule(context: Context, event: EventEntity) {
        val triggerAt = event.dateTimeMillis - event.advanceMinutes * 60_000L
        if (triggerAt <= System.currentTimeMillis()) return

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("eventId", event.id)
            putExtra("title", event.title)
            putExtra("description", event.description)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            RequestCodes.alarm(event.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun cancel(context: Context, eventId: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, AlarmReceiver::class.java)
        // Allarme principale e snooze pendenti, inclusi i codici precedenti agli slot:
        // senza gli snooze, completare o eliminare un evento rinviato lo lasciava scattare
        RequestCodes.allAlarms(eventId).forEach { requestCode ->
            PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )?.let { pending ->
                alarmManager.cancel(pending)
                pending.cancel()
            }
        }
    }

    fun scheduleSnooze(context: Context, eventId: Long, title: String, description: String?, snoozeMinutes: Int) {
        val triggerAt = System.currentTimeMillis() + snoozeMinutes * 60_000L
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("eventId", eventId)
            putExtra("title", title)
            putExtra("description", description)
        }
        val requestCode = RequestCodes.snooze(eventId, snoozeMinutes)
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }
}

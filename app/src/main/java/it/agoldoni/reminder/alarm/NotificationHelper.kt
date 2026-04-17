package it.agoldoni.reminder.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import it.agoldoni.reminder.R

object NotificationHelper {

    private const val CHANNEL_ID = "reminders"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Promemoria",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifiche per gli eventi"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(context: Context, eventId: Long, title: String, description: String?) {
        createChannel(context)
        val notificationId = eventId.toInt()

        val dismissIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = "ACTION_DISMISS"
            putExtra("notificationId", notificationId)
        }
        val dismissPending = PendingIntent.getBroadcast(
            context, notificationId * 10 + 1, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snooze5Intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = "ACTION_SNOOZE"
            putExtra("notificationId", notificationId)
            putExtra("eventId", eventId)
            putExtra("title", title)
            putExtra("description", description)
            putExtra("snoozeMinutes", 5)
        }
        val snooze5Pending = PendingIntent.getBroadcast(
            context, notificationId * 10 + 2, snooze5Intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snooze60Intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = "ACTION_SNOOZE"
            putExtra("notificationId", notificationId)
            putExtra("eventId", eventId)
            putExtra("title", title)
            putExtra("description", description)
            putExtra("snoozeMinutes", 60)
        }
        val snooze60Pending = PendingIntent.getBroadcast(
            context, notificationId * 10 + 3, snooze60Intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(description ?: "Promemoria evento")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(0, "Chiudi", dismissPending)
            .addAction(0, "+5 min", snooze5Pending)
            .addAction(0, "+1 ora", snooze60Pending)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId, notification)
    }
}

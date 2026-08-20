package it.agoldoni.reminder.platform

import it.agoldoni.reminder.data.EventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Azioni offerte dalla notifica desktop, allineate a quelle Android. */
enum class NotificationAction { SNOOZE_SHORT, SNOOZE_LONG, COMPLETE }

/**
 * Notifiche di sistema via `notify-send` (libnotify ≥ 0.8, che supporta le azioni con `-A`
 * e stampa su stdout il nome dell'azione scelta). Non c'è un equivalente comune ad Android:
 * lì la notifica nasce dal `BroadcastReceiver`, fuori dal processo dell'app.
 */
class DesktopNotifier(
    private val scope: CoroutineScope,
    private val onAction: suspend (NotificationAction, EventEntity) -> Unit
) {

    fun show(event: EventEntity, overdue: Boolean = false) {
        scope.launch {
            val action = withContext(Dispatchers.IO) { runNotifySend(event, overdue) } ?: return@launch
            onAction(action, event)
        }
    }

    private fun runNotifySend(event: EventEntity, overdue: Boolean): NotificationAction? {
        val title = if (overdue) "Scaduto: ${event.title}" else event.title
        val body = buildString {
            append(event.description ?: "Promemoria evento")
            append("\n")
            append(formatDateTime(event.dateTimeMillis))
        }

        val command = listOf(
            "notify-send",
            "--app-name", APP_NAME,
            "--urgency", "critical",
            "--action", "${ACTION_SNOOZE_SHORT}=+5 min",
            "--action", "${ACTION_SNOOZE_LONG}=+1 ora",
            "--action", "${ACTION_COMPLETE}=Completa",
            title,
            body
        )

        val chosen = runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()
            output
        }.getOrElse { error ->
            System.err.println("[Promemoria] notifica non mostrata: ${error.message}")
            return null
        }

        return when (chosen) {
            ACTION_SNOOZE_SHORT -> NotificationAction.SNOOZE_SHORT
            ACTION_SNOOZE_LONG -> NotificationAction.SNOOZE_LONG
            ACTION_COMPLETE -> NotificationAction.COMPLETE
            else -> null // notifica scaduta o chiusa senza scegliere
        }
    }

    private companion object {
        const val APP_NAME = "Promemoria"
        const val ACTION_SNOOZE_SHORT = "snooze5"
        const val ACTION_SNOOZE_LONG = "snooze60"
        const val ACTION_COMPLETE = "done"
    }
}

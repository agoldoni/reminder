package it.agoldoni.reminder.export

import it.agoldoni.reminder.data.EventEntity

/**
 * Serializza gli eventi in un formato di file. Il risultato è un [ByteArray] e non uno stream
 * perché il contratto vive in `commonMain`: gli export sono di pochi KB.
 */
interface Exporter {
    val mimeType: String
    val fileExtension: String
    suspend fun export(events: List<EventEntity>): ByteArray
}

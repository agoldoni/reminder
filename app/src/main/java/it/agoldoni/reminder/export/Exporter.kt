package it.agoldoni.reminder.export

import it.agoldoni.reminder.data.EventEntity
import java.io.OutputStream

interface Exporter {
    val mimeType: String
    val fileExtension: String
    suspend fun export(events: List<EventEntity>, output: OutputStream)
}

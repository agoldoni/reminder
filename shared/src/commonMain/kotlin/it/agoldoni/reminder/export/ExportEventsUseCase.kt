package it.agoldoni.reminder.export

import it.agoldoni.reminder.data.EventDao
import it.agoldoni.reminder.platform.formatFileDate
import it.agoldoni.reminder.platform.nowMillis

class ExportEventsUseCase(
    private val dao: EventDao,
    private val exporter: Exporter,
    private val target: ExportTarget
) {

    suspend fun execute(filter: ExportFilter): Result<Unit> {
        val events = when (filter) {
            ExportFilter.ALL -> dao.getAll()
            ExportFilter.OPEN_ONLY -> dao.getAllOpen()
        }

        if (events.isEmpty()) return Result.failure(EmptyExportException())

        return runCatching {
            val bytes = exporter.export(events)
            target.deliver(buildFileName(), exporter.mimeType, bytes)
        }
    }

    private fun buildFileName(): String =
        "promemoria_${formatFileDate(nowMillis())}.${exporter.fileExtension}"
}

class EmptyExportException : Exception()

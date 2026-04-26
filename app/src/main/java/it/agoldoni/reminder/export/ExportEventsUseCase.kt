package it.agoldoni.reminder.export

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import it.agoldoni.reminder.data.EventDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

class ExportEventsUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: EventDao,
    private val exporter: Exporter
) {

    suspend fun execute(filter: ExportFilter): Result<Uri> = withContext(Dispatchers.IO) {
        val events = when (filter) {
            ExportFilter.ALL -> dao.getAll()
            ExportFilter.OPEN_ONLY -> dao.getAllOpen()
        }

        if (events.isEmpty()) {
            return@withContext Result.failure(EmptyExportException())
        }

        runCatching {
            val exportsDir = File(context.cacheDir, EXPORTS_SUBDIR).apply { mkdirs() }
            val fileName = buildFileName(exporter.fileExtension)
            val file = File(exportsDir, fileName)

            file.outputStream().use { out ->
                exporter.export(events, out)
            }

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }.onFailure { Log.e(TAG, "Export failed", it) }
    }

    private fun buildFileName(extension: String): String {
        val datePart = SimpleDateFormat("yyyyMMdd", Locale.ITALY).format(Date())
        return "promemoria_$datePart.$extension"
    }

    private companion object {
        const val EXPORTS_SUBDIR = "exports"
        const val TAG = "ExportEvents"
    }
}

class EmptyExportException : Exception()

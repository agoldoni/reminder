package it.agoldoni.reminder.export

import it.agoldoni.reminder.platform.appDataDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Segnaposto: il dialog di salvataggio nativo è il task T-16.
 * Per ora scrive in `<dati app>/export` e restituisce il percorso tramite [onExported].
 */
class DesktopExportTarget(
    private val onExported: (File) -> Unit = {}
) : ExportTarget {

    override suspend fun deliver(fileName: String, mimeType: String, bytes: ByteArray) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(appDataDirectory(), "export").apply { mkdirs() }
            File(dir, fileName).apply { writeBytes(bytes) }
        }
        onExported(file)
    }
}

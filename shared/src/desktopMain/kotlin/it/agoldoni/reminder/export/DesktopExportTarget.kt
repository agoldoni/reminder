package it.agoldoni.reminder.export

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Salvataggio con il dialog nativo del sistema. Se l'utente annulla non viene scritto nulla:
 * l'export resta un'operazione riuscita ma senza destinazione.
 */
class DesktopExportTarget(
    private val parent: Frame? = null,
    private val onSaved: (File) -> Unit = {}
) : ExportTarget {

    override suspend fun deliver(fileName: String, mimeType: String, bytes: ByteArray) {
        val destination = withContext(Dispatchers.IO) { chooseDestination(fileName) } ?: return
        withContext(Dispatchers.IO) { destination.writeBytes(bytes) }
        onSaved(destination)
    }

    private fun chooseDestination(fileName: String): File? {
        var chosen: File? = null
        EventQueue.invokeAndWait {
            val dialog = FileDialog(parent, "Salva promemoria", FileDialog.SAVE).apply {
                file = fileName
                directory = System.getProperty("user.home")
                isVisible = true
            }
            val directory = dialog.directory
            val name = dialog.file
            chosen = if (directory != null && name != null) File(directory, name) else null
        }
        return chosen
    }
}

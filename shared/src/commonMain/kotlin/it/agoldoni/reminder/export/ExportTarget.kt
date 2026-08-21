package it.agoldoni.reminder.export

/** Destinazione del file esportato: condivisione su Android, salvataggio su desktop. */
interface ExportTarget {
    suspend fun deliver(fileName: String, mimeType: String, bytes: ByteArray)
}

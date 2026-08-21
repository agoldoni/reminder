package it.agoldoni.reminder.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Scrive il file in cache e lo passa al chooser di condivisione di Android. */
class AndroidExportTarget(private val context: Context) : ExportTarget {

    override suspend fun deliver(fileName: String, mimeType: String, bytes: ByteArray) {
        val uri = withContext(Dispatchers.IO) {
            val exportsDir = File(context.cacheDir, EXPORTS_SUBDIR).apply { mkdirs() }
            val file = File(exportsDir, fileName)
            file.writeBytes(bytes)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "Condividi promemoria").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private companion object {
        const val EXPORTS_SUBDIR = "exports"
    }
}

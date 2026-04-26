package it.agoldoni.reminder.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class ShareHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exporter: Exporter
) {

    fun share(uri: Uri) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = exporter.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "Condividi promemoria").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}

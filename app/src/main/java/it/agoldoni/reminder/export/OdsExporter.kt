package it.agoldoni.reminder.export

import it.agoldoni.reminder.data.EventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Genera un file ODS (OpenDocument Spreadsheet) minimale conforme a ODF 1.2.
 * L'ODS è uno ZIP contenente almeno: `mimetype` (primo entry, STORED, non compresso),
 * `META-INF/manifest.xml`, `content.xml`. Implementazione manuale per evitare
 * dipendenze JVM non disponibili su Android (es. StAX in SODS).
 */
class OdsExporter @Inject constructor() : Exporter {

    override val mimeType: String = "application/vnd.oasis.opendocument.spreadsheet"
    override val fileExtension: String = "ods"

    override suspend fun export(events: List<EventEntity>, output: OutputStream) {
        withContext(Dispatchers.IO) {
            val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            ZipOutputStream(output).use { zip ->
                writeMimetype(zip)
                writeDeflated(zip, "META-INF/manifest.xml", buildManifest())
                writeDeflated(zip, "content.xml", buildContent(events, dateFormat))
            }
        }
    }

    private fun writeMimetype(zip: ZipOutputStream) {
        val bytes = mimeType.toByteArray(Charsets.US_ASCII)
        val entry = ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeDeflated(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun buildManifest(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">""")
        append("""<manifest:file-entry manifest:full-path="/" manifest:version="1.2" manifest:media-type="application/vnd.oasis.opendocument.spreadsheet"/>""")
        append("""<manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>""")
        append("""</manifest:manifest>""")
    }

    private fun buildContent(events: List<EventEntity>, dateFormat: SimpleDateFormat): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append(
            """<office:document-content """ +
                """xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
                """xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" """ +
                """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
                """office:version="1.2">"""
        )
        append("""<office:body><office:spreadsheet><table:table table:name="Promemoria">""")

        appendRow(
            CellValue.Str("Titolo"),
            CellValue.Str("Descrizione"),
            CellValue.Str("Data e ora"),
            CellValue.Str("Anticipo (min)"),
            CellValue.Str("Stato")
        )

        events.forEach { event ->
            appendRow(
                CellValue.Str(event.title),
                CellValue.Str(event.description.orEmpty()),
                CellValue.Str(dateFormat.format(Date(event.dateTimeMillis))),
                CellValue.Num(event.advanceMinutes),
                CellValue.Str(if (event.completed) "Completato" else "Aperto")
            )
        }

        append("""</table:table></office:spreadsheet></office:body></office:document-content>""")
    }

    private fun StringBuilder.appendRow(vararg cells: CellValue) {
        append("<table:table-row>")
        cells.forEach { cell ->
            when (cell) {
                is CellValue.Str -> {
                    append("""<table:table-cell office:value-type="string"><text:p>""")
                    appendEscaped(cell.value)
                    append("</text:p></table:table-cell>")
                }
                is CellValue.Num -> {
                    append("""<table:table-cell office:value-type="float" office:value="""")
                    append(cell.value)
                    append(""""><text:p>""")
                    append(cell.value)
                    append("</text:p></table:table-cell>")
                }
            }
        }
        append("</table:table-row>")
    }

    private fun StringBuilder.appendEscaped(s: String) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    private sealed interface CellValue {
        data class Str(val value: String) : CellValue
        data class Num(val value: Int) : CellValue
    }
}

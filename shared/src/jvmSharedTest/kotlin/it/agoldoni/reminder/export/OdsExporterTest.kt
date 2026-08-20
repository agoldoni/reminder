package it.agoldoni.reminder.export

import it.agoldoni.reminder.data.EventEntity
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OdsExporterTest {

    private val exporter = OdsExporter()

    private val events = listOf(
        EventEntity(
            id = 1,
            title = "Dentista",
            description = "controllo annuale",
            dateTimeMillis = 1_787_000_000_000,
            advanceMinutes = 30,
            completed = false
        ),
        EventEntity(
            id = 2,
            title = "Riunione & co <urgente>",
            description = null,
            dateTimeMillis = 1_787_100_000_000,
            advanceMinutes = 0,
            completed = true
        )
    )

    private fun entries(bytes: ByteArray): List<Pair<ZipEntry, String>> = buildList {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                add(entry to zip.readBytes().toString(Charsets.UTF_8))
            }
        }
    }

    @Test
    fun `il primo entry e mimetype non compresso, come richiede ODF`() = runBlocking {
        val entries = entries(exporter.export(events))
        val (first, content) = entries.first()

        assertEquals("mimetype", first.name)
        assertEquals(ZipEntry.STORED, first.method, "mimetype deve essere STORED")
        assertEquals("application/vnd.oasis.opendocument.spreadsheet", content)
    }

    @Test
    fun `l'archivio contiene manifest e contenuto`() = runBlocking {
        val names = entries(exporter.export(events)).map { it.first.name }

        assertContains(names, "META-INF/manifest.xml")
        assertContains(names, "content.xml")
    }

    @Test
    fun `il contenuto riporta intestazioni, eventi e stato`() = runBlocking {
        val content = entries(exporter.export(events)).first { it.first.name == "content.xml" }.second

        assertContains(content, "Titolo")
        assertContains(content, "Anticipo (min)")
        assertContains(content, "Dentista")
        assertContains(content, "controllo annuale")
        assertContains(content, "Aperto")
        assertContains(content, "Completato")
    }

    @Test
    fun `i caratteri speciali sono sottoposti a escape XML`() = runBlocking {
        val content = entries(exporter.export(events)).first { it.first.name == "content.xml" }.second

        assertContains(content, "Riunione &amp; co &lt;urgente&gt;")
        assertTrue(
            "co <urgente>" !in content,
            "il titolo non deve finire nell'XML senza escape"
        )
    }

    @Test
    fun `mime type ed estensione sono quelli di ODS`() {
        assertEquals("application/vnd.oasis.opendocument.spreadsheet", exporter.mimeType)
        assertEquals("ods", exporter.fileExtension)
    }
}

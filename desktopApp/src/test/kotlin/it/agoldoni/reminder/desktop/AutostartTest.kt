package it.agoldoni.reminder.desktop

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutostartTest {

    private val configDir: File = File.createTempFile("autostart-test", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    @AfterTest
    fun cleanUp() {
        configDir.deleteRecursively()
    }

    @Test
    fun `abilitare crea il desktop entry con il comando di avvio`() {
        val autostart = Autostart(configDir, command = "/opt/promemoria.AppImage")

        assertFalse(autostart.isEnabled(), "all'inizio non deve esistere")
        assertTrue(autostart.setEnabled(true))

        val content = autostart.file.readText()
        assertEquals(File(configDir, "autostart/promemoria.desktop"), autostart.file)
        assertTrue(autostart.isEnabled())
        assertContains(content, "Exec=/opt/promemoria.AppImage")
        assertContains(content, "Type=Application")
        assertContains(content, "Name=Promemoria")
    }

    @Test
    fun `disabilitare rimuove il desktop entry`() {
        val autostart = Autostart(configDir, command = "/opt/promemoria.AppImage")
        autostart.setEnabled(true)

        assertTrue(autostart.setEnabled(false))
        assertFalse(autostart.isEnabled())
        assertFalse(autostart.file.exists())
    }

    @Test
    fun `disabilitare quando non c'e nulla non e un errore`() {
        val autostart = Autostart(configDir, command = "/opt/promemoria.AppImage")

        assertTrue(autostart.setEnabled(false))
        assertFalse(autostart.isEnabled())
    }

    @Test
    fun `senza comando di avvio l'autostart non e supportato`() {
        val autostart = Autostart(configDir, command = null)

        assertFalse(autostart.isSupported)
        assertFalse(autostart.setEnabled(true), "non deve scrivere nulla senza comando")
        assertFalse(autostart.file.exists())
    }
}

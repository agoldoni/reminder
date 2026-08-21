package it.agoldoni.reminder.desktop

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopIntegrationTest {

    private lateinit var dataDir: File
    private val icona = ByteArray(64) { 7 }

    @BeforeTest
    fun setUp() {
        dataDir = File.createTempFile("promemoria-integrazione", "").let {
            it.delete(); it.apply { mkdirs() }
        }
    }

    @AfterTest
    fun tearDown() {
        dataDir.deleteRecursively()
    }

    private fun integrazione(comando: String? = "/opt/Promemoria.AppImage") =
        DesktopIntegration(dataDir = dataDir, command = comando, icon = { icona })

    @Test
    fun `registra voce e icona fra quelle dell'utente`() {
        val integrazione = integrazione()

        assertTrue(integrazione.register())

        assertTrue(integrazione.desktopFile.isFile, "serve una voce fra le applicazioni")
        assertTrue(integrazione.iconFile.isFile, "e un'icona nel tema, non un percorso volatile")
        assertEquals(icona.size, integrazione.iconFile.readBytes().size)
    }

    /**
     * È `StartupWMClass` a legare la finestra all'applicazione: senza, la barra non sa a chi
     * appartiene e mostra un'icona generica. Deve combaciare con quello che la finestra dichiara.
     */
    @Test
    fun `la voce dichiara la classe con cui la finestra si presenta`() {
        val integrazione = integrazione()
        integrazione.register()

        val contenuto = integrazione.desktopFile.readText()

        assertTrue("StartupWMClass=${wmClass()}" in contenuto, contenuto)
        assertTrue(
            "." !in wmClass(),
            "il JDK sostituisce i punti con trattini: ${wmClass()}"
        )
        assertTrue("Icon=promemoria" in contenuto, "l'icona è un nome del tema: $contenuto")
        assertTrue("Exec=/opt/Promemoria.AppImage" in contenuto, contenuto)
        assertTrue("Type=Application" in contenuto, contenuto)
    }

    /**
     * L'AppImage si sposta e si aggiorna: una voce che punta al percorso di ieri aprirebbe il
     * nulla, quindi va riscritta quando il comando cambia.
     */
    @Test
    fun `la voce viene riscritta se il comando è cambiato`() {
        integrazione(comando = "/vecchio/Promemoria.AppImage").register()
        val prima = integrazione(comando = "/vecchio/Promemoria.AppImage").desktopFile.readText()

        integrazione(comando = "/nuovo/Promemoria.AppImage").register()
        val dopo = integrazione().desktopFile.readText()

        assertTrue("/vecchio/" in prima)
        assertTrue("/nuovo/" in dopo, "la voce deve seguire l'eseguibile: $dopo")
    }

    @Test
    fun `senza un comando noto non si registra niente`() {
        val integrazione = integrazione(comando = null)

        assertFalse(integrazione.isSupported)
        assertFalse(integrazione.register())
        assertFalse(integrazione.desktopFile.exists(), "meglio nessuna voce che una che non apre nulla")
    }
}

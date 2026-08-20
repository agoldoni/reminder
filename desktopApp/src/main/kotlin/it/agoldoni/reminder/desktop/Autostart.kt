package it.agoldoni.reminder.desktop

import java.io.File

/**
 * Avvio al login secondo la specifica XDG Autostart: un file `.desktop` in
 * `$XDG_CONFIG_HOME/autostart` (di norma `~/.config/autostart`).
 */
class Autostart(
    configDir: File = defaultConfigDir(),
    private val command: String? = detectLaunchCommand()
) {

    val file: File = File(configDir, "autostart/$FILE_NAME")

    /** Falso quando il comando di avvio non è determinabile (es. eseguendo da Gradle). */
    val isSupported: Boolean get() = command != null

    fun isEnabled(): Boolean = file.exists()

    /** Restituisce `true` se lo stato richiesto è stato raggiunto. */
    fun setEnabled(enabled: Boolean): Boolean {
        if (!enabled) {
            file.delete()
            return !file.exists()
        }
        val exec = command ?: return false
        file.parentFile?.mkdirs()
        file.writeText(desktopEntry(exec))
        return file.exists()
    }

    private fun desktopEntry(exec: String): String = """
        [Desktop Entry]
        Type=Application
        Name=Promemoria
        Comment=Promemoria e scadenze
        Exec=$exec
        Terminal=false
        X-GNOME-Autostart-enabled=true
    """.trimIndent() + "\n"

    companion object {
        private const val FILE_NAME = "promemoria.desktop"

        fun defaultConfigDir(): File = File(
            System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                ?: File(System.getProperty("user.home"), ".config").absolutePath
        )

        /**
         * Come AppImage il percorso dell'eseguibile è in `APPIMAGE`; altrimenti si usa il
         * comando del processo, che però da Gradle è `java` e non serve a nulla.
         */
        fun detectLaunchCommand(): String? =
            System.getenv("APPIMAGE")?.takeIf { it.isNotBlank() }
                ?: ProcessHandle.current().info().command().orElse(null)
                    ?.takeUnless { it.substringAfterLast('/') == "java" }
    }
}

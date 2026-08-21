package it.agoldoni.reminder.desktop

import java.io.File

/**
 * Nome con cui la finestra si presenta al gestore delle finestre, ed è la barra delle applicazioni
 * a usarlo per capire a quale applicazione appartenga.
 *
 * **Non si può scegliere.** I JDK recenti non leggono più `awt.appClassName`: ricavano il valore
 * dalla classe `main`, prendendo l'elemento in fondo allo stack al momento in cui inizializzano il
 * toolkit e sostituendo i punti con trattini. Provare a imporne un altro non ha effetto — è stato
 * provato, e la finestra continuava a presentarsi come `it-agoldoni-reminder-desktop-MainKt`.
 *
 * Quindi si fa il contrario: si calcola il valore con la stessa regola del JDK e lo si dichiara
 * nella voce `.desktop`. Ricavarlo invece di scriverlo a mano vuol dire che rinominare il file
 * `Main.kt` non rompe l'associazione in silenzio.
 */
fun wmClass(): String =
    Thread.currentThread().stackTrace.last().className.replace('.', '-')

/**
 * Registra l'applicazione presso il desktop, così che la barra delle applicazioni le dia la sua
 * icona invece di una generica.
 *
 * Servono due cose che devono combaciare: una voce `.desktop` fra le applicazioni dell'utente, e
 * il campo `StartupWMClass` di quella voce uguale al [wmClass] della finestra. È quel campo a
 * fare il collegamento; l'icona indicata nella voce viene usata solo se il collegamento riesce.
 *
 * L'icona viene copiata fra quelle dell'utente perché `Icon=` di una voce `.desktop` vuole un
 * nome del tema, non un percorso dentro un pacchetto che cambia a ogni avvio — come accade con
 * l'AppImage, che si monta ogni volta in una cartella temporanea diversa.
 */
class DesktopIntegration(
    dataDir: File = defaultDataDir(),
    private val command: String? = Autostart.detectLaunchCommand(),
    private val icon: () -> ByteArray? = ::iconaDalleRisorse
) {

    val desktopFile: File = File(dataDir, "applications/$NOME_FILE")
    val iconFile: File = File(dataDir, "icons/hicolor/$DIMENSIONE_ICONA/apps/$NOME_ICONA.png")

    /** Falso quando non si sa quale comando riavvierebbe l'app, per esempio eseguendo da Gradle. */
    val isSupported: Boolean get() = command != null

    /**
     * Scrive la voce se manca o se il comando è cambiato — succede quando l'AppImage viene spostata
     * o aggiornata, e una voce che punta al vecchio percorso non aprirebbe più niente.
     */
    fun register(): Boolean {
        val exec = command ?: return false
        return runCatching {
            icon()?.let { byte ->
                if (!iconFile.isFile || iconFile.readBytes().size != byte.size) {
                    iconFile.parentFile?.mkdirs()
                    iconFile.writeBytes(byte)
                }
            }
            val contenuto = desktopEntry(exec)
            if (!desktopFile.isFile || desktopFile.readText() != contenuto) {
                desktopFile.parentFile?.mkdirs()
                desktopFile.writeText(contenuto)
            }
            true
        }.getOrDefault(false)
    }

    internal fun desktopEntry(exec: String): String = """
        [Desktop Entry]
        Type=Application
        Name=Promemoria
        Comment=Promemoria e scadenze
        Exec=$exec
        Icon=$NOME_ICONA
        Terminal=false
        Categories=Utility;
        StartupWMClass=${wmClass()}
    """.trimIndent() + "\n"

    companion object {
        private const val NOME_FILE = "promemoria.desktop"
        private const val NOME_ICONA = "promemoria"
        private const val DIMENSIONE_ICONA = "256x256"

        fun defaultDataDir(): File = File(
            System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                ?: File(System.getProperty("user.home"), ".local/share").absolutePath
        )
    }
}

private fun iconaDalleRisorse(): ByteArray? =
    DesktopIntegration::class.java.getResourceAsStream("/icon.png")?.use { it.readBytes() }

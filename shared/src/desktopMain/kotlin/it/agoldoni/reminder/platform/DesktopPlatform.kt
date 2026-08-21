package it.agoldoni.reminder.platform

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import it.agoldoni.reminder.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.net.InetAddress
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Dati applicativi secondo XDG: `$XDG_DATA_HOME/promemoria` o `~/.local/share/promemoria`. */
fun appDataDirectory(): File {
    val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
        ?: File(System.getProperty("user.home"), ".local/share").absolutePath
    return File(base, "promemoria").apply { mkdirs() }
}

/**
 * Identificativo stabile di questa installazione, accanto al database perché condivide con esso
 * il ciclo di vita: cancellare la cartella dei dati azzera anche le associazioni.
 */
fun localDeviceId(directory: File = appDataDirectory()): String {
    val file = File(directory, "device-id")
    file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return newUuid().also {
        directory.mkdirs()
        file.writeText(it)
    }
}

/** Nome con cui questo dispositivo si presenta sulla rete: l'hostname della macchina. */
fun localDeviceName(): String =
    System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }
        ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: "Promemoria desktop"

/** Su desktop SQLite non è garantito dal sistema: si usa il driver bundled. */
fun createAppDatabase(
    dbFile: File = File(appDataDirectory(), "reminder.db"),
    deviceId: String = localDeviceId()
): AppDatabase =
    Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
        .addMigrations(
            AppDatabase.MIGRATION_1_2,
            AppDatabase.migration2to3(deviceId),
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5
        )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

/** Preferenze in un file di properties accanto al database: stessa cartella, stesso ciclo di vita. */
class DesktopAppSettings(
    private val file: File = File(appDataDirectory(), "impostazioni.properties")
) : AppSettings {

    private val properties = Properties().apply {
        if (file.isFile) file.inputStream().use(::load)
    }

    private val _syncEnabled =
        MutableStateFlow(properties.getProperty(SYNC_ENABLED_KEY)?.toBoolean() ?: false)
    override val syncEnabled: StateFlow<Boolean> = _syncEnabled.asStateFlow()

    override fun setSyncEnabled(enabled: Boolean) {
        salva(SYNC_ENABLED_KEY, enabled)
        _syncEnabled.value = enabled
    }

    /**
     * Persistito anche qui, benché su desktop la web app non sia ancora cablata: il contratto di
     * [AppSettings] dice che le preferenze sopravvivono alla chiusura, e un flag che finge di
     * salvarsi sarebbe una bugia che si scopre solo il giorno in cui il desktop la userà.
     */
    private val _webEnabled =
        MutableStateFlow(properties.getProperty(WEB_ENABLED_KEY)?.toBoolean() ?: false)
    override val webEnabled: StateFlow<Boolean> = _webEnabled.asStateFlow()

    override fun setWebEnabled(enabled: Boolean) {
        salva(WEB_ENABLED_KEY, enabled)
        _webEnabled.value = enabled
    }

    private fun salva(chiave: String, valore: Boolean) {
        properties.setProperty(chiave, valore.toString())
        file.parentFile?.mkdirs()
        file.outputStream().use { properties.store(it, "Promemoria") }
    }

    private companion object {
        const val SYNC_ENABLED_KEY = "syncEnabled"
        const val WEB_ENABLED_KEY = "webEnabled"
    }
}

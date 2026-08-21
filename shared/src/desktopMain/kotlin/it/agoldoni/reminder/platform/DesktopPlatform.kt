package it.agoldoni.reminder.platform

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import it.agoldoni.reminder.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.net.InetAddress

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
            AppDatabase.MIGRATION_3_4
        )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

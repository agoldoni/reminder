package it.agoldoni.reminder.platform

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import it.agoldoni.reminder.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import java.io.File

/** Dati applicativi secondo XDG: `$XDG_DATA_HOME/promemoria` o `~/.local/share/promemoria`. */
fun appDataDirectory(): File {
    val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
        ?: File(System.getProperty("user.home"), ".local/share").absolutePath
    return File(base, "promemoria").apply { mkdirs() }
}

/** Su desktop SQLite non è garantito dal sistema: si usa il driver bundled. */
fun createAppDatabase(dbFile: File = File(appDataDirectory(), "reminder.db")): AppDatabase =
    Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
        .addMigrations(AppDatabase.MIGRATION_1_2)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

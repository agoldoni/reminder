package it.agoldoni.reminder.platform

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import it.agoldoni.reminder.alarm.AlarmScheduler as AndroidAlarms
import it.agoldoni.reminder.data.AppDatabase
import it.agoldoni.reminder.data.EventEntity
import it.agoldoni.reminder.di.AppContainer
import kotlinx.coroutines.Dispatchers

/** Su Android si usa SQLite di sistema: nessuna libreria nativa da imbarcare nell'APK. */
fun createAppDatabase(context: Context): AppDatabase =
    Room.databaseBuilder<AppDatabase>(
        context = context.applicationContext,
        name = context.getDatabasePath(DATABASE_NAME).absolutePath
    )
        .addMigrations(AppDatabase.MIGRATION_1_2)
        .setDriver(AndroidSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

private const val DATABASE_NAME = "reminder.db"

class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {
    override fun schedule(event: EventEntity) = AndroidAlarms.schedule(context, event)
    override fun cancel(eventId: Long) = AndroidAlarms.cancel(context, eventId)
}

/**
 * Ponte fra l'`Application` (modulo `:androidApp`) e i componenti Android dichiarati nel
 * manifest ma implementati qui: i receiver non hanno altro modo di raggiungere il container.
 */
object AndroidAppContainer {
    lateinit var instance: AppContainer
}

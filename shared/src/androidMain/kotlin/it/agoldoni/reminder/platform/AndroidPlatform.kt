package it.agoldoni.reminder.platform

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import it.agoldoni.reminder.alarm.AlarmScheduler as AndroidAlarms
import it.agoldoni.reminder.data.AppDatabase
import it.agoldoni.reminder.data.EventEntity
import it.agoldoni.reminder.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Su Android si usa SQLite di sistema: nessuna libreria nativa da imbarcare nell'APK. */
fun createAppDatabase(context: Context, deviceId: String = localDeviceId(context)): AppDatabase =
    Room.databaseBuilder<AppDatabase>(
        context = context.applicationContext,
        name = context.getDatabasePath(DATABASE_NAME).absolutePath
    )
        .addMigrations(
            AppDatabase.MIGRATION_1_2,
            AppDatabase.migration2to3(deviceId),
            AppDatabase.MIGRATION_3_4
        )
        .setDriver(AndroidSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

private const val DATABASE_NAME = "reminder.db"
private const val DEVICE_PREFS = "device"
private const val DEVICE_ID_KEY = "id"
private val deviceIdLock = Any()

/**
 * Identificativo stabile di questa installazione, generato al primo avvio. Sopravvive agli
 * aggiornamenti dell'app ma non alla disinstallazione, che è il comportamento voluto: dopo una
 * reinstallazione il dispositivo va associato di nuovo.
 */
fun localDeviceId(context: Context): String = synchronized(deviceIdLock) {
    val prefs = context.applicationContext.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)
    prefs.getString(DEVICE_ID_KEY, null)
        ?: newUuid().also { prefs.edit().putString(DEVICE_ID_KEY, it).apply() }
}

/**
 * Nome con cui questo dispositivo si presenta sulla rete. `device_name` è quello che l'utente ha
 * scelto nelle impostazioni; il modello è il ripiego quando non è stato impostato.
 */
fun localDeviceName(context: Context): String =
    Settings.Global.getString(context.applicationContext.contentResolver, "device_name")
        ?.takeIf { it.isNotBlank() }
        ?: Build.MODEL

/** Preferenze su `SharedPreferences`: sopravvivono agli aggiornamenti, non alla disinstallazione. */
class AndroidAppSettings(context: Context) : AppSettings {

    private val prefs = context.applicationContext
        .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)

    private val _syncEnabled = MutableStateFlow(prefs.getBoolean(SYNC_ENABLED_KEY, false))
    override val syncEnabled: StateFlow<Boolean> = _syncEnabled.asStateFlow()

    override fun setSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SYNC_ENABLED_KEY, enabled).apply()
        _syncEnabled.value = enabled
    }

    private companion object {
        const val SETTINGS_PREFS = "impostazioni"
        const val SYNC_ENABLED_KEY = "syncEnabled"
    }
}

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

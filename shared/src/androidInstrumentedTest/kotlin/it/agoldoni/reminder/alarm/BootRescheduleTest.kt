package it.agoldoni.reminder.alarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import it.agoldoni.reminder.data.AppDatabase
import it.agoldoni.reminder.data.EventEntity
import it.agoldoni.reminder.platform.AndroidAlarmScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Verifica su device la riprogrammazione degli allarmi dopo il riavvio: `BOOT_COMPLETED` è un
 * broadcast protetto e non si può simulare, ma la logica che il receiver esegue sì.
 */
@RunWith(AndroidJUnit4::class)
class BootRescheduleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AppDatabase
    private lateinit var scheduler: AndroidAlarmScheduler

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<AppDatabase>(context)
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        scheduler = AndroidAlarmScheduler(context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun un_evento_futuro_viene_riprogrammato() = runBlocking {
        val id = database.eventDao().insert(
            EventEntity(
                title = "Dopo il riavvio",
                dateTimeMillis = System.currentTimeMillis() + 3_600_000,
                advanceMinutes = 0
            )
        )
        assertNull(alarmOf(id), "prima non deve esistere alcun allarme")

        rescheduleFutureAlarms(database.eventDao(), scheduler)

        assertNotNull(alarmOf(id), "dopo la riprogrammazione l'allarme deve esistere")
        scheduler.cancel(id)
    }

    @Test
    fun gli_eventi_completati_e_scaduti_restano_fuori() = runBlocking {
        val dao = database.eventDao()
        val completato = dao.insert(
            EventEntity(
                title = "Già fatto",
                dateTimeMillis = System.currentTimeMillis() + 3_600_000,
                advanceMinutes = 0,
                completed = true
            )
        )
        val scaduto = dao.insert(
            EventEntity(
                title = "Scaduto",
                dateTimeMillis = System.currentTimeMillis() - 3_600_000,
                advanceMinutes = 0
            )
        )

        rescheduleFutureAlarms(dao, scheduler)

        assertNull(alarmOf(completato), "un evento completato non va riprogrammato")
        assertNull(alarmOf(scaduto), "un evento scaduto non va riprogrammato")
    }

    private fun alarmOf(eventId: Long): PendingIntent? = PendingIntent.getBroadcast(
        context,
        RequestCodes.alarm(eventId),
        Intent(context, AlarmReceiver::class.java),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    )
}

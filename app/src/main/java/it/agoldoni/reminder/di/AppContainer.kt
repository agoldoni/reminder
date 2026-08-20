package it.agoldoni.reminder.di

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import it.agoldoni.reminder.ReminderApp
import it.agoldoni.reminder.data.AppDatabase
import it.agoldoni.reminder.data.EventDao
import it.agoldoni.reminder.export.ExportEventsUseCase
import it.agoldoni.reminder.export.Exporter
import it.agoldoni.reminder.export.OdsExporter
import it.agoldoni.reminder.export.ShareHelper
import it.agoldoni.reminder.ui.completed.CompletedViewModel
import it.agoldoni.reminder.ui.edit.EventEditViewModel
import it.agoldoni.reminder.ui.list.EventListViewModel

/**
 * Contenitore delle dipendenze dell'app, costruito una volta in [ReminderApp].
 * Sostituisce Hilt: con una dozzina di punti di iniezione un container esplicito
 * costa meno di un framework, ed è l'unica forma utilizzabile nel modulo condiviso KMP.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "reminder.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
    }

    val eventDao: EventDao by lazy { database.eventDao() }

    val exporter: Exporter by lazy { OdsExporter() }

    val exportEventsUseCase: ExportEventsUseCase by lazy {
        ExportEventsUseCase(appContext, eventDao, exporter)
    }

    val shareHelper: ShareHelper by lazy { ShareHelper(appContext, exporter) }

    /** Factory per i ViewModel senza argomenti di navigazione. */
    val viewModelFactory: ViewModelProvider.Factory by lazy {
        viewModelFactory {
            initializer { EventListViewModel(eventDao, exportEventsUseCase, shareHelper, appContext) }
            initializer { CompletedViewModel(eventDao, appContext) }
        }
    }

    /** Factory dell'editor: l'id dell'evento arriva dalla rotta di navigazione. */
    fun eventEditViewModelFactory(eventId: Long): ViewModelProvider.Factory = viewModelFactory {
        initializer { EventEditViewModel(eventDao, eventId, appContext) }
    }
}

/** Accesso al container da qualsiasi punto che disponga di un [Context]. */
val Context.appContainer: AppContainer
    get() = (applicationContext as ReminderApp).container

package it.agoldoni.reminder.di

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import it.agoldoni.reminder.data.EventDao
import it.agoldoni.reminder.export.ExportEventsUseCase
import it.agoldoni.reminder.export.ExportTarget
import it.agoldoni.reminder.export.Exporter
import it.agoldoni.reminder.platform.AlarmScheduler
import it.agoldoni.reminder.platform.AppInfo
import it.agoldoni.reminder.ui.completed.CompletedViewModel
import it.agoldoni.reminder.ui.edit.EventEditViewModel
import it.agoldoni.reminder.ui.list.EventListViewModel

/**
 * Dipendenze dell'app. Le parti di piattaforma (allarmi, destinazione dell'export, database)
 * sono costruite dal modulo applicativo e passate qui: il container resta comune.
 */
class AppContainer(
    val eventDao: EventDao,
    val alarmScheduler: AlarmScheduler,
    val appInfo: AppInfo,
    /** Identità di questa installazione: marchia gli eventi creati qui. */
    val deviceId: String,
    exporter: Exporter,
    exportTarget: ExportTarget
) {

    val exportEventsUseCase: ExportEventsUseCase =
        ExportEventsUseCase(eventDao, exporter, exportTarget)

    /** ViewModel senza argomenti di navigazione. */
    val viewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer { EventListViewModel(eventDao, exportEventsUseCase, alarmScheduler) }
        initializer { CompletedViewModel(eventDao, alarmScheduler) }
    }

    /** Factory dell'editor: l'id dell'evento arriva dalla rotta di navigazione. */
    fun eventEditViewModelFactory(eventId: Long): ViewModelProvider.Factory = viewModelFactory {
        initializer { EventEditViewModel(eventDao, eventId, alarmScheduler, deviceId) }
    }
}

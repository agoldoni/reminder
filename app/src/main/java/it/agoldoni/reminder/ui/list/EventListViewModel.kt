package it.agoldoni.reminder.ui.list

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.agoldoni.reminder.alarm.AlarmScheduler
import it.agoldoni.reminder.data.EventDao
import it.agoldoni.reminder.data.EventEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EventListViewModel @Inject constructor(
    private val dao: EventDao,
    application: Application
) : AndroidViewModel(application) {

    val events: StateFlow<List<EventEntity>> = dao.getActiveSortedDesc()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(event: EventEntity) {
        viewModelScope.launch {
            dao.delete(event)
            AlarmScheduler.cancel(getApplication(), event.id)
        }
    }

    fun markCompleted(event: EventEntity) {
        viewModelScope.launch {
            dao.markCompleted(event.id)
            AlarmScheduler.cancel(getApplication(), event.id)
        }
    }
}

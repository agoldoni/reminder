package it.agoldoni.reminder.ui.completed

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
class CompletedViewModel @Inject constructor(
    private val dao: EventDao,
    application: Application
) : AndroidViewModel(application) {

    val events: StateFlow<List<EventEntity>> = dao.getCompletedSortedDesc()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun restore(event: EventEntity) {
        viewModelScope.launch {
            dao.markActive(event.id)
            AlarmScheduler.schedule(getApplication(), event)
        }
    }

    fun delete(event: EventEntity) {
        viewModelScope.launch {
            dao.delete(event)
        }
    }
}

package it.agoldoni.reminder.ui.completed

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.agoldoni.reminder.alarm.AlarmScheduler
import it.agoldoni.reminder.data.EventDao
import it.agoldoni.reminder.data.EventEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CompletedViewModel(
    private val dao: EventDao,
    private val appContext: Context
) : ViewModel() {

    val events: StateFlow<List<EventEntity>> = dao.getCompletedSortedDesc()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun restore(event: EventEntity) {
        viewModelScope.launch {
            dao.markActive(event.id)
            AlarmScheduler.schedule(appContext, event)
        }
    }

    fun delete(event: EventEntity) {
        viewModelScope.launch {
            dao.delete(event)
        }
    }
}

package it.agoldoni.reminder.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.agoldoni.reminder.platform.AlarmScheduler
import it.agoldoni.reminder.data.EventDao
import it.agoldoni.reminder.data.EventEntity
import it.agoldoni.reminder.export.EmptyExportException
import it.agoldoni.reminder.export.ExportEventsUseCase
import it.agoldoni.reminder.export.ExportFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class EventListViewModel(
    private val dao: EventDao,
    private val exportEventsUseCase: ExportEventsUseCase,
    private val alarmScheduler: AlarmScheduler
) : ViewModel() {

    val events: StateFlow<List<EventEntity>> = dao.getActiveSortedAsc()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _exportState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val exportState: StateFlow<ExportUiState> = _exportState.asStateFlow()

    fun delete(event: EventEntity) {
        viewModelScope.launch {
            dao.delete(event)
            alarmScheduler.cancel(event.id)
        }
    }

    fun markCompleted(event: EventEntity) {
        viewModelScope.launch {
            dao.markCompleted(event.id)
            alarmScheduler.cancel(event.id)
        }
    }

    fun export(filter: ExportFilter) {
        if (_exportState.value is ExportUiState.Loading) return
        _exportState.value = ExportUiState.Loading
        viewModelScope.launch {
            exportEventsUseCase.execute(filter)
                .onSuccess { _exportState.value = ExportUiState.Idle }
                .onFailure { error ->
                    _exportState.value = when (error) {
                        is EmptyExportException -> ExportUiState.Empty
                        else -> ExportUiState.Error
                    }
                }
        }
    }

    fun consumeExportState() {
        _exportState.value = ExportUiState.Idle
    }
}

sealed interface ExportUiState {
    data object Idle : ExportUiState
    data object Loading : ExportUiState
    data object Empty : ExportUiState
    data object Error : ExportUiState
}

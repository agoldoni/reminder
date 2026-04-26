package it.agoldoni.reminder.ui.list

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.agoldoni.reminder.alarm.AlarmScheduler
import it.agoldoni.reminder.data.EventDao
import it.agoldoni.reminder.data.EventEntity
import it.agoldoni.reminder.export.EmptyExportException
import it.agoldoni.reminder.export.ExportEventsUseCase
import it.agoldoni.reminder.export.ExportFilter
import it.agoldoni.reminder.export.ShareHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EventListViewModel @Inject constructor(
    private val dao: EventDao,
    private val exportEventsUseCase: ExportEventsUseCase,
    private val shareHelper: ShareHelper,
    application: Application
) : AndroidViewModel(application) {

    val events: StateFlow<List<EventEntity>> = dao.getActiveSortedAsc()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _exportState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val exportState: StateFlow<ExportUiState> = _exportState.asStateFlow()

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

    fun export(filter: ExportFilter) {
        if (_exportState.value is ExportUiState.Loading) return
        _exportState.value = ExportUiState.Loading
        viewModelScope.launch {
            val result = exportEventsUseCase.execute(filter)
            result
                .onSuccess { uri ->
                    shareHelper.share(uri)
                    _exportState.value = ExportUiState.Idle
                }
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

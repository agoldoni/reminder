package it.agoldoni.reminder.ui.edit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.agoldoni.reminder.alarm.AlarmScheduler
import it.agoldoni.reminder.data.EventDao
import it.agoldoni.reminder.data.EventEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EventEditViewModel @Inject constructor(
    private val dao: EventDao,
    savedStateHandle: SavedStateHandle,
    application: Application
) : AndroidViewModel(application) {

    val eventId: Long = savedStateHandle.get<Long>("eventId") ?: 0L

    private val _title = MutableStateFlow("")
    val title = _title.asStateFlow()

    private val _description = MutableStateFlow("")
    val description = _description.asStateFlow()

    private val _dateTimeMillis = MutableStateFlow(System.currentTimeMillis() + 3600_000L)
    val dateTimeMillis = _dateTimeMillis.asStateFlow()

    private val _advanceMinutes = MutableStateFlow(15)
    val advanceMinutes = _advanceMinutes.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved = _saved.asStateFlow()

    init {
        if (eventId != 0L) {
            viewModelScope.launch {
                dao.getById(eventId)?.let { event ->
                    _title.value = event.title
                    _description.value = event.description ?: ""
                    _dateTimeMillis.value = event.dateTimeMillis
                    _advanceMinutes.value = event.advanceMinutes
                }
            }
        }
    }

    fun setTitle(value: String) { _title.value = value }
    fun setDescription(value: String) { _description.value = value }
    fun setDateTimeMillis(value: Long) { _dateTimeMillis.value = value }
    fun setAdvanceMinutes(value: Int) { _advanceMinutes.value = value }

    fun save() {
        if (_title.value.isBlank()) return
        viewModelScope.launch {
            val event = EventEntity(
                id = eventId,
                title = _title.value.trim(),
                description = _description.value.trim().ifBlank { null },
                dateTimeMillis = _dateTimeMillis.value,
                advanceMinutes = _advanceMinutes.value
            )
            val savedEvent = if (eventId == 0L) {
                val newId = dao.insert(event)
                event.copy(id = newId)
            } else {
                dao.update(event)
                event
            }
            AlarmScheduler.schedule(getApplication(), savedEvent)
            _saved.value = true
        }
    }
}

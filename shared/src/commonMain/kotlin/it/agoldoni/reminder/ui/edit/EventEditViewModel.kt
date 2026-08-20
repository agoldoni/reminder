package it.agoldoni.reminder.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.agoldoni.reminder.platform.AlarmScheduler
import it.agoldoni.reminder.platform.nowMillis
import it.agoldoni.reminder.data.EventDao
import it.agoldoni.reminder.data.EventEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EventEditViewModel(
    private val dao: EventDao,
    val eventId: Long,
    private val alarmScheduler: AlarmScheduler
) : ViewModel() {

    private val _title = MutableStateFlow("")
    val title = _title.asStateFlow()

    private val _description = MutableStateFlow("")
    val description = _description.asStateFlow()

    private val _dateTimeMillis = MutableStateFlow(nowMillis() + 3600_000L)
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
            alarmScheduler.schedule(savedEvent)
            _saved.value = true
        }
    }
}

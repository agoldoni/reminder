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
    private val alarmScheduler: AlarmScheduler,
    private val deviceId: String
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

    /**
     * Riga di partenza in modifica. Il salvataggio ci ricopia sopra i campi del modulo invece di
     * costruire un'entità nuova: uuid, origine e stato di completamento appartengono all'evento,
     * non alla schermata, e rigenerarli spezzerebbe l'identità vista dagli altri dispositivi.
     */
    private var existing: EventEntity? = null

    init {
        if (eventId != 0L) {
            viewModelScope.launch {
                load()?.let { event ->
                    _title.value = event.title
                    _description.value = event.description ?: ""
                    _dateTimeMillis.value = event.dateTimeMillis
                    _advanceMinutes.value = event.advanceMinutes
                }
            }
        }
    }

    private suspend fun load(): EventEntity? =
        existing ?: dao.getById(eventId)?.also { existing = it }

    fun setTitle(value: String) { _title.value = value }
    fun setDescription(value: String) { _description.value = value }
    fun setDateTimeMillis(value: Long) { _dateTimeMillis.value = value }
    fun setAdvanceMinutes(value: Int) { _advanceMinutes.value = value }

    fun save() {
        if (_title.value.isBlank()) return
        viewModelScope.launch {
            // Rilettura anche al salvataggio: se l'utente salva prima che il caricamento iniziale
            // sia arrivato, `existing` è ancora nullo e si perderebbe l'identità della riga.
            val base = if (eventId == 0L) null else load()
            val event = (base ?: EventEntity(
                title = _title.value,
                dateTimeMillis = _dateTimeMillis.value,
                origin = deviceId
            )).copy(
                title = _title.value.trim(),
                description = _description.value.trim().ifBlank { null },
                dateTimeMillis = _dateTimeMillis.value,
                advanceMinutes = _advanceMinutes.value,
                updatedAt = nowMillis()
            )
            val savedEvent = if (base == null) {
                val newId = dao.insert(event)
                event.copy(id = newId)
            } else {
                dao.update(event)
                event
            }
            existing = savedEvent
            alarmScheduler.schedule(savedEvent)
            _saved.value = true
        }
    }
}

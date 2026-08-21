package it.agoldoni.reminder.ui.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import it.agoldoni.reminder.platform.LocalAppContainer
import it.agoldoni.reminder.platform.formatDate
import it.agoldoni.reminder.platform.formatTime
import it.agoldoni.reminder.platform.hourOf
import it.agoldoni.reminder.platform.minuteOf
import it.agoldoni.reminder.platform.withDateFrom
import it.agoldoni.reminder.platform.withTime

private val advanceOptions = listOf(
    0 to "All'ora dell'evento",
    5 to "5 minuti prima",
    15 to "15 minuti prima",
    30 to "30 minuti prima",
    60 to "1 ora prima"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditScreen(
    eventId: Long,
    onBack: () -> Unit,
    viewModel: EventEditViewModel =
        viewModel(factory = LocalAppContainer.current.eventEditViewModelFactory(eventId))
) {
    val title by viewModel.title.collectAsState()
    val description by viewModel.description.collectAsState()
    val dateTimeMillis by viewModel.dateTimeMillis.collectAsState()
    val advanceMinutes by viewModel.advanceMinutes.collectAsState()
    val saved by viewModel.saved.collectAsState()

    LaunchedEffect(saved) {
        if (saved) onBack()
    }


    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var advanceExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.eventId == 0L) "Nuovo evento" else "Modifica evento") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = viewModel::setTitle,
                label = { Text("Titolo *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = description,
                onValueChange = viewModel::setDescription,
                label = { Text("Descrizione") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = formatDate(dateTimeMillis),
                onValueChange = {},
                label = { Text("Data") },
                readOnly = true,
                modifier = Modifier.fillMaxWidth().clickable { showDatePicker = true },
                enabled = false
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = formatTime(dateTimeMillis),
                onValueChange = {},
                label = { Text("Ora") },
                readOnly = true,
                modifier = Modifier.fillMaxWidth().clickable { showTimePicker = true },
                enabled = false
            )

            Spacer(Modifier.height(12.dp))

            ExposedDropdownMenuBox(
                expanded = advanceExpanded,
                onExpandedChange = { advanceExpanded = !advanceExpanded }
            ) {
                OutlinedTextField(
                    value = advanceOptions.first { it.first == advanceMinutes }.second,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Anticipo notifica") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = advanceExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = advanceExpanded,
                    onDismissRequest = { advanceExpanded = false }
                ) {
                    advanceOptions.forEach { (minutes, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                viewModel.setAdvanceMinutes(minutes)
                                advanceExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { viewModel.save() },
                modifier = Modifier.fillMaxWidth(),
                enabled = title.isNotBlank()
            ) {
                Text("Salva")
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = dateTimeMillis
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { selectedDate ->
                        viewModel.setDateTimeMillis(withDateFrom(dateTimeMillis, selectedDate))
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Annulla") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = hourOf(dateTimeMillis),
            initialMinute = minuteOf(dateTimeMillis),
            is24Hour = true
        )
        TimePickerDialog(
            onDismiss = { showTimePicker = false },
            onConfirm = {
                viewModel.setDateTimeMillis(
                    withTime(dateTimeMillis, timePickerState.hour, timePickerState.minute)
                )
                showTimePicker = false
            }
        ) {
            TimePicker(state = timePickerState)
        }
    }
}

@Composable
private fun TimePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
        text = { content() }
    )
}

package it.agoldoni.reminder.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import it.agoldoni.reminder.data.EventEntity
import it.agoldoni.reminder.export.ExportFilter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventListScreen(
    onAddEvent: () -> Unit,
    onEditEvent: (Long) -> Unit,
    onNavigateToCompleted: () -> Unit,
    viewModel: EventListViewModel = hiltViewModel()
) {
    val events by viewModel.events.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
    var eventToRemove by remember { mutableStateOf<EventEntity?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(exportState) {
        when (exportState) {
            ExportUiState.Empty -> {
                snackbarHostState.showSnackbar("Nessun promemoria da esportare")
                viewModel.consumeExportState()
            }
            ExportUiState.Error -> {
                snackbarHostState.showSnackbar("Errore nella generazione del file")
                viewModel.consumeExportState()
            }
            else -> {}
        }
    }

    eventToRemove?.let { event ->
        AlertDialog(
            onDismissRequest = { eventToRemove = null },
            title = { Text("Rimuovi evento") },
            text = { Text("Cosa vuoi fare con \"${event.title}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(event)
                    eventToRemove = null
                }) {
                    Text("Elimina", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { eventToRemove = null }) {
                        Text("Annulla")
                    }
                    TextButton(onClick = {
                        viewModel.markCompleted(event)
                        eventToRemove = null
                    }) {
                        Text("Fatto")
                    }
                }
            }
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Esporta promemoria") },
            text = { Text("Quali promemoria vuoi esportare?") },
            confirmButton = {
                TextButton(onClick = {
                    showExportDialog = false
                    viewModel.export(ExportFilter.OPEN_ONLY)
                }) {
                    Text("Solo aperti")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showExportDialog = false }) {
                        Text("Annulla")
                    }
                    TextButton(onClick = {
                        showExportDialog = false
                        viewModel.export(ExportFilter.ALL)
                    }) {
                        Text("Tutti")
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Promemoria") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    if (exportState == ExportUiState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp).padding(end = 4.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = { showExportDialog = true }) {
                            Icon(Icons.Default.Share, contentDescription = "Esporta")
                        }
                    }
                    IconButton(onClick = onNavigateToCompleted) {
                        Icon(Icons.Default.CheckCircle, contentDescription = "Fatti")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddEvent) {
                Icon(Icons.Default.Add, contentDescription = "Aggiungi evento")
            }
        }
    ) { padding ->
        if (events.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Nessun evento", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
            ) {
                items(events, key = { it.id }) { event ->
                    EventCard(
                        event = event,
                        onClick = { onEditEvent(event.id) },
                        onDeleteClick = { eventToRemove = event }
                    )
                }
            }
        }
    }
}

@Composable
internal fun EventCard(event: EventEntity, onClick: () -> Unit, onDeleteClick: () -> Unit) {
    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    val timeFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    val notificationMillis = event.dateTimeMillis - event.advanceMinutes * 60_000L
    val now = System.currentTimeMillis()
    val isPast = event.dateTimeMillis < now
    val startOfDayAfterTomorrow = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, 2)
    }.timeInMillis
    val isTodayOrTomorrow = !isPast && event.dateTimeMillis < startOfDayAfterTomorrow
    val isDark = isSystemInDarkTheme()

    val containerColor: Color
    val contentColor: Color
    when {
        isPast -> {
            containerColor = MaterialTheme.colorScheme.surfaceVariant
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
        isTodayOrTomorrow -> {
            containerColor = if (isDark) Color(0xFF5D4A1F) else Color(0xFFFFD54F)
            contentColor = if (isDark) Color(0xFFFFF8E1) else Color(0xFF2E1A00)
        }
        else -> {
            containerColor = if (isDark) Color(0xFF1F3D25) else Color(0xFF81C784)
            contentColor = if (isDark) Color(0xFFE8F5E9) else Color(0xFF0D2E0F)
        }
    }
    val dateColor = if (isPast) MaterialTheme.colorScheme.error else contentColor
    val notificationColor = contentColor.copy(alpha = 0.75f)

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = dateFormat.format(Date(event.dateTimeMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = dateColor,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Notifica: ${timeFormat.format(Date(notificationMillis))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = notificationColor
                )
            }
            IconButton(
                onClick = onDeleteClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Rimuovi",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

package it.agoldoni.reminder.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import it.agoldoni.reminder.platform.LocalAppContainer
import it.agoldoni.reminder.platform.formatDateTime
import it.agoldoni.reminder.sync.DiscoveredPeer
import it.agoldoni.reminder.sync.DiscoveryStatus
import it.agoldoni.reminder.sync.PairedPeer
import it.agoldoni.reminder.sync.PeerSource
import it.agoldoni.reminder.sync.SYNC_PORT
import it.agoldoni.reminder.ui.web.SezioneWebApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onBack: () -> Unit,
    viewModel: SyncViewModel = viewModel(factory = LocalAppContainer.current.viewModelFactory)
) {
    val status by viewModel.status.collectAsState()
    val discovered by viewModel.discovered.collectAsState()
    val discoveryStatus by viewModel.discoveryStatus.collectAsState()
    val paired by viewModel.paired.collectAsState()
    val prompt by viewModel.prompt.collectAsState()
    val message by viewModel.message.collectAsState()

    var peerDaDissociare by remember { mutableStateOf<PairedPeer?>(null) }
    var mostraMessaggio by remember { mutableStateOf<String?>(null) }
    var mostraInserimentoManuale by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Aprire questa schermata accende ricerca e ascolto anche a sincronizzazione spenta: è già un
    // atto esplicito dell'utente, e senza non ci sarebbe modo di trovare il primo dispositivo da
    // associare. È anche l'unico posto in cui si può mostrare il codice di un'associazione in
    // arrivo, quindi fuori da qui quelle associazioni vengono negate.
    DisposableEffect(Unit) {
        viewModel.apriSchermata()
        onDispose { viewModel.chiudiSchermata() }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumaMessaggio()
        }
    }

    LaunchedEffect(mostraMessaggio) {
        mostraMessaggio?.let {
            snackbarHostState.showSnackbar(it)
            mostraMessaggio = null
        }
    }

    prompt?.let { richiesta ->
        ConfrontoCodiceDialog(
            prompt = richiesta,
            onRisposta = viewModel::rispondiAlConfronto
        )
    }

    peerDaDissociare?.let { peer ->
        AlertDialog(
            onDismissRequest = { peerDaDissociare = null },
            title = { Text("Dissocia dispositivo") },
            text = {
                Text(
                    "Dissociare \"${peer.displayName}\"? I promemoria già sincronizzati restano, " +
                        "ma i due dispositivi smettono di allinearsi finché non li associ di nuovo."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dissocia(peer.deviceId)
                    peerDaDissociare = null
                }) {
                    Text("Dissocia", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { peerDaDissociare = null }) { Text("Annulla") }
            }
        )
    }

    if (mostraInserimentoManuale) {
        InserimentoManualeDialog(
            onConferma = { host, port ->
                viewModel.aggiungiManuale(host, port)
                mostraInserimentoManuale = false
            },
            onAnnulla = { mostraInserimentoManuale = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sincronizzazione") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                actions = {
                    if (status.syncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp).padding(end = 8.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = viewModel::sincronizzaOra) {
                            Icon(Icons.Default.Refresh, contentDescription = "Sincronizza ora")
                        }
                    }
                    IconButton(onClick = { mostraInserimentoManuale = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Aggiungi indirizzo")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
        ) {
            item {
                StatoRiepilogo(
                    host = status.listeningHost,
                    porta = status.listeningPort,
                    ultimoSync = status.lastSyncAt,
                    messaggio = status.lastMessage
                )
            }

            // La web app locale sta qui perché è la stessa materia: questo dispositivo, la rete
            // che lo circonda e chi lo può raggiungere. La sezione si nasconde da sé sulle
            // piattaforme dove non è cablata.
            item { SezioneWebApp(onMessaggio = { messaggio -> mostraMessaggio = messaggio }) }

            item { Intestazione("Dispositivi associati") }
            if (paired.isEmpty()) {
                item { Spiegazione("Nessun dispositivo associato. Scegline uno qui sotto per cominciare.") }
            } else {
                items(paired, key = { it.deviceId }) { peer ->
                    PeerAssociato(peer, onDissocia = { peerDaDissociare = peer })
                }
            }

            item { Intestazione("Trovati sulla rete") }
            (discoveryStatus as? DiscoveryStatus.Unavailable)?.let {
                item { Spiegazione(it.message, errore = true) }
            }
            val daAssociare = discovered.filterNot { trovato ->
                paired.any { it.deviceId == trovato.deviceId }
            }
            if (daAssociare.isEmpty()) {
                item {
                    Spiegazione(
                        if (discoveryStatus is DiscoveryStatus.Searching) {
                            "Ricerca in corso. L'altro dispositivo deve avere l'app aperta su " +
                                "questa stessa schermata."
                        } else {
                            "Nessun dispositivo in vista."
                        }
                    )
                }
            } else {
                items(daAssociare, key = { "${it.host}:${it.port}" }) { peer ->
                    PeerDaAssociare(peer, onAssocia = { viewModel.associa(peer) })
                }
            }
        }
    }
}

/**
 * Il momento decisivo dell'associazione, e l'unica cosa che la rende sicura: lo stesso numero
 * deve comparire su **entrambi** gli schermi. Il testo lo dice esplicitamente, perché un utente
 * che confermi senza guardare l'altro dispositivo annulla la protezione.
 */
@Composable
private fun ConfrontoCodiceDialog(prompt: PairingPrompt, onRisposta: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onRisposta(false) },
        title = {
            Text(
                if (prompt.incoming) "${prompt.peerName} vuole associarsi"
                else "Associazione con ${prompt.peerName}"
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    prompt.code,
                    style = MaterialTheme.typography.displaySmall,
                    letterSpacing = 8.sp,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Guarda l'altro dispositivo: compare lo stesso numero? " +
                        "Conferma solo se coincide.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRisposta(true) }) { Text("Sì, coincide") }
        },
        dismissButton = {
            TextButton(onClick = { onRisposta(false) }) {
                Text("No, annulla", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}

@Composable
private fun InserimentoManualeDialog(
    onConferma: (String, Int) -> Unit,
    onAnnulla: () -> Unit
) {
    var host by remember { mutableStateOf("") }
    var porta by remember { mutableStateOf(SYNC_PORT.toString()) }

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text("Aggiungi un indirizzo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Serve quando la rete non lascia passare la ricerca automatica. " +
                        "L'indirizzo è quello dell'altro dispositivo.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Indirizzo") },
                    placeholder = { Text("192.168.1.10") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = porta,
                    onValueChange = { porta = it.filter(Char::isDigit) },
                    label = { Text("Porta") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConferma(host, porta.toIntOrNull() ?: 0) },
                enabled = host.isNotBlank()
            ) {
                Text("Aggiungi")
            }
        },
        dismissButton = { TextButton(onClick = onAnnulla) { Text("Annulla") } }
    )
}

@Composable
private fun StatoRiepilogo(host: String?, porta: Int?, ultimoSync: Long?, messaggio: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (porta != null) {
                Text(
                    if (host != null) "Raggiungibile su $host:$porta" else "In ascolto sulla porta $porta",
                    style = MaterialTheme.typography.titleSmall
                )
                // È l'unico posto in cui l'utente può leggere il proprio indirizzo: il modulo di
                // inserimento manuale chiede quello dell'**altro** dispositivo, e senza questa
                // riga bisognerebbe andarlo a cercare nelle impostazioni di sistema.
                Text(
                    "Se i due dispositivi non si trovano da soli, digita questo indirizzo " +
                        "sull'altro.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Questo dispositivo non è in ascolto: è lui a contattare l'altro.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                ultimoSync?.let { "Ultima sincronizzazione: ${formatDateTime(it)}" }
                    ?: "Mai sincronizzato.",
                style = MaterialTheme.typography.bodyMedium
            )
            messaggio?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PeerAssociato(peer: PairedPeer, onDissocia: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text(peer.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (peer.lastContactAt > 0) "Allineato al ${formatDateTime(peer.lastContactAt)}"
                    else "Non ancora allineato",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = onDissocia) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Dissocia",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun PeerDaAssociare(peer: DiscoveredPeer, onAssocia: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text(peer.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (peer.source == PeerSource.MANUAL) "Indirizzo inserito a mano"
                    else "${peer.host}:${peer.port}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onAssocia) { Text("Associa") }
        }
    }
}

@Composable
private fun Intestazione(testo: String) {
    Text(
        testo,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun Spiegazione(testo: String, errore: Boolean = false) {
    Text(
        testo,
        style = MaterialTheme.typography.bodyMedium,
        color = if (errore) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

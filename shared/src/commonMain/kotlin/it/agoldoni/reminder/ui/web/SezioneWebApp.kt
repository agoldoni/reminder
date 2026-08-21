package it.agoldoni.reminder.ui.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import it.agoldoni.reminder.platform.LocalAppContainer
import it.agoldoni.reminder.platform.sistemaConfermaLaCopia
import it.agoldoni.reminder.ui.icons.CopyIcon

/**
 * La web app locale, come sezione della schermata Sincronizzazione.
 *
 * Sta qui e non in una schermata propria perché è la stessa materia — questo dispositivo, la rete
 * locale, chi lo può raggiungere — e perché la barra della vista principale ha già quattro icone.
 *
 * **Non avvia né ferma il server con un `DisposableEffect`**, a differenza di ciò che la schermata
 * fa per la ricerca dei dispositivi: la porta deve restare aperta quando l'utente torna alla
 * lista, altrimenti la pagina morirebbe appena si esce di qui. È l'Activity a decidere, non
 * questa schermata.
 */
@Composable
fun SezioneWebApp(
    onMessaggio: (String) -> Unit,
    viewModel: WebViewModel = viewModel(factory = LocalAppContainer.current.viewModelFactory)
) {
    if (!viewModel.supported) return

    val stato by viewModel.status.collectAsState()
    val appunti = LocalClipboardManager.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                // Senza spazio esplicito la seconda riga del sottotitolo arriva a filo
                // dell'interruttore e sembra passarci sotto.
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Consulta dal browser", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Apre una pagina di sola lettura per chi è sulla stessa rete.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = stato.enabled,
                    onCheckedChange = { acceso -> if (acceso) viewModel.accendi() else viewModel.spegni() }
                )
            }

            val url = stato.url
            when {
                !stato.enabled -> Text(
                    "Spenta: nessuno può raggiungere i promemoria da questo dispositivo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                url != null -> {
                    val copia = {
                        appunti.setText(AnnotatedString(url))
                        // Da Android 13 il sistema apre già la sua anteprima con il testo
                        // copiato: aggiungerci il nostro messaggio direbbe due volte la stessa
                        // cosa, una sopra l'altra.
                        if (!sistemaConfermaLaCopia()) onMessaggio("Indirizzo copiato negli appunti.")
                    }
                    // Tutta la riga copia, non solo l'icona: su un telefono l'indirizzo è il
                    // bersaglio grande e ovvio, e ridigitarlo a mano sull'altro dispositivo è
                    // proprio la fatica che questa riga esiste per togliere.
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(onClickLabel = "Copia l'indirizzo", onClick = copia)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            url,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = copia) {
                            Icon(CopyIcon, contentDescription = "Copia l'indirizzo")
                        }
                    }
                    // Il codice in fondo all'indirizzo è la sola cosa che tiene fuori gli altri:
                    // va detto, o verrà copiato via senza pensarci.
                    Text(
                        "Tocca per copiarlo. Serve per intero, codice compreso: senza quello la " +
                            "pagina non si apre. Il codice cambia ogni volta che riaccendi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Acceso ma senza indirizzo: o la porta non si è aperta, o non c'è una rete locale.
                stato.lastMessage != null -> Text(
                    stato.lastMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )

                else -> Text(
                    "Nessun indirizzo di rete locale: collega il dispositivo a una rete Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (stato.enabled) {
                Text(
                    "Resta aperta anche a app chiusa: una notifica fissa te lo ricorda, e da lì " +
                        "puoi spegnerla.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

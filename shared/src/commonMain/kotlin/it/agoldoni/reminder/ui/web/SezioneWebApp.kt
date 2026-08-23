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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
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
                        "Apre una pagina di sola lettura per chi è sulla stessa rete. " +
                            "Da un secondo indirizzo si possono anche modificare.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = stato.enabled,
                    onCheckedChange = { acceso -> if (acceso) viewModel.accendi() else viewModel.spegni() }
                )
            }

            val url = stato.urlLettura
            when {
                !stato.enabled -> Text(
                    "Spenta: nessuno può raggiungere i promemoria da questo dispositivo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                url != null -> {
                    RigaIndirizzo(url) { copiato ->
                        appunti.setText(AnnotatedString(copiato))
                        // Da Android 13 il sistema apre già la sua anteprima con il testo
                        // copiato: aggiungerci il nostro messaggio direbbe due volte la stessa
                        // cosa, una sopra l'altra.
                        if (!sistemaConfermaLaCopia()) {
                            onMessaggio("Indirizzo di sola lettura copiato negli appunti.")
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

                    stato.urlScrittura?.let { completo ->
                        IndirizzoCompleto(completo) { copiato ->
                            appunti.setText(AnnotatedString(copiato))
                            if (!sistemaConfermaLaCopia()) {
                                onMessaggio("Indirizzo con le modifiche copiato negli appunti.")
                            }
                        }
                    }

                    // **L'avviso va anticipato qui.** Comparirà di sicuro — il certificato è
                    // generato dal telefono e nessun browser lo conosce — e chi non se lo aspetta
                    // pensa che l'app sia rotta e torna indietro proprio quando manca un tocco.
                    Text(
                        "La prima volta il browser dirà che la connessione non è privata: è " +
                            "previsto. Il certificato lo genera questo telefono, non un'autorità " +
                            "che il browser conosca. Prosegui e la pagina si apre.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    stato.impronta?.let { impronta -> Impronta(impronta) }
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
                    "Resta aperta anche a app chiusa, per guardare e per modificare: una " +
                        "notifica fissa te lo ricorda, e da lì puoi spegnerla.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Una riga d'indirizzo: si copia toccandola.
 *
 * Tutta la riga copia, non solo l'icona: su un telefono l'indirizzo è il bersaglio grande e ovvio,
 * e ridigitarlo a mano sull'altro dispositivo è proprio la fatica che questa riga esiste per
 * togliere.
 */
@Composable
private fun RigaIndirizzo(url: String, onCopia: (String) -> Unit) {
    val copia = { onCopia(url) }
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
}

/**
 * L'indirizzo che permette anche di modificare, dietro un tocco.
 *
 * **Perché non è affiancato all'altro.** I due indirizzi differiscono solo negli otto caratteri
 * finali: uno sotto l'altro, con l'ellissi che ne taglia la coda, sono due stringhe
 * indistinguibili a colpo d'occhio. E i due errori possibili non pesano uguale — copiare questo
 * credendo di copiare quello di lettura regala il telecomando e **non dà nessun segnale**, mentre
 * l'errore opposto si scopre in tre secondi perché la pagina non ha i comandi. Il gesto che
 * sbaglia in silenzio è l'unico che richiede un tocco in più.
 *
 * **Perché non ordinati per frequenza d'uso**, che sarebbe stata la scelta ovvia: entrambi si
 * copiano di rado, perché il codice cambia solo alla riaccensione e l'interruttore resta acceso.
 *
 * La forma è la stessa di [Impronta], che sta qui sotto per la stessa ragione: non fare rumore a
 * chi non sta cercando quella cosa lì.
 */
@Composable
private fun IndirizzoCompleto(url: String, onCopia: (String) -> Unit) {
    var aperto by remember { mutableStateOf(false) }

    Text(
        if (aperto) "Nascondi l'indirizzo con le modifiche" else "Serve anche modificare dal browser?",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable { aperto = !aperto }
            .padding(vertical = 4.dp)
    )

    if (aperto) {
        RigaIndirizzo(url, onCopia)
        Text(
            "Con questo indirizzo si creano, si modificano e si completano i promemoria. " +
                "Tienilo per te: a chi deve solo guardare dài l'altro.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * L'impronta del certificato, nascosta finché non la si chiede.
 *
 * **Perché nascosta.** Sono trentadue coppie esadecimali: in mezzo all'indirizzo sarebbero rumore
 * per chi vuole solo aprire la pagina, e la maggior parte delle volte è ciò che si vuole.
 *
 * **Perché c'è.** Scavalcando l'avviso del browser si accetta *qualunque* certificato, quindi si
 * ottiene una connessione cifrata ma non la certezza di parlare con questo telefono: chi si
 * mettesse in mezzo sulla rete potrebbe presentarne uno suo. Confrontare questa impronta con
 * quella che il browser mostra nei dettagli del certificato è ciò che chiude quel buco — una
 * volta sola, e solo per chi ci tiene. È lo stesso confronto a vista con cui l'app fa associare
 * due dispositivi nella schermata di sincronizzazione.
 */
@Composable
private fun Impronta(impronta: String) {
    var aperta by remember { mutableStateOf(false) }

    Text(
        if (aperta) "Nascondi l'impronta del certificato" else "Verifica il certificato",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable { aperta = !aperta }
            .padding(vertical = 4.dp)
    )

    if (aperta) {
        Text(
            impronta,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Nel browser, apri i dettagli del certificato e confronta questa sequenza. Se " +
                "coincide, stai parlando con questo telefono e con nessun altro.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

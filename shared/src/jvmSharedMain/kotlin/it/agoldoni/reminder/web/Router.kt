package it.agoldoni.reminder.web

/** Il percorso dei dati. Separato dagli asset perché è l'unico che tocca il database. */
private const val PERCORSO_EVENTI = "/api/eventi"

/** Nome del parametro che porta il token. Corto perché va digitato a mano. */
private const val PARAMETRO_TOKEN = "t"

/**
 * L'unico tipo di corpo che si accetta, e non è pignoleria.
 *
 * Un modulo HTML su una pagina qualsiasi può inviare solo `x-www-form-urlencoded`,
 * `multipart/form-data` o `text/plain`: pretendere `application/json` fa scattare il preflight
 * CORS, che una pagina cross-origin non supera. È la difesa dal CSRF più economica che esista —
 * una riga — e sta sopra a quella vera, che è il token.
 */
private const val TIPO_RICHIESTO = "application/json"

/**
 * Che cosa ha scritto l'ultima operazione: `<id>:<updatedAt>`.
 *
 * Sta in un header e non nel corpo perché nel corpo cambierebbe l'impronta, e chi ha appena
 * salvato non riceverebbe più `304` al controllo successivo. Serve al browser per annullare una
 * completazione: l'evento completato esce dalla lista degli aperti, quindi il suo `updatedAt`
 * nuovo non c'è da nessun'altra parte, e senza quello il controllo ottimistico non ha che cosa
 * dichiarare.
 */
private const val HEADER_SCRITTO = "X-Promemoria-Scritto"

/**
 * Smista le richieste e applica il controllo d'accesso.
 *
 * Lo smistamento è **prima per metodo e poi per percorso**, e non il contrario: è la struttura che
 * la feature 002 aveva scelto prevedendo le scritture, e infatti aggiungerle ha voluto dire
 * aggiungere due rami invece di riscrivere.
 *
 * **L'ordine dei controlli d'accesso non è indifferente.** Prima il token, poi il livello, e solo
 * alla fine se l'app è aperta. Guardare per ultimo il token direbbe a uno sconosciuto — che non ha
 * nessuna credenziale — se il telefono è in uso in questo momento.
 */
internal class Router(
    private val scritture: ScrittureWeb,
    private val token: AccessToken,
    /** Il DAO serve solo a comporre la rappresentazione; chi scrive è [scritture]. */
    private val dao: it.agoldoni.reminder.data.EventDao,
    /** Se l'app è davanti all'utente adesso. Le scritture funzionano solo mentre lo è. */
    private val appDavanti: () -> Boolean
) {

    suspend fun gestisci(request: HttpRequest, provenienza: String): HttpResponse {
        val accesso = token.verifica(request.query[PARAMETRO_TOKEN], provenienza)
        return when (request.method) {
            "GET" -> get(request, accesso)
            "POST" -> post(request, accesso)
            "PUT" -> put(request, accesso)
            else -> metodoSbagliato(request.path)
        }
    }

    // --- Lettura ------------------------------------------------------------------------------

    private suspend fun get(request: HttpRequest, accesso: Accesso): HttpResponse {
        if (request.path == PERCORSO_EVENTI) {
            if (!accesso.puoLeggere) return negato()
            return eventi(request, accesso)
        }
        val asset = StaticAssets.asset(request.path) ?: return HttpResponse.vuota(404)
        if (asset.tokenRichiesto && !accesso.puoLeggere) return negato()
        val contenuto = StaticAssets.contenuto(asset)
            // Dichiarato nell'elenco ma assente dall'artefatto: è un difetto di confezionamento,
            // non una richiesta sbagliata, e dirlo `404` manderebbe a cercare dalla parte opposta.
            ?: return HttpResponse.vuota(500)
        return HttpResponse(200, asset.contentType, contenuto)
    }

    private suspend fun eventi(request: HttpRequest, accesso: Accesso): HttpResponse {
        val corpo = rappresentazione(accesso)
        // Si aggiorna solo se qualcosa è cambiato: a impronta uguale il browser tiene quel che ha
        // e la pagina non si ridisegna, quindi non perde la posizione di scorrimento.
        if (request.header("if-none-match") == corpo.etag) {
            return HttpResponse.vuota(304, mapOf("ETag" to corpo.etag))
        }
        return HttpResponse(200, "application/json; charset=utf-8", corpo.bytes, mapOf("ETag" to corpo.etag))
    }

    // --- Scrittura ----------------------------------------------------------------------------

    private suspend fun post(request: HttpRequest, accesso: Accesso): HttpResponse {
        if (request.path != PERCORSO_EVENTI) {
            return if (StaticAssets.asset(request.path) != null || request.path.startsWith("$PERCORSO_EVENTI/")) {
                metodoSbagliato(request.path)
            } else {
                HttpResponse.vuota(404)
            }
        }
        return scrivi(request, accesso) { scritture.crea(request.body) }
    }

    private suspend fun put(request: HttpRequest, accesso: Accesso): HttpResponse {
        val id = idDaPercorso(request.path) ?: return when {
            request.path == PERCORSO_EVENTI -> metodoSbagliato(request.path)
            else -> HttpResponse.vuota(404)
        }
        return scrivi(request, accesso) { scritture.modifica(id, request.body) }
    }

    /**
     * Il guscio comune delle scritture: i controlli nell'ordine, l'esecuzione, e la risposta —
     * che è **sempre la lista intera**, cioè la stessa cosa che restituisce una `GET`.
     *
     * Rispondere con la lista risparmia un giro di rete a chi ha appena salvato, e siccome porta
     * anche l'impronta nuova, la richiesta condizionale successiva riceve un `304` da sé.
     * Vale anche per il `409`: chi ha perso il confronto deve vedere **il valore vero**, e ce l'ha
     * già nella risposta che gli dice di aver perso.
     */
    private suspend fun scrivi(
        request: HttpRequest,
        accesso: Accesso,
        azione: suspend () -> EsitoScrittura
    ): HttpResponse {
        if (!accesso.puoScrivere) return negato()
        // Dopo il token, mai prima: vedi il commento in testa alla classe.
        if (!appDavanti()) return appNonDavanti()
        if (!tipoAmmesso(request)) return HttpResponse.vuota(415)

        return when (val esito = azione()) {
            is EsitoScrittura.Fatta -> conLaLista(
                status = if (esito.creato) 201 else 200,
                accesso = accesso,
                extra = mapOf(HEADER_SCRITTO to "${esito.id}:${esito.updatedAt}")
            )

            is EsitoScrittura.NonLeggibile -> HttpResponse.vuota(400)
            is EsitoScrittura.NonValida -> HttpResponse.testo(422, "${esito.campo}: ${esito.motivo}")
            EsitoScrittura.Assente -> HttpResponse.vuota(404)
            EsitoScrittura.Conflitto -> conLaLista(409, accesso)
        }
    }

    private suspend fun conLaLista(
        status: Int,
        accesso: Accesso,
        extra: Map<String, String> = emptyMap()
    ): HttpResponse {
        val corpo = rappresentazione(accesso)
        return HttpResponse(
            status,
            "application/json; charset=utf-8",
            corpo.bytes,
            extra + ("ETag" to corpo.etag)
        )
    }

    private suspend fun rappresentazione(accesso: Accesso): CorpoJson = corpoEventi(
        dao = dao,
        permessi = if (accesso.puoScrivere) PermessiWeb.SCRITTURA else PermessiWeb.LETTURA,
        // Solo chi può scrivere ha motivo di sapere se in questo momento può farlo.
        scritturaDisponibile = accesso.puoScrivere && appDavanti()
    )

    /**
     * `/api/eventi/12` → `12`. Un segmento che non è un numero non è una risorsa che esiste, quindi
     * `404` e non `400`: al server non interessa perché il client l'abbia scritto così.
     */
    private fun idDaPercorso(percorso: String): Long? =
        percorso.removePrefix("$PERCORSO_EVENTI/")
            .takeIf { percorso.startsWith("$PERCORSO_EVENTI/") && it.isNotEmpty() }
            ?.toLongOrNull()

    private fun tipoAmmesso(request: HttpRequest): Boolean {
        // `application/json; charset=utf-8` è lecito: si guarda il tipo, non i parametri.
        val tipo = request.header("content-type")?.substringBefore(';')?.trim()?.lowercase()
        return tipo == TIPO_RICHIESTO
    }

    /** I metodi ammessi su un percorso, per l'header `Allow` — che senza sarebbe un `405` muto. */
    private fun metodoSbagliato(percorso: String): HttpResponse {
        val ammessi = when {
            percorso == PERCORSO_EVENTI -> "GET, POST"
            idDaPercorso(percorso) != null -> "PUT"
            StaticAssets.asset(percorso) != null -> "GET"
            else -> return HttpResponse.vuota(404)
        }
        return HttpResponse.vuota(405, mapOf("Allow" to ammessi))
    }

    /**
     * Una risposta sola per «token assente», «token sbagliato», «hai tentato troppe volte» e
     * «questo è l'indirizzo di sola lettura». Distinguerle non servirebbe all'utente e servirebbe
     * a chi sonda: saprebbe quando ha indovinato la forma giusta e quando è stato messo in pausa.
     *
     * La pagina distingue lo stesso i due casi che le servono, ma dal **suo** lato: sa che cosa
     * stava facendo quando ha ricevuto il rifiuto.
     */
    private fun negato() = HttpResponse.vuota(403)

    /**
     * Qui invece si dice perché, e non è incoerente con [negato]. Chi arriva fin qui ha **già
     * dimostrato** di avere il token di scrittura: tacere non lo tiene fuori da niente, lo lascia
     * solo davanti a un rifiuto senza spiegazione.
     */
    private fun appNonDavanti() =
        HttpResponse.testo(503, "Apri l'app sul telefono per modificare i promemoria.")
}

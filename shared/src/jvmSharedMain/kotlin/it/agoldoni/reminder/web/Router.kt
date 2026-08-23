package it.agoldoni.reminder.web

/** Il percorso dei dati. Separato dagli asset perché è l'unico che tocca il database. */
private const val PERCORSO_EVENTI = "/api/eventi"

/**
 * Lo schema con cui la credenziale viaggia: `Authorization: Bearer <jwt>`.
 *
 * **Non è più un parametro di query**, e non c'è un periodo di grazia: `?t=` non autentica più.
 * Due modi di presentare la stessa credenziale sono uno di troppo — e nessun client vivo si rompe,
 * perché fino alla feature 005 i token morivano comunque a ogni riavvio del processo.
 *
 * Il confronto è **senza distinzione di maiuscole**, come vuole la RFC 7235: `bearer xyz` è lecito
 * quanto `Bearer xyz`, e rifiutarlo sarebbe un difetto che si manifesta solo con certi client.
 */
private const val SCHEMA_BEARER = "bearer"

/**
 * Il parametro che chiede di **restare in attesa** invece di rispondere subito.
 *
 * **È un interruttore e non una durata**, e la differenza non è di stile: un client che potesse
 * chiedere «aspetta dieci minuti» inchioderebbe per dieci minuti una coroutine e un socket, e ne
 * basterebbero otto per esaurire [ATTESE_MASSIME]. Quanto si aspetta lo decide il server.
 *
 * Su un percorso nuovo invece che su un parametro si è scelto il parametro: la risorsa è la stessa,
 * cambia solo la consegna — e un percorso in più andrebbe aggiunto anche alla tabella `Allow` di
 * [metodoSbagliato] e ragionato contro `StaticAssets`.
 */
private const val PARAMETRO_ATTESA = "attendi"
private const val VALORE_ATTESA = "1"

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
 * **Si scrive tutte le volte che la porta è aperta**, anche ad app chiusa. Una prima stesura lo
 * permetteva solo con l'app in primo piano, per prudenza e perché non si sapeva se scrivere ad app
 * chiusa funzionasse: la verifica sul dispositivo dice che funziona — database e sveglie, con
 * l'Activity distrutta e il processo tenuto vivo dal solo servizio in primo piano. Restare stretti
 * avrebbe voluto dire rifiutare qualcosa che funziona, cioè rispondere una bugia; e come difesa
 * quel cancello non fermava comunque chi ha il token e sceglie il momento.
 */
internal class Router(
    private val scritture: ScrittureWeb,
    private val token: AccessToken,
    /** Il DAO serve solo a comporre la rappresentazione; chi scrive è [scritture]. */
    private val dao: it.agoldoni.reminder.data.EventDao,
    /**
     * Il segnale di cambiamento. Il valore predefinito **non aspetta e non si sveglia mai**: chi
     * costruisce un `Router` senza saperne nulla ottiene esattamente il comportamento di prima di
     * questa feature.
     */
    private val cambiamenti: Cambiamenti = Cambiamenti.fermo
) {

    suspend fun gestisci(request: HttpRequest, provenienza: String): HttpResponse {
        val accesso = token.verifica(bearer(request), provenienza)
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
            // Su una lettura il rifiuto ha un significato solo — «non so chi sei» — perché
            // entrambi i permessi leggono: quindi è sempre un `401`, mai un `403`.
            if (!accesso.puoLeggere) return nonAutenticato()
            return eventi(request, accesso)
        }
        val asset = StaticAssets.asset(request.path) ?: return HttpResponse.vuota(404)
        if (asset.tokenRichiesto && !accesso.puoLeggere) return nonAutenticato()
        val contenuto = StaticAssets.contenuto(asset)
            // Dichiarato nell'elenco ma assente dall'artefatto: è un difetto di confezionamento,
            // non una richiesta sbagliata, e dirlo `404` manderebbe a cercare dalla parte opposta.
            ?: return HttpResponse.vuota(500)
        return HttpResponse(200, asset.contentType, contenuto)
    }

    /**
     * La lettura, in due modi che sono la stessa decisione presa in due momenti diversi.
     *
     * Senza `attendi` è quella di sempre: si compone, si confronta l'impronta, si risponde `200` o
     * `304`. Con `attendi`, se l'impronta coincide non si risponde `304` subito ma si aspetta che
     * cambi qualcosa — ed è tutta qui la differenza fra una pagina che scopre le modifiche entro
     * mezzo minuto e una che le vede arrivare.
     */
    private suspend fun eventi(request: HttpRequest, accesso: Accesso): HttpResponse {
        // **L'ordine di queste due righe è il cuore della correttezza dell'attesa.** Il contatore
        // si legge PRIMA di comporre il corpo: al contrario, una scrittura che cadesse in mezzo non
        // sveglierebbe nessuno. Il perché per esteso sta su `Cambiamenti.versione`.
        val visto = cambiamenti.versione
        val corpo = rappresentazione(accesso)
        val atteso = request.header("if-none-match")

        // Si aggiorna solo se qualcosa è cambiato: a impronta uguale il browser tiene quel che ha
        // e la pagina non si ridisegna, quindi non perde la posizione di scorrimento. Vale anche
        // per chi stava aspettando: se l'impronta è già diversa si era perso un giro, e la risposta
        // parte subito senza sospendere niente.
        if (corpo.etag != atteso) return conCorpo(200, corpo)

        // Chi non ha chiesto di aspettare riceve la risposta di sempre: è la compatibilità
        // all'indietro, e viene gratis perché un client vecchio semplicemente non manda il
        // parametro. Il timeout di lettura del socket non c'entra e non va toccato: quello è un
        // `soTimeout`, cioè un limite alla *lettura*, e a questo punto non si legge più niente.
        if (request.query[PARAMETRO_ATTESA] != VALORE_ATTESA) {
            return HttpResponse.vuota(304, mapOf("ETag" to corpo.etag))
        }

        // Si ricompone a ogni segnale e si riparte se l'impronta non è cambiata davvero: sulla
        // tabella `events` si scrive anche per cose che non toccano i promemoria aperti.
        val cambiato = cambiamenti.attendi(visto) {
            rappresentazione(accesso).takeIf { it.etag != atteso }
        }
        return if (cambiato != null) {
            conCorpo(200, cambiato)
        } else {
            HttpResponse.vuota(304, mapOf("ETag" to corpo.etag))
        }
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
        // **Qui i due rifiuti si separano**, ed è l'unico posto in cui succede. Chi non è
        // autenticato riceve `401`; chi lo è ma ha in mano l'indirizzo di sola lettura riceve
        // `403`. Il `403` non regala niente a chi sonda: lo vede solo chi ha già un token valido,
        // e gli dice una cosa che sa già.
        if (!accesso.puoScrivere) {
            return if (accesso.autenticato) soloLettura() else nonAutenticato()
        }
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
    ): HttpResponse = conCorpo(status, rappresentazione(accesso), extra)

    /** Un corpo già composto e la sua impronta: lettura e scritture escono tutte da qui. */
    private fun conCorpo(
        status: Int,
        corpo: CorpoJson,
        extra: Map<String, String> = emptyMap()
    ): HttpResponse = HttpResponse(
        status,
        "application/json; charset=utf-8",
        corpo.bytes,
        extra + ("ETag" to corpo.etag)
    )

    private suspend fun rappresentazione(accesso: Accesso): CorpoJson = corpoEventi(
        dao = dao,
        permessi = if (accesso.puoScrivere) PermessiWeb.SCRITTURA else PermessiWeb.LETTURA
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
     * La credenziale, dall'header. `null` se manca, se lo schema è un altro o se non c'è niente
     * dopo lo schema: al verificatore arriva sempre «un token o niente», mai un mezzo header.
     */
    private fun bearer(request: HttpRequest): String? {
        val pezzi = request.header("authorization")?.split(' ', limit = 2) ?: return null
        if (pezzi.size != 2 || !pezzi[0].equals(SCHEMA_BEARER, ignoreCase = true)) return null
        return pezzi[1].trim().takeIf { it.isNotEmpty() }
    }

    /**
     * «Non so chi sei»: credenziale assente, malformata, con la firma sbagliata, scaduta — oppure
     * respinta perché si è tentato troppe volte.
     *
     * **Le quattro condizioni restano indistinguibili sul filo**, ed è la regola della 002:
     * separarle non servirebbe all'utente e servirebbe a chi sonda, che saprebbe quando ha
     * indovinato la forma giusta e quando è stato messo in pausa.
     *
     * Niente `WWW-Authenticate`: non serve a un client nostro e allarga la superficie.
     */
    private fun nonAutenticato() = HttpResponse.vuota(401)

    /**
     * «So chi sei, e questo indirizzo permette solo di guardare.»
     *
     * È un codice diverso da [nonAutenticato] perché **il client deve poterli distinguere**: un
     * token respinto qui è un token buono, e buttarlo via — come è giusto fare con un `401` —
     * toglierebbe l'accesso a chi ha semplicemente toccato un pulsante che non doveva esserci.
     */
    private fun soloLettura() = HttpResponse.vuota(403)
}

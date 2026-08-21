package it.agoldoni.reminder.web

import it.agoldoni.reminder.data.EventDao

/** Il percorso dei dati. Separato dagli asset perché è l'unico che tocca il database. */
private const val PERCORSO_EVENTI = "/api/eventi"

/** Nome del parametro che porta il token. Corto perché va digitato a mano. */
private const val PARAMETRO_TOKEN = "t"

/**
 * Smista le richieste e applica il controllo d'accesso.
 *
 * Lo smistamento è **prima per metodo e poi per percorso**, e non il contrario, perché così
 * aggiungere le scritture — che è il seguito previsto di questa feature — vuol dire aggiungere un
 * ramo, non riscrivere la struttura. Oggi ogni metodo diverso da `GET` finisce in `405`.
 */
internal class Router(
    private val dao: EventDao,
    private val token: AccessToken
) {

    suspend fun gestisci(request: HttpRequest, provenienza: String): HttpResponse = when (request.method) {
        "GET" -> get(request, provenienza)
        else -> HttpResponse.vuota(405, mapOf("Allow" to "GET"))
    }

    private suspend fun get(request: HttpRequest, provenienza: String): HttpResponse {
        if (request.path == PERCORSO_EVENTI) {
            if (!autorizzato(request, provenienza)) return negato()
            return eventi(request)
        }
        val asset = StaticAssets.asset(request.path) ?: return HttpResponse.vuota(404)
        if (asset.tokenRichiesto && !autorizzato(request, provenienza)) return negato()
        val contenuto = StaticAssets.contenuto(asset)
            // Dichiarato nell'elenco ma assente dall'artefatto: è un difetto di confezionamento,
            // non una richiesta sbagliata, e dirlo `404` manderebbe a cercare dalla parte opposta.
            ?: return HttpResponse.vuota(500)
        return HttpResponse(200, asset.contentType, contenuto)
    }

    private suspend fun eventi(request: HttpRequest): HttpResponse {
        val corpo = corpoEventi(dao)
        // Si aggiorna solo se qualcosa è cambiato: a impronta uguale il browser tiene quel che ha
        // e la pagina non si ridisegna, quindi non perde la posizione di scorrimento.
        if (request.header("if-none-match") == corpo.etag) {
            return HttpResponse.vuota(304, mapOf("ETag" to corpo.etag))
        }
        return HttpResponse(200, "application/json; charset=utf-8", corpo.bytes, mapOf("ETag" to corpo.etag))
    }

    private fun autorizzato(request: HttpRequest, provenienza: String) =
        token.verifica(request.query[PARAMETRO_TOKEN], provenienza) == Accesso.CONSENTITO

    /**
     * Una risposta sola per «token assente», «token sbagliato» e «hai tentato troppe volte».
     * Distinguerle non servirebbe all'utente e servirebbe a chi sonda: saprebbe quando ha
     * indovinato la forma giusta e quando è stato messo in pausa.
     */
    private fun negato() = HttpResponse.vuota(403)
}

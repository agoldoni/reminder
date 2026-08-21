package it.agoldoni.reminder.web

import java.util.concurrent.ConcurrentHashMap

/**
 * Un file servibile, con il suo tipo e la sua politica di accesso.
 *
 * [tokenRichiesto] sta qui e non nel router perché la politica di accesso è una proprietà della
 * risorsa: tenerla accanto al nome del file rende impossibile aggiungerne una nuova dimenticando
 * di decidere chi può leggerla.
 */
internal data class Asset(
    val risorsa: String,
    val contentType: String,
    val tokenRichiesto: Boolean
)

/**
 * I file della pagina, serviti da un **elenco chiuso**.
 *
 * È la differenza che conta rispetto a un server che mappa i percorsi sul filesystem: lì bisogna
 * *filtrare* i tentativi di risalita e sperare di averli previsti tutti; qui un percorso che non
 * è una chiave di questa mappa semplicemente non esiste, quindi la risalita non è respinta — è
 * impossibile. Il controllo sui `..` che fa il parser è una seconda rete, non la prima.
 *
 * Il token protegge `/` e i dati; non gli asset. CSS, JavaScript, manifest e icone non contengono
 * niente di personale, e pretendere il token anche su di essi complicherebbe le richieste che il
 * browser fa **fuori** dal contesto della pagina — il manifest e le icone che vi sono elencate.
 * Il prezzo, dichiarato: una richiesta senza token a `/app.css` rivela che il servizio è acceso.
 */
internal object StaticAssets {

    private val AMMESSI: Map<String, Asset> = mapOf(
        "/" to Asset("web/index.html", "text/html; charset=utf-8", tokenRichiesto = true),
        "/app.css" to Asset("web/app.css", "text/css; charset=utf-8", tokenRichiesto = false),
        "/app.js" to Asset("web/app.js", "text/javascript; charset=utf-8", tokenRichiesto = false),
        "/manifest.json" to Asset("web/manifest.json", "application/manifest+json", tokenRichiesto = false),
        "/icona-192.png" to Asset("web/icona-192.png", "image/png", tokenRichiesto = false),
        "/icona-512.png" to Asset("web/icona-512.png", "image/png", tokenRichiesto = false)
    )

    /** Letti una volta e tenuti: sono pochi kilobyte e non cambiano per tutta la vita del processo. */
    private val cache = ConcurrentHashMap<String, ByteArray>()

    fun asset(percorso: String): Asset? = AMMESSI[percorso]

    /** I percorsi serviti, per i test: l'elenco non deve poter divergere da quello vero. */
    val percorsi: Set<String> get() = AMMESSI.keys

    /**
     * Restituisce `null` se il file non è nell'artefatto. Non è un caso teorico: le risorse di
     * `jvmSharedMain` finiscono nel jar desktop ma **non** nell'APK, e un asset messo nel posto
     * sbagliato darebbe una pagina bianca senza dire perché. Stanno in `shared/src/webAssets/`,
     * dichiarata a entrambi i target in `build.gradle.kts`.
     */
    fun contenuto(asset: Asset): ByteArray? = cache[asset.risorsa] ?: run {
        val bytes = StaticAssets::class.java.classLoader
            ?.getResourceAsStream(asset.risorsa)
            ?.use { it.readBytes() }
        bytes?.also { cache[asset.risorsa] = it }
    }
}

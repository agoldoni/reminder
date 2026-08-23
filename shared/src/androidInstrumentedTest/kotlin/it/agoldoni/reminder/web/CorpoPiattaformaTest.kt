package it.agoldoni.reminder.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **Presidio di piattaforma per la lettura del corpo (feature 004).**
 *
 * Esiste per un difetto vero, trovato sul campo e non da un test: la prima stesura leggeva il
 * corpo con `InputStream.readNBytes`, che sulla JVM c'è da Java 9 — quindi tutti i test su desktop
 * passavano — ma su Android non è disponibile al `minSdk` 26 di questo progetto. Il guasto non era
 * nemmeno rumoroso: moriva dentro la coroutine che serve la connessione, il socket si chiudeva
 * senza rispondere, e dall'altra parte si vedeva una risposta vuota — `curl` diceva `000`, non un
 * codice di errore. La pagina sembrava funzionare in lettura e non salvava niente.
 *
 * È lo stesso genere di trappola di `KeyStore.getDefaultType()` nella feature 003, e la lezione è
 * la stessa: **ciò che tocca l'API della piattaforma va provato sulla piattaforma.** I test su
 * desktop non possono rispondere a questa domanda, per costruzione.
 *
 * Si prova `leggiRichiesta` per intero e non solo la funzione di lettura: il difetto stava
 * nell'anello, non nella singola chiamata, e un test sulla sola funzione avrebbe potuto passare
 * lasciando rotto il giro.
 */
@RunWith(AndroidJUnit4::class)
class CorpoPiattaformaTest {

    private fun richiesta(corpo: ByteArray, dichiarata: Int = corpo.size): InputStream {
        val testa = "POST /api/eventi?t=abc HTTP/1.1\r\n" +
            "Host: 10.0.2.15:9888\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: $dichiarata\r\n\r\n"
        return ByteArrayInputStream(testa.toByteArray(Charsets.ISO_8859_1) + corpo)
    }

    @Test
    fun un_corpo_dichiarato_si_legge_per_intero_su_questo_dispositivo() {
        val corpo = """{"titolo":"Dentista","dateTimeMillis":1790000000000,"advanceMinutes":15}"""
        val letta = assertIs<RichiestaLetta.Ok>(leggiRichiesta(richiesta(corpo.toByteArray())))

        assertEquals("POST", letta.request.method)
        assertEquals("/api/eventi", letta.request.path)
        assertEquals(corpo, letta.request.body)
    }

    @Test
    fun un_corpo_grande_si_legge_tutto_anche_a_letture_parziali() {
        // Su un socket `read` restituisce spesso meno byte di quanti ne siano stati chiesti, e il
        // corpo va ricomposto in più giri. Qui lo stream è in memoria, ma la dimensione mette alla
        // prova il ciclo invece della singola lettura.
        val testo = "a".repeat(Http.MAX_CORPO - 100)
        val corpo = """{"titolo":"x","descrizione":"$testo","dateTimeMillis":1,"advanceMinutes":0}"""
        val letta = assertIs<RichiestaLetta.Ok>(leggiRichiesta(richiesta(corpo.toByteArray())))
        assertEquals(corpo.length, letta.request.body.length)
    }

    @Test
    fun un_corpo_UTF8_si_decodifica_su_questo_dispositivo() {
        val corpo = """{"titolo":"Riunione col perché — è già lunedì ☕"}"""
        val bytes = corpo.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size > corpo.length, "il corpo di prova dev'essere multibyte")

        val letta = assertIs<RichiestaLetta.Ok>(leggiRichiesta(richiesta(bytes)))
        assertEquals(corpo, letta.request.body, "la lunghezza è in byte, il testo in caratteri")
    }

    @Test
    fun un_corpo_troncato_e_malformato_e_non_blocca() {
        // Il client ha chiuso a metà: deve diventare un esito, non un'attesa infinita.
        assertIs<RichiestaLetta.Malformata>(
            leggiRichiesta(richiesta("{}".toByteArray(), dichiarata = 500))
        )
    }

    @Test
    fun leggiEsattamente_non_dipende_da_API_recenti() {
        // La chiamata diretta, per avere il guasto col suo nome se un giorno qualcuno la
        // riscrivesse con un metodo che su questo livello di API non esiste.
        val sorgente = ByteArrayInputStream(ByteArray(1000) { (it % 251).toByte() })
        val letti = leggiEsattamente(sorgente, 1000)
        assertEquals(1000, letti.size)
        assertEquals(250.toByte(), letti[250])
    }
}

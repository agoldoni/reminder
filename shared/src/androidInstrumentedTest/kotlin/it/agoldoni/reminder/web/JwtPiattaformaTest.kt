package it.agoldoni.reminder.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **TC-21 della feature 006 — presidio del rischio R-07.**
 *
 * I token d'accesso alla web app poggiano su due cose che sul desktop ci sono di sicuro:
 * `javax.crypto.Mac` con `HmacSHA256` e **`java.util.Base64`**. La seconda è il motivo di questo
 * file: esiste dall'**API 26**, che è esattamente il `minSdk` di questo progetto. Sulla carta va
 * bene; ma è lo stesso genere di confine su cui il progetto si è già bruciato due volte —
 * `KeyStore.getDefaultType()` nella feature 003 e `InputStream.readNBytes` nella 004 — e in
 * entrambi i casi il guasto viveva **solo** dove i test unitari non arrivano.
 *
 * Se `Jwt` non funzionasse qui, il difetto non sarebbe nemmeno rumoroso: nessun token verificato,
 * ogni richiesta un `401`, e una pagina che dice «indirizzo non più valido» a un indirizzo appena
 * consegnato.
 *
 * Si prova anche la persistenza della chiave in una cartella vera di `filesDir`, perché è lì che
 * `WebService` la mette e perché [ChiaveFirma] stringe i permessi con `File.setReadable`, il cui
 * comportamento su Android non è quello di una `umask` qualsiasi.
 */
@RunWith(AndroidJUnit4::class)
class JwtPiattaformaTest {

    private val adesso = 1_700_000_000_000L

    private fun cartellaDiProva(nome: String): File {
        val contesto = InstrumentationRegistry.getInstrumentation().targetContext
        return File(contesto.filesDir, nome).also { it.deleteRecursively() }
    }

    @Test
    fun coniaEVerificaSulDispositivo() {
        val chiave = ByteArray(32) { it.toByte() }

        val coniato = Jwt.firma(PermessiWeb.SCRITTURA, chiave, adesso)

        // Tre segmenti base64url: se `Base64.getUrlEncoder()` non ci fosse, non si arriverebbe qui.
        val pezzi = coniato.token.split('.')
        assertEquals(3, pezzi.size, "un JWT ha tre segmenti: ${coniato.token}")
        assertTrue(
            pezzi.all { segmento -> segmento.all { it.isLetterOrDigit() || it == '-' || it == '_' } },
            "base64url non ha né `+` né `/` né `=`: ${coniato.token}"
        )

        val letto = assertNotNull(
            Jwt.verifica(coniato.token, chiave, adesso),
            "il token appena coniato non si verifica: su questo dispositivo la web app sarebbe muta"
        )
        assertEquals(PermessiWeb.SCRITTURA, letto.p)
        assertEquals(adesso + DURATA_ACCESSO_MILLIS, coniato.scadenzaMillis)
    }

    @Test
    fun unTokenManomessoNonEntraNemmenoQui() {
        val chiave = ByteArray(32) { it.toByte() }
        val pezzi = Jwt.firma(PermessiWeb.LETTURA, chiave, adesso).token.split('.')

        // La promozione da lettura a scrittura: il caso che conta.
        val promosso = Base64UrlDiProva.codifica(
            """{"v":1,"p":"scrittura","iat":1,"exp":99999999999}"""
        )
        assertNull(Jwt.verifica("${pezzi[0]}.$promosso.${pezzi[2]}", chiave, adesso))
        assertNull(Jwt.verifica("${pezzi[0]}.${pezzi[1]}.!!!", chiave, adesso))
        assertNull(Jwt.verifica("${pezzi[0]}.${pezzi[1]}", chiave, adesso))
    }

    @Test
    fun laChiaveSopravviveInFilesDir() {
        val cartella = cartellaDiProva("prova-firma")
        try {
            val prima = ChiaveFirma(cartella).caricaOCrea()
            val coniato = Jwt.firma(PermessiWeb.LETTURA, prima, adesso)

            // Un'istanza nuova: è ciò che succede quando Android uccide il processo e l'app riparte.
            val dopo = ChiaveFirma(cartella).caricaOCrea()

            assertTrue(prima contentEquals dopo, "la chiave deve sopravvivere al processo")
            assertNotNull(
                Jwt.verifica(coniato.token, dopo, adesso),
                "è questa la feature: il segnalibro non si rompe da solo"
            )

            val file = File(cartella, "firma.key")
            assertTrue(file.canRead(), "il proprietario deve poterla leggere")
            assertTrue(file.length() == 32L, "trentadue byte, o è un file rotto")
        } finally {
            cartella.deleteRecursively()
        }
    }
}

/** Base64url scritto qui e non preso da `Jwt`: il presidio non deve poggiare su ciò che verifica. */
private object Base64UrlDiProva {
    private const val ALFABETO = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun codifica(testo: String): String {
        val bytes = testo.encodeToByteArray()
        val uscita = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
            uscita.append(ALFABETO[b0 shr 2])
            uscita.append(ALFABETO[((b0 and 0x03) shl 4) or (b1 shr 4)])
            if (i + 1 < bytes.size) uscita.append(ALFABETO[((b1 and 0x0f) shl 2) or (b2 shr 6)])
            if (i + 2 < bytes.size) uscita.append(ALFABETO[b2 and 0x3f])
            i += 3
        }
        return uscita.toString()
    }
}

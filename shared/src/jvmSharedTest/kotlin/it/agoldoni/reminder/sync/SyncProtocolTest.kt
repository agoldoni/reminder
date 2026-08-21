package it.agoldoni.reminder.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Il formato sul filo è un contratto fra due versioni dell'app che possono essere installate in
 * momenti diversi. Gli altri test lo attraversano sempre in entrambi i sensi con lo stesso codice,
 * quindi un campo perso o rinominato passerebbe inosservato: qui si controlla il formato in sé.
 */
class SyncProtocolTest {

    private fun <T : SyncMessage> andataERitorno(messaggio: T): SyncMessage {
        val testo = protocolJson.encodeToString<SyncMessage>(messaggio)
        return protocolJson.decodeFromString<SyncMessage>(testo)
    }

    @Test
    fun `ogni messaggio sopravvive all'andata e ritorno`() {
        val messaggi = listOf(
            Hello(PROTOCOL_VERSION, "id-a", "Telefono", SyncIntent.PAIR, 47700),
            Hello(PROTOCOL_VERSION, "id-a", "Telefono", SyncIntent.SYNC, null),
            HelloAck(PROTOCOL_VERSION, "id-b", "Computer", 47700),
            Rejected("Dispositivo non associato."),
            PairBegin("aabbcc"),
            PairKey("ddeeff"),
            PairConfirm("001122"),
            PairDone,
            SessionBegin("0f0f"),
            SessionAccept("1a1a", "2b2b"),
            SessionConfirm("3c3c"),
            Pull(1_700_000_000_000L),
            Ack(3)
        )

        messaggi.forEach { assertEquals(it, andataERitorno(it), "perso qualcosa in $it") }
    }

    @Test
    fun `un evento attraversa il filo con tutti i suoi campi`() {
        val push = Push(
            events = listOf(
                SyncEvent(
                    uuid = "u-1",
                    title = "Dentista",
                    description = "Studio in centro",
                    dateTimeMillis = 1_800_000_000_000L,
                    advanceMinutes = 30,
                    completed = true,
                    updatedAt = 1_700_000_000_000L,
                    deleted = true,
                    deletedAt = 1_700_000_001_000L,
                    origin = "id-telefono"
                ),
                // Un tombstone essenziale: i campi opzionali devono poter mancare.
                SyncEvent(uuid = "u-2", title = "", dateTimeMillis = 0, updatedAt = 1)
            ),
            upTo = 1_700_000_002_000L
        )

        assertEquals(push, andataERitorno(push))
    }

    @Test
    fun `il tipo del messaggio viaggia in chiaro nel campo type`() {
        val testo = protocolJson.encodeToString<SyncMessage>(Pull(42))

        assertTrue("\"type\":\"pull\"" in testo, "il discriminatore fa parte del contratto: $testo")
        assertTrue("\"since\":42" in testo, testo)
    }

    @Test
    fun `un messaggio di un tipo sconosciuto viene rifiutato invece che ignorato`() {
        assertFailsWith<Exception> {
            protocolJson.decodeFromString<SyncMessage>("""{"type":"inventato","x":1}""")
        }
    }

    @Test
    fun `un messaggio troncato o non JSON viene rifiutato`() {
        assertFailsWith<Exception> {
            protocolJson.decodeFromString<SyncMessage>("""{"type":"pull",""")
        }
        assertFailsWith<Exception> { protocolJson.decodeFromString<SyncMessage>("non json") }
    }

    @Test
    fun `un campo obbligatorio mancante non passa come valore vuoto`() {
        // Senza `deviceId` il saluto non identifica nessuno: deve fallire, non arrivare con "".
        assertFailsWith<Exception> {
            protocolJson.decodeFromString<SyncMessage>(
                """{"type":"hello","protocolVersion":1,"displayName":"X","intent":"PAIR"}"""
            )
        }
    }

    /**
     * Un peer più recente potrebbe aggiungere campi. Riceverli non deve far cadere la connessione,
     * perché a decidere la compatibilità è il numero di versione, non la forma del messaggio.
     */
    @Test
    fun `i campi in più di una versione futura non fanno cadere il dialogo`() {
        val conExtra = """{"type":"ack","applied":2,"qualcosaDiNuovo":"x"}"""

        assertEquals(Ack(2), protocolJson.decodeFromString<SyncMessage>(conExtra))
    }
}

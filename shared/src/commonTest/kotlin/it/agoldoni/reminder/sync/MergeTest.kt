package it.agoldoni.reminder.sync

import it.agoldoni.reminder.data.EventEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MergeTest {

    private fun evento(
        id: Long = 1,
        uuid: String = "uuid-1",
        title: String = "Dentista",
        updatedAt: Long,
        completed: Boolean = false,
        deleted: Boolean = false,
        origin: String = "id-telefono"
    ) = EventEntity(
        id = id,
        title = title,
        dateTimeMillis = 1_800_000_000_000L,
        advanceMinutes = 15,
        completed = completed,
        uuid = uuid,
        updatedAt = updatedAt,
        deleted = deleted,
        deletedAt = if (deleted) updatedAt else null,
        origin = origin
    )

    @Test
    fun `un evento mai visto entra come riga nuova`() {
        val decisione = resolveMerge(local = null, remote = evento(id = 42, updatedAt = 100))

        val inserito = assertIs<MergeDecision.Insert>(decisione).event
        assertEquals(0L, inserito.id, "l'id del mittente non ha senso qui: lo assegna il database")
        assertEquals("uuid-1", inserito.uuid, "l'identità globale invece si conserva")
        assertEquals(100L, inserito.updatedAt)
    }

    @Test
    fun `il più recente vince e conserva l'id locale`() {
        val decisione = resolveMerge(
            local = evento(id = 7, updatedAt = 100, title = "Vecchio"),
            remote = evento(id = 99, updatedAt = 200, title = "Nuovo")
        )

        val aggiornato = assertIs<MergeDecision.Update>(decisione).event
        assertEquals("Nuovo", aggiornato.title)
        assertEquals(7L, aggiornato.id, "cambiare l'id romperebbe gli allarmi già programmati")
    }

    @Test
    fun `un remoto più vecchio non sovrascrive il locale`() {
        val decisione = resolveMerge(
            local = evento(updatedAt = 200, title = "Nuovo"),
            remote = evento(updatedAt = 100, title = "Vecchio")
        )

        assertEquals(MergeDecision.Keep, decisione)
    }

    @Test
    fun `applicare due volte lo stesso evento non cambia nulla la seconda volta`() {
        val identico = evento(updatedAt = 100)

        assertEquals(MergeDecision.Keep, resolveMerge(local = identico, remote = identico))
    }

    @Test
    fun `a parità di istante i due dispositivi scelgono lo stesso vincitore`() {
        val diUno = evento(id = 3, updatedAt = 500, title = "Aaa")
        val diAltro = evento(id = 9, updatedAt = 500, title = "Zzz")

        // Stesso conflitto guardato dai due lati: il vincitore deve essere lo stesso evento,
        // altrimenti i due database divergono e restano divergenti.
        val suPrimo = resolveMerge(local = diUno, remote = diAltro)
        val suSecondo = resolveMerge(local = diAltro, remote = diUno)

        val vincitoreSuPrimo = (suPrimo as? MergeDecision.Update)?.event?.title ?: diUno.title
        val vincitoreSuSecondo = (suSecondo as? MergeDecision.Update)?.event?.title ?: diAltro.title
        assertEquals(vincitoreSuPrimo, vincitoreSuSecondo)
        assertEquals("Zzz", vincitoreSuPrimo)
    }

    @Test
    fun `una cancellazione più recente vince sui dati`() {
        val decisione = resolveMerge(
            local = evento(updatedAt = 100, title = "Ancora qui"),
            remote = evento(updatedAt = 200, deleted = true)
        )

        val aggiornato = assertIs<MergeDecision.Update>(decisione).event
        assertTrue(aggiornato.deleted)
        assertEquals(200L, aggiornato.deletedAt)
    }

    @Test
    fun `una modifica più vecchia non resuscita un evento cancellato`() {
        val decisione = resolveMerge(
            local = evento(updatedAt = 200, deleted = true),
            remote = evento(updatedAt = 100, title = "Modificato prima della cancellazione")
        )

        assertEquals(MergeDecision.Keep, decisione, "il tombstone deve tenere il campo")
    }

    @Test
    fun `andata e ritorno sul filo non perde nessun campo`() {
        val originale = evento(id = 5, updatedAt = 300, completed = true, deleted = true)

        val tornato = originale.toSyncEvent().toEntity()

        assertEquals(originale.copy(id = 0), tornato, "solo l'id locale non attraversa la rete")
    }
}

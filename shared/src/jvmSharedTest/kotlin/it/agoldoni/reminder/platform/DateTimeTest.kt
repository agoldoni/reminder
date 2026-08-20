package it.agoldoni.reminder.platform

import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DateTimeTest {

    private fun millisOf(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private val riferimento = millisOf(2026, 8, 20, 19, 4)

    @Test
    fun `le tre maschere seguono il formato italiano`() {
        assertEquals("20/08/2026 19:04", formatDateTime(riferimento))
        assertEquals("20/08/2026", formatDate(riferimento))
        assertEquals("19:04", formatTime(riferimento))
        assertEquals("20260820", formatFileDate(riferimento))
    }

    @Test
    fun `withTime cambia solo ora e minuti e azzera i secondi`() {
        val cambiato = withTime(riferimento, hour = 7, minute = 30)

        assertEquals("20/08/2026", formatDate(cambiato))
        assertEquals("07:30", formatTime(cambiato))
        assertEquals(0, Calendar.getInstance().apply { timeInMillis = cambiato }.get(Calendar.SECOND))
    }

    @Test
    fun `withDateFrom prende il giorno dalla sorgente e conserva l'ora`() {
        val sorgente = millisOf(2027, 1, 3, 23, 59)

        val unito = withDateFrom(riferimento, sorgente)

        assertEquals("03/01/2027", formatDate(unito))
        assertEquals("19:04", formatTime(unito), "l'ora deve restare quella di partenza")
    }

    @Test
    fun `hourOf e minuteOf leggono l'orario`() {
        assertEquals(19, hourOf(riferimento))
        assertEquals(4, minuteOf(riferimento))
    }

    @Test
    fun `startOfToday e la mezzanotte di oggi`() {
        val inizio = startOfToday()

        assertEquals("00:00", formatTime(inizio))
        assertEquals(formatDate(nowMillis()), formatDate(inizio))
        assertTrue(inizio <= nowMillis(), "la mezzanotte di oggi non può essere nel futuro")
    }
}

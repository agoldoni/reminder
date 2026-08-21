package it.agoldoni.reminder.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleInstanceTest {

    private val port = 47999

    @Test
    fun `la seconda istanza non parte e chiede alla prima di mostrarsi`() {
        val showRequested = CountDownLatch(1)
        val first = SingleInstance(port)
        val second = SingleInstance(port)

        try {
            assertTrue(first.acquire { showRequested.countDown() }, "la prima istanza deve acquisire")

            assertFalse(second.acquire { }, "la seconda istanza non deve acquisire")
            assertTrue(
                showRequested.await(5, TimeUnit.SECONDS),
                "la prima istanza deve ricevere la richiesta di mostrarsi"
            )
        } finally {
            first.release()
            second.release()
        }
    }

    @Test
    fun `dopo release la porta torna disponibile`() {
        val first = SingleInstance(port)
        assertTrue(first.acquire { })
        first.release()

        val second = SingleInstance(port)
        try {
            assertTrue(second.acquire { }, "rilasciata la porta, una nuova istanza deve poter partire")
        } finally {
            second.release()
        }
    }
}

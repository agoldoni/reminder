package it.agoldoni.reminder.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L'indirizzo mostrato nella schermata di sincronizzazione è quello che l'utente deve digitare
 * sull'altro dispositivo. Su Android si ricava aprendo un socket UDP «connesso», e vale la pena
 * verificarlo **sul device**: è l'unico posto dove potrebbero intromettersi le policy di rete del
 * sistema, e un fallimento silenzioso lascerebbe l'utente senza l'indirizzo proprio quando la
 * ricerca automatica non passa e quel valore è l'unica strada.
 */
@RunWith(AndroidJUnit4::class)
class LocalAddressTest {

    @Test
    fun indirizzoLocaleUsabilePerIlCollegamentoManuale() {
        val indirizzo = siteAddress()

        assertTrue(indirizzo is Inet4Address, "deve essere digitabile: $indirizzo")
        assertFalse(indirizzo.isLoopbackAddress, "loopback non serve all'altro dispositivo")
        assertFalse(indirizzo.isAnyLocalAddress, "0.0.0.0 non è un indirizzo a cui connettersi")
        println("indirizzo locale: ${indirizzo.hostAddress}")
    }
}

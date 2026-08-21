package it.agoldoni.reminder.sync

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * L'indirizzo con cui questa macchina esce verso la rete locale.
 *
 * Lo si chiede al sistema aprendo un socket UDP «connesso» — che non manda nulla, ma costringe il
 * kernel a scegliere la rotta e quindi l'interfaccia. È l'unico modo affidabile: scandire le
 * interfacce e prendere la prima attiva sembra equivalente e non lo è, perché su una macchina con
 * Docker o con dei bridge la prima è `docker0`. `NetworkInterface.isVirtual()` non aiuta a
 * escluderle: è vero solo per le sottointerfacce tipo `eth0:1`, non per i bridge. Legarsi a quella
 * sbagliata significa annunciarsi su una rete dove non c'è nessuno.
 *
 * La scansione resta come ripiego per la macchina senza rotta di default, dove non c'è comunque
 * nessuno da trovare.
 */
internal fun siteAddress(): InetAddress =
    runCatching {
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress(ROUTE_PROBE, 9))
            socket.localAddress.takeIf { it is Inet4Address && !it.isAnyLocalAddress }
        }
    }.getOrNull()
        ?: NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLinkLocalAddress }
        ?: InetAddress.getLocalHost()

/** TEST-NET-1 (RFC 5737): non esiste e non viene contattato, serve solo a far scegliere la rotta. */
private const val ROUTE_PROBE = "192.0.2.1"

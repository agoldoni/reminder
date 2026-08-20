package it.agoldoni.reminder.desktop

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Istanza singola tramite socket sul loopback: la prima istanza tiene la porta, le successive
 * si limitano a bussare — chiedendo di mostrare la finestra esistente — e terminano.
 */
class SingleInstance(private val port: Int = DEFAULT_PORT) {

    private var server: ServerSocket? = null

    /** `true` se questa è l'unica istanza; `false` se un'altra era già in ascolto. */
    fun acquire(onShowRequested: () -> Unit): Boolean {
        val socket = try {
            ServerSocket(port, BACKLOG, InetAddress.getLoopbackAddress())
        } catch (_: IOException) {
            knockOnRunningInstance()
            return false
        }
        server = socket
        thread(isDaemon = true, name = "single-instance") {
            while (!socket.isClosed) {
                val accepted = runCatching { socket.accept().close() }.isSuccess
                if (accepted) onShowRequested()
            }
        }
        return true
    }

    fun release() {
        runCatching { server?.close() }
        server = null
    }

    private fun knockOnRunningInstance() {
        runCatching { Socket(InetAddress.getLoopbackAddress(), port).close() }
    }

    companion object {
        const val DEFAULT_PORT = 47653
        private const val BACKLOG = 4
    }
}

package it.agoldoni.reminder.sync

import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * Due estremi collegati, con la registrazione di tutto ciò che passa. Serve a poter affermare
 * qualcosa sul traffico — che sia illeggibile, che un frame ripetuto venga respinto — invece di
 * limitarsi a constatare che i due lati si capiscono.
 */
class Wire {

    private val aOut = PipedOutputStream()
    private val bOut = PipedOutputStream()

    /** Ciò che l'iniziatore ha scritto, byte per byte. */
    val fromInitiator = ByteArrayOutputStream()
    val fromResponder = ByteArrayOutputStream()

    val initiatorInput: PipedInputStream = PipedInputStream(bOut, BUFFER)
    val responderInput: PipedInputStream = PipedInputStream(aOut, BUFFER)

    val initiatorOutput: OutputStream = Tap(aOut, fromInitiator)
    val responderOutput: OutputStream = Tap(bOut, fromResponder)

    fun close() {
        runCatching { aOut.close() }
        runCatching { bOut.close() }
    }

    private class Tap(target: OutputStream, private val copy: ByteArrayOutputStream) :
        FilterOutputStream(target) {
        override fun write(b: Int) {
            copy.write(b)
            out.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            copy.write(b, off, len)
            out.write(b, off, len)
        }
    }

    private companion object {
        const val BUFFER = 1 shl 16
    }
}

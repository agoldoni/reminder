package it.agoldoni.reminder.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HKDF non ha un modo di fallire rumorosamente: se la derivazione è sbagliata i due lati ottengono
 * chiavi diverse e il canale semplicemente non funziona, oppure — peggio — funziona con una chiave
 * più debole del previsto. Per questo la si verifica contro i vettori della RFC 5869 e non contro
 * se stessa.
 */
class CryptoTest {

    @Test
    fun `HKDF corrisponde al caso 1 della RFC 5869`() {
        val okm = Hkdf.derive(
            secret = "0b".repeat(22).hexToBytes(),
            salt = "000102030405060708090a0b0c".hexToBytes(),
            info = "f0f1f2f3f4f5f6f7f8f9".hexToBytes(),
            length = 42
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            okm.toHex()
        )
    }

    @Test
    fun `HKDF corrisponde al caso 3 della RFC 5869, senza sale e senza info`() {
        val okm = Hkdf.derive(
            secret = "0b".repeat(22).hexToBytes(),
            salt = ByteArray(0),
            info = ByteArray(0),
            length = 42
        )
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31" +
                "b8a11f5c5ee1879ec3454e5f3c738d2d" +
                "9d201395faa4b61a96c8",
            okm.toHex()
        )
    }

    @Test
    fun `HKDF corrisponde al caso 2 della RFC 5869, su input lunghi`() {
        val okm = Hkdf.derive(
            secret = (0..0x4f).joinToString("") { "%02x".format(it) }.hexToBytes(),
            salt = (0x60..0xaf).joinToString("") { "%02x".format(it) }.hexToBytes(),
            info = (0xb0..0xff).joinToString("") { "%02x".format(it) }.hexToBytes(),
            length = 82
        )
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a4934" +
                "4f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09" +
                "da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f" +
                "1d87",
            okm.toHex()
        )
    }

    @Test
    fun `due dispositivi arrivano allo stesso segreto ECDH`() {
        val a = EphemeralKeyPair.generate()
        val b = EphemeralKeyPair.generate()

        val daA = a.sharedSecretWith(b.publicKeyBytes)
        val daB = b.sharedSecretWith(a.publicKeyBytes)

        assertEquals(daA.toHex(), daB.toHex())
        assertEquals(32, daA.size, "P-256 produce un segreto da 32 byte")
    }

    @Test
    fun `un terzo dispositivo non arriva allo stesso segreto`() {
        val a = EphemeralKeyPair.generate()
        val b = EphemeralKeyPair.generate()
        val intruso = EphemeralKeyPair.generate()

        assertFalse(
            a.sharedSecretWith(b.publicKeyBytes).toHex() ==
                a.sharedSecretWith(intruso.publicKeyBytes).toHex()
        )
    }

    @Test
    fun `una chiave pubblica malformata viene rifiutata invece che accettata`() {
        val a = EphemeralKeyPair.generate()

        assertFailsWith<Exception> { a.sharedSecretWith(ByteArray(91) { 7 }) }
        assertFailsWith<Exception> { a.sharedSecretWith(ByteArray(0)) }
    }

    @Test
    fun `il confronto a tempo costante distingue comunque i valori`() {
        assertTrue(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
    }

    @Test
    fun `esadecimale e byte fanno andata e ritorno`() {
        val originale = randomBytes(48)
        assertEquals(originale.toHex(), originale.toHex().hexToBytes().toHex())
        assertEquals(96, originale.toHex().length)
    }
}

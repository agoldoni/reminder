package it.agoldoni.reminder.sync

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Le primitive su cui poggiano associazione e canale cifrato. Stanno in `jvmSharedMain` perché
 * `javax.crypto` c'è su entrambi i target: nessun `expect`/`actual`, una sola implementazione da
 * rivedere.
 *
 * La curva è **P-256**: X25519 sulla JVM di Android arriva solo con l'API 31, mentre qui il minimo
 * è 26. Le chiavi sono effimere — una coppia nuova a ogni associazione — quindi la scelta pesa
 * solo sulla compatibilità.
 */
private const val CURVE = "secp256r1"
private const val HMAC = "HmacSHA256"
private const val HASH_LENGTH = 32

internal val secureRandom = SecureRandom()

/** Coppia effimera per lo scambio, con la pubblica già nella forma in cui viaggia sul filo. */
class EphemeralKeyPair private constructor(private val keyPair: KeyPair) {

    /** Codifica X.509 `SubjectPublicKeyInfo`: si ricostruisce con [sharedSecretWith]. */
    val publicKeyBytes: ByteArray get() = keyPair.public.encoded

    /**
     * Segreto ECDH con la pubblica dell'altro. Solleva se i byte non sono una chiave valida sulla
     * curva attesa: una pubblica su un'altra curva è un tentativo di aggirare lo scambio, non un
     * caso da tollerare.
     */
    fun sharedSecretWith(peerPublicKeyBytes: ByteArray): ByteArray {
        val peerKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(peerPublicKeyBytes)) as ECPublicKey
        val expected = (keyPair.public as ECPublicKey).params
        require(peerKey.params.curve == expected.curve && peerKey.params.order == expected.order) {
            "La chiave pubblica del peer non è sulla curva attesa."
        }
        return KeyAgreement.getInstance("ECDH").run {
            init(keyPair.private)
            doPhase(peerKey, true)
            generateSecret()
        }
    }

    companion object {
        fun generate(): EphemeralKeyPair = EphemeralKeyPair(
            KeyPairGenerator.getInstance("EC")
                .apply { initialize(ECGenParameterSpec(CURVE), secureRandom) }
                .generateKeyPair()
        )
    }
}

/**
 * HKDF-SHA256 (RFC 5869). Non c'è in JCA e serve in tre punti: la chiave di sessione, il codice di
 * conferma e le chiavi direzionali del canale.
 */
object Hkdf {
    fun derive(secret: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray =
        derive(secret, salt, info.encodeToByteArray(), length)

    fun derive(secret: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * HASH_LENGTH)) { "Lunghezza non derivabile: $length" }
        val prk = hmac(if (salt.isEmpty()) ByteArray(HASH_LENGTH) else salt, secret)
        val infoBytes = info
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            previous = hmac(prk, previous + infoBytes + byteArrayOf(counter.toByte()))
            val take = minOf(previous.size, length - written)
            previous.copyInto(output, written, 0, take)
            written += take
            counter++
        }
        return output
    }

    fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance(HMAC).run {
            init(SecretKeySpec(key, HMAC))
            doFinal(message)
        }
}

/** Confronto a tempo costante: su un MAC un confronto che esce al primo byte diverso è una falla. */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Stringa esadecimale di lunghezza dispari." }
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun randomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

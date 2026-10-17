package echopc.sync

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing and encryption, with what both Java and Android (6+) have built in: an ECDH (P-256) key exchange,
 * confirmed by a 6-digit code both screens show (like Bluetooth "numeric comparison": the PC commits to its
 * nonce first, so nobody in between can pick keys that give the same code), then AES-256-GCM for every message.
 */
object SyncCrypto {
    private val random = SecureRandom()

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    fun nonce(size: Int = 16): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        parts.forEach { update(it) }
        digest()
    }

    fun agree(own: PrivateKey, peerPublic: ByteArray): ByteArray {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublic))
        return KeyAgreement.getInstance("ECDH").run { init(own); doPhase(pub, true); generateSecret() }
    }

    /** The PC's promise about its nonce, sent before it sees the phone's nonce. */
    fun commitment(pcPublic: ByteArray, phonePublic: ByteArray, pcNonce: ByteArray) = sha256("commit".toByteArray(), pcPublic, phonePublic, pcNonce)

    /** The 6 digits both screens show, e.g. "482 913". */
    fun code(phonePublic: ByteArray, pcPublic: ByteArray, phoneNonce: ByteArray, pcNonce: ByteArray): String {
        val h = sha256("code".toByteArray(), phonePublic, pcPublic, phoneNonce, pcNonce)
        val n = ((h[0].toLong() and 0xff) shl 24 or ((h[1].toLong() and 0xff) shl 16) or ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)) % 1_000_000
        return "%06d".format(n).let { it.substring(0, 3) + " " + it.substring(3) }
    }

    fun sessionKey(secret: ByteArray, phoneNonce: ByteArray, pcNonce: ByteArray) = sha256("wsmusic-sync-key".toByteArray(), secret, phoneNonce, pcNonce)

    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val iv = nonce(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); updateAAD(aad) }
        return iv + cipher.doFinal(plain)
    }

    /** Throws if the message wasn't made with [key] (tampered, or from a device that isn't paired). */
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        require(sealed.size > 28) { "Message too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, 12)); updateAAD(aad) }
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }

    // kotlin.io.encoding: java.util.Base64 needs Android 8, the phone app supports Android 6.
    fun b64(bytes: ByteArray): String = kotlin.io.encoding.Base64.Default.encode(bytes)
    fun unb64(text: String): ByteArray = kotlin.io.encoding.Base64.Default.decode(text)
}

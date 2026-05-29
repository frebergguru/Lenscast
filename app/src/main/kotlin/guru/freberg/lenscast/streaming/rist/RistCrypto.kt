package guru.freberg.lenscast.streaming.rist

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * RIST Main Profile pre-shared-key (PSK) encryption, matching librist / VSF TR-06-2 exactly
 * so the output decrypts on any compliant receiver (ffmpeg `rist://…?secret=`, VLC, Wowza).
 *
 * Scheme (verified against librist `src/crypto/psk.c`):
 *  - **Key derivation:** `PBKDF2-HMAC-SHA256(passphrase, salt = 4-byte GRE nonce, 1024
 *    iterations, dkLen = keyBits/8)`. The nonce is a random non-zero 32-bit value carried in
 *    cleartext in every encrypted GRE header; the receiver re-derives the key from it.
 *  - **Cipher:** AES-CTR. The 16-byte initial counter block is `BE32(greSeq) ‖ 12 zero bytes`
 *    — i.e. the GRE sequence number occupies the high 4 bytes, so CTR increments the low
 *    (zero) bytes. Encryption is a single continuous CTR stream over the cleartext region
 *    (VSF proto header + reduced header + RTP header + payload).
 *
 * We implement PBKDF2 by hand on `Mac("HmacSHA256")` rather than via
 * `SecretKeyFactory("PBKDF2WithHmacSHA256")` to avoid the SunJCE char→byte password-encoding
 * ambiguity — librist hashes the raw passphrase bytes, and we feed UTF-8 bytes directly.
 */
class RistCrypto(passphrase: String, val keyBits: Int) {

    private val password: ByteArray = passphrase.toByteArray(Charsets.UTF_8)

    /** 4-byte TX nonce (PBKDF2 salt + GRE header field). Random, non-zero, "even" (bit7 of [0] clear). */
    val nonce: ByteArray = randomNonce()
    private val txKey: ByteArray = deriveKey(nonce)

    // Cache the most recently used RX nonce→key so per-packet decryption doesn't re-run PBKDF2
    // 1024 times for every inbound feedback packet (the receiver keeps one nonce per session).
    private var rxNonce: ByteArray? = null
    private var rxKey: ByteArray? = null

    /** Encrypt [data] for transmission under GRE sequence [greSeq]. Returns a new array. */
    fun encrypt(greSeq: Int, data: ByteArray): ByteArray = aesCtr(txKey, ivFor(greSeq), data)

    /** Decrypt an inbound packet that carried [pktNonce] and [greSeq] in its GRE header. */
    fun decrypt(pktNonce: ByteArray, greSeq: Int, data: ByteArray, off: Int, len: Int): ByteArray {
        val key = keyForRxNonce(pktNonce)
        return aesCtr(key, ivFor(greSeq), data.copyOfRange(off, off + len))
    }

    private fun keyForRxNonce(n: ByteArray): ByteArray {
        val cached = rxKey
        if (cached != null && rxNonce.contentEqualsSafe(n)) return cached
        val k = deriveKey(n)
        rxNonce = n.copyOf()
        rxKey = k
        return k
    }

    private fun ivFor(greSeq: Int): ByteArray {
        val iv = ByteArray(16)
        iv[0] = (greSeq ushr 24).toByte()
        iv[1] = (greSeq ushr 16).toByte()
        iv[2] = (greSeq ushr 8).toByte()
        iv[3] = greSeq.toByte()
        return iv
    }

    private fun deriveKey(salt: ByteArray): ByteArray = pbkdf2HmacSha256(password, salt, ITERATIONS, keyBits / 8)

    private fun aesCtr(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun ByteArray?.contentEqualsSafe(other: ByteArray): Boolean =
        this != null && this.contentEquals(other)

    companion object {
        private const val ITERATIONS = 1024 // RIST_PBKDF2_HMAC_SHA256_ITERATIONS

        private fun randomNonce(): ByteArray {
            val n = ByteArray(4)
            java.security.SecureRandom().nextBytes(n)
            if (n.all { it.toInt() == 0 }) n[0] = 1 // must be non-zero
            n[0] = (n[0].toInt() and 0x7F).toByte() // clear bit7 → "even" key (librist convention)
            return n
        }

        internal fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(password, "HmacSHA256"))
            val hLen = 32
            val out = ByteArray(dkLen)
            val blocks = (dkLen + hLen - 1) / hLen
            var written = 0
            for (i in 1..blocks) {
                // U1 = PRF(salt ‖ INT_BE(i))
                mac.update(salt)
                val u1 = mac.doFinal(byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
                var u = u1
                val t = u1.copyOf()
                for (j in 1 until iterations) {
                    u = mac.doFinal(u)
                    for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
                }
                val n = minOf(hLen, dkLen - written)
                System.arraycopy(t, 0, out, written, n)
                written += n
            }
            return out
        }
    }
}

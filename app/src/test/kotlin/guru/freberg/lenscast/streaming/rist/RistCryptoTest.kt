package guru.freberg.lenscast.streaming.rist

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins the RIST Main-Profile PSK crypto to known-answer vectors. Interop with a live RIST
 * receiver can't be exercised in CI, so the next best assurance is that the key-derivation
 * and cipher exactly match the spec librist implements (PBKDF2-HMAC-SHA256, AES-CTR).
 */
class RistCryptoTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /** RFC 7914 / standard PBKDF2-HMAC-SHA256 known-answer vectors. */
    @Test
    fun pbkdf2_matchesRfcVectors() {
        // password="password", salt="salt", c=1, dkLen=32
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hex(RistCrypto.pbkdf2HmacSha256("password".toByteArray(), "salt".toByteArray(), 1, 32)),
        )
        // password="password", salt="salt", c=2, dkLen=32
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            hex(RistCrypto.pbkdf2HmacSha256("password".toByteArray(), "salt".toByteArray(), 2, 32)),
        )
        // password="passwordPASSWORDpassword", salt="saltSALTsaltSALTsaltSALTsaltSALTsalt", c=4096, dkLen=40
        assertEquals(
            "348c89dbcbd32b2f32d814b8116e84cf2b17347ebc1800181c4e2a1fb8dd53e1c635518c7dac47e9",
            hex(RistCrypto.pbkdf2HmacSha256(
                "passwordPASSWORDpassword".toByteArray(),
                "saltSALTsaltSALTsaltSALTsaltSALTsalt".toByteArray(),
                4096, 40,
            )),
        )
    }

    /** VSF TR-06-2 Annex B official key-derivation vector: passphrase "Reliable Internet Stream
     *  Transport", salt = the 4 bytes 0x52495354, 1024 iterations. This is the canonical
     *  RIST Main-Profile PSK example, so matching it proves byte-level spec interop. */
    @Test
    fun pbkdf2_matchesRistSpecAnnexBVector() {
        val pass = "Reliable Internet Stream Transport".toByteArray()
        val salt = hex("52495354")
        assertEquals(
            "1c2b0cfc90ae2638fea78c7fb2977047",
            hex(RistCrypto.pbkdf2HmacSha256(pass, salt, 1024, 16)),
        )
        assertEquals(
            "1c2b0cfc90ae2638fea78c7fb297704718bff7f4052743001a9b7ebb51cc9f1c",
            hex(RistCrypto.pbkdf2HmacSha256(pass, salt, 1024, 32)),
        )
    }

    /** AES-CTR is a stream cipher: encrypting then decrypting (re-deriving the key from the
     *  transmitted nonce, as a receiver does) must recover the plaintext exactly. */
    @Test
    fun encryptDecrypt_roundTripsAcrossInstances() {
        val sender = RistCrypto("hunter2hunter2", 128)
        val receiver = RistCrypto("hunter2hunter2", 128) // independent instance, different own nonce
        val plain = ByteArray(1316) { (it * 7 + 3).toByte() }
        val greSeq = 0x0001F2A3
        val ct = sender.encrypt(greSeq, plain)
        assertFalse("ciphertext must differ from plaintext", ct.contentEquals(plain))
        // The receiver derives the key from the *sender's* nonce (carried on the wire).
        val pt = receiver.decrypt(sender.nonce, greSeq, ct, 0, ct.size)
        assertArrayEquals(plain, pt)
    }

    @Test
    fun aes256_roundTrips() {
        val c = RistCrypto("a-rather-longer-passphrase", 256)
        val plain = "the quick brown fox".toByteArray()
        val ct = c.encrypt(42, plain)
        assertArrayEquals(plain, c.decrypt(c.nonce, 42, ct, 0, ct.size))
    }
}

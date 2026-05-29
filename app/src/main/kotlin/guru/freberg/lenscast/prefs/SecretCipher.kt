package guru.freberg.lenscast.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Transparent at-rest encryption for the credential fields stored in DataStore (the stream /
 * SFTP passwords and the SRT / RIST passphrases). Preferences-DataStore writes plaintext to
 * app-private storage, which is recoverable on a rooted device or via data extraction; this
 * wraps each secret with an AES-256-GCM key held in the AndroidKeyStore (hardware-backed and
 * non-exportable where the device supports it).
 *
 * Migration & robustness — this must never break an existing install or crash a settings read:
 *  - [seal] output is tagged with [PREFIX]; [open] decrypts tagged values and returns anything
 *    else verbatim, so values written by older (plaintext) builds keep working and are
 *    re-encrypted on the next save.
 *  - Every Keystore failure degrades gracefully: [seal] falls back to returning plaintext,
 *    [open] returns "". Neither throws. The key is created without user-authentication binding,
 *    so it isn't invalidated by lock-screen changes.
 */
object SecretCipher {
    private const val PREFIX = "enc1:"
    private const val ALIAS = "lenscast_secret_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private val key: SecretKey? by lazy { loadOrCreateKey() }

    /** Encrypt [plain] for storage. Empty stays empty; failures fall back to plaintext. */
    fun seal(plain: String): String {
        if (plain.isEmpty()) return plain
        val k = key ?: return plain
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, k)
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (_: Throwable) { plain }
    }

    /** Decrypt a value produced by [seal]; pass through legacy plaintext unchanged. */
    fun open(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        val k = key ?: return ""
        return try {
            val raw = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, raw, 0, IV_LEN))
            String(cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
        } catch (_: Throwable) { "" }
    }

    private fun loadOrCreateKey(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: run {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            gen.generateKey()
        }
    } catch (_: Throwable) { null }
}

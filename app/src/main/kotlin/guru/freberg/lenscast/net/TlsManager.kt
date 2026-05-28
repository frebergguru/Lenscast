package guru.freberg.lenscast.net

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Self-signed TLS material for the MJPEG + web-control HTTP servers.
 *
 * v1 used AndroidKeyStore for the key — clean idea, but the TLS handshake routinely
 * failed mid-flight with "Incompatible digest" / "RSA/ECB/NoPadding provider not found"
 * errors out of Conscrypt's RSA upcalls. The keystore's KM_TAG_DIGEST authorizations
 * can't be made permissive enough on every vendor keymaster to satisfy Conscrypt's
 * actual cipher/init calls during a real TLS handshake. So this version uses a
 * software RSA-2048 keypair from the standard SunRsaSign provider, wraps it in a
 * Bouncy Castle-built X.509 v3 cert, and persists both as a single PKCS12 file in the
 * app's private files directory (no password — the file's only reachable from this
 * uid).
 *
 * Validity is 100 years. Receivers see a self-signed-CA warning; the SHA-256
 * fingerprint shown in the app + the web panel lets the user verify it.
 */
class TlsManager(private val context: Context) {

    private val keystoreFile: File get() = File(context.filesDir, "lenscast-tls.p12")

    /**
     * Returns an [SSLContext] backed by the persisted cert + key. Generates the
     * material on first call. Returns null only on disk-I/O or unexpected JCA failure.
     */
    fun sslContext(): SSLContext? = try {
        val ks = loadOrCreateKeyStore()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, KS_PASSWORD)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        ctx
    } catch (t: Throwable) {
        Log.w(TAG, "sslContext failed: ${t.message}")
        null
    }

    /** SHA-256 fingerprint of the current cert, colon-separated hex. Null on failure. */
    fun fingerprintSha256(): String? = try {
        val ks = loadOrCreateKeyStore()
        val cert = ks.getCertificate(ALIAS) as? X509Certificate ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        digest.joinToString(":") { "%02X".format(it) }
    } catch (t: Throwable) {
        Log.w(TAG, "fingerprintSha256 failed: ${t.message}")
        null
    }

    /** Wipe the stored material so the next [sslContext] call regenerates fresh. */
    fun regenerate() {
        try { keystoreFile.delete() } catch (_: Throwable) {}
    }

    private fun loadOrCreateKeyStore(): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        if (keystoreFile.exists()) {
            try {
                keystoreFile.inputStream().use { ks.load(it, KS_PASSWORD) }
                if (ks.containsAlias(ALIAS)) return ks
            } catch (t: Throwable) {
                Log.w(TAG, "existing keystore unusable (${t.message}); regenerating")
            }
        }
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = buildSelfSignedCertificate(keyPair)
        val freshKs = KeyStore.getInstance("PKCS12")
        freshKs.load(null, KS_PASSWORD)
        freshKs.setKeyEntry(ALIAS, keyPair.private, KS_PASSWORD, arrayOf<java.security.cert.Certificate>(cert))
        keystoreFile.outputStream().use { freshKs.store(it, KS_PASSWORD) }
        Log.i(TAG, "Generated self-signed TLS cert at ${keystoreFile.absolutePath}")
        return freshKs
    }

    /**
     * X.509 v3 self-signed cert via Bouncy Castle's JcaX509v3CertificateBuilder. Common
     * Name is "Lenscast" — hostname matching always fails (the phone's IP isn't known
     * at issue time) but that's expected for a self-signed LAN cert; receivers click
     * through the warning once per browser.
     */
    private fun buildSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val notBefore = Date()
        val notAfter = Date(notBefore.time + 100L * 365L * 24L * 60L * 60L * 1000L) // ~100y
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val name = X500Name("CN=Lenscast, O=Lenscast")
        val builder = JcaX509v3CertificateBuilder(name, serial, notBefore, notAfter, name, keyPair.public)
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val holder = builder.build(signer)
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    companion object {
        private const val TAG = "TlsManager"
        private const val ALIAS = "lenscast-server"
        // Password for the on-disk PKCS12. The file is in app-private storage so this
        // is a formality — KeyManagerFactory.init just requires *some* password.
        private val KS_PASSWORD = "lenscast".toCharArray()

        /**
         * Compatibility shim for callers that used the old object-based TlsManager
         * (`TlsManager.sslContext()` / `TlsManager.fingerprintSha256()`). They now
         * pass a Context to [forContext]; the legacy zero-arg statics throw to make
         * call sites explicit during the migration.
         */
        fun forContext(context: Context): TlsManager = TlsManager(context.applicationContext)
    }
}

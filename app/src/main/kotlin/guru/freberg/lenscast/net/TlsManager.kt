package guru.freberg.lenscast.net

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
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

    /**
     * SHA-256 fingerprint of the current cert, colon-separated hex. Null on failure.
     *
     * The web panel's /status endpoint reads this at 1 Hz; recomputing means a PKCS12
     * disk read + parse + cert digest every second. The fingerprint only changes when the
     * cert is reissued, which happens on exactly two triggers: the device's IP set changes
     * (see [loadOrCreateKeyStore]) or [regenerate] wipes the file. We memoize keyed on both
     * the IP set and the keystore file's mtime so a cache hit is provably the same cert.
     */
    fun fingerprintSha256(): String? {
        val ips = localIpv4Addresses()
        val mtime = keystoreFile.lastModified()
        fpCache?.let { if (it.mtime == mtime && it.ips == ips) return it.fingerprint }
        return try {
            val ks = loadOrCreateKeyStore()
            val cert = ks.getCertificate(ALIAS) as? X509Certificate ?: return null
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            val fp = digest.joinToString(":") { "%02X".format(it) }
            // loadOrCreateKeyStore may have just rewritten the file (IP change / first run),
            // so capture the post-load mtime — that's what the next call will compare against.
            fpCache = FpCache(fp, ips, keystoreFile.lastModified())
            fp
        } catch (t: Throwable) {
            Log.w(TAG, "fingerprintSha256 failed: ${t.message}")
            null
        }
    }

    /** Wipe the stored material so the next [sslContext] call regenerates fresh. */
    fun regenerate() {
        fpCache = null
        try { keystoreFile.delete() } catch (_: Throwable) {}
    }

    private fun loadOrCreateKeyStore(): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        val currentIps = localIpv4Addresses()
        if (keystoreFile.exists()) {
            try {
                keystoreFile.inputStream().use { ks.load(it, KS_PASSWORD) }
                val cached = ks.getCertificate(ALIAS) as? X509Certificate
                // Regenerate when the device's IP set has changed (e.g. user switched
                // Wi-Fi networks since first issue). A cert without SAN entries for the
                // active IP triggers `SSLV3_ALERT_CERTIFICATE_UNKNOWN` in every modern
                // browser, which used to flood the WebControlServer log and break the
                // 1 Hz /status poll once browsers rate-limited the failed handshakes.
                if (cached != null && ks.containsAlias(ALIAS) && certCoversIps(cached, currentIps)) {
                    return ks
                }
                Log.i(TAG, "Cert exists but doesn't cover current IPs (${currentIps.joinToString()}); regenerating")
            } catch (t: Throwable) {
                Log.w(TAG, "existing keystore unusable (${t.message}); regenerating")
            }
        }
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = buildSelfSignedCertificate(keyPair, currentIps)
        val freshKs = KeyStore.getInstance("PKCS12")
        freshKs.load(null, KS_PASSWORD)
        freshKs.setKeyEntry(ALIAS, keyPair.private, KS_PASSWORD, arrayOf<java.security.cert.Certificate>(cert))
        keystoreFile.outputStream().use { freshKs.store(it, KS_PASSWORD) }
        Log.i(TAG, "Generated self-signed TLS cert with SAN=${currentIps.joinToString()}")
        return freshKs
    }

    /** Every non-loopback IPv4 address the device currently has. Empty if Wi-Fi is off. */
    private fun localIpv4Addresses(): List<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    } catch (_: Throwable) { emptyList() }

    /** True when the cert's IP-SANs include every address in [ips] (with the loopback). */
    private fun certCoversIps(cert: X509Certificate, ips: List<String>): Boolean {
        val sans = try {
            cert.subjectAlternativeNames?.mapNotNull { entry ->
                // Each entry is `[Int, Any]` — type 7 = iPAddress (RFC 5280 §4.2.1.6).
                if (entry.size >= 2 && entry[0] == 7) entry[1] as? String else null
            }?.toSet().orEmpty()
        } catch (_: Throwable) { emptySet() }
        if (sans.isEmpty()) return false
        // Always require loopback + at least one current IP — if Wi-Fi is off and ips is
        // empty, the loopback presence keeps `adb forward` usable from the dev machine.
        if ("127.0.0.1" !in sans) return false
        return ips.all { it in sans }
    }

    /**
     * X.509 v3 self-signed cert. Includes SubjectAlternativeName entries for every IPv4
     * address the device currently has plus `127.0.0.1`, `localhost`, and `lenscast.local`
     * (mDNS). Without SAN, modern browsers reject the cert outright with no "proceed
     * anyway" option for fetch/XHR — the on-screen warning is browseable but JS fetches
     * silently fail, which is what broke our 1 Hz /status poll over HTTPS.
     */
    private fun buildSelfSignedCertificate(keyPair: KeyPair, ips: List<String>): X509Certificate {
        val notBefore = Date()
        val notAfter = Date(notBefore.time + 100L * 365L * 24L * 60L * 60L * 1000L) // ~100y
        // ≥64 bits of entropy (CA/Browser Forum baseline) — a time-based serial is guessable
        // and can collide across two certs issued in the same millisecond.
        val serial = BigInteger(159, java.security.SecureRandom())
        val name = X500Name("CN=Lenscast, O=Lenscast")
        val builder = JcaX509v3CertificateBuilder(name, serial, notBefore, notAfter, name, keyPair.public)
        val sanEntries = buildList {
            add(GeneralName(GeneralName.iPAddress, "127.0.0.1"))
            for (ip in ips) add(GeneralName(GeneralName.iPAddress, ip))
            add(GeneralName(GeneralName.dNSName, "localhost"))
            add(GeneralName(GeneralName.dNSName, "lenscast.local"))
        }
        builder.addExtension(
            Extension.subjectAlternativeName, false,
            GeneralNames(sanEntries.toTypedArray()),
        )
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

        // Memoized fingerprint (see fingerprintSha256). Process-level because forContext()
        // hands out a fresh TlsManager per call, so an instance field wouldn't survive.
        // A single @Volatile holder keeps the (fingerprint, ips, mtime) triple coherent.
        private class FpCache(val fingerprint: String, val ips: List<String>, val mtime: Long)
        @Volatile private var fpCache: FpCache? = null

        /**
         * Compatibility shim for callers that used the old object-based TlsManager
         * (`TlsManager.sslContext()` / `TlsManager.fingerprintSha256()`). They now
         * pass a Context to [forContext]; the legacy zero-arg statics throw to make
         * call sites explicit during the migration.
         */
        fun forContext(context: Context): TlsManager = TlsManager(context.applicationContext)
    }
}

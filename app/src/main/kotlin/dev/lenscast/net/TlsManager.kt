package dev.lenscast.net

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/**
 * Self-signed certificate manager backed by the AndroidKeyStore.
 *
 * AndroidKeyStore is the native, hardware-backed keystore on modern Android. Crucially,
 * generating a key pair there with a `subject` *also synthesises a self-signed X.509
 * certificate around it* — exactly what we need for our TLS server, with no Bouncy Castle
 * dependency and no manual ASN.1 work. The private key never leaves the secure storage,
 * which is a nice security side-effect.
 *
 * Validity is 100 years. Receivers will see a hostname mismatch ("CN=Lenscast" vs the
 * phone's IP) and a self-signed-CA warning — that's expected, the user has to click
 * through once per browser. The SHA-256 fingerprint shown in the app lets the user
 * verify the cert hasn't been MITM-ed.
 */
object TlsManager {
    private const val TAG = "TlsManager"
    private const val ALIAS = "lenscast-server"
    private const val KEYSTORE = "AndroidKeyStore"

    /**
     * Returns the SSL context configured with our cert and private key. Generates the key
     * pair on first call. Returns null if generation fails (some emulator images, older
     * devices missing AndroidKeyStore).
     */
    fun sslContext(): SSLContext? {
        return try {
            ensureKeyPair()
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, null) // null password — AndroidKeyStore manages access internally
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(kmf.keyManagers, null, null)
            ctx
        } catch (t: Throwable) {
            Log.w(TAG, "sslContext failed: ${t.message}")
            null
        }
    }

    /** SHA-256 fingerprint of the current cert, colon-separated hex; null if no cert yet. */
    fun fingerprintSha256(): String? = try {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val cert = ks.getCertificate(ALIAS) as? X509Certificate ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        digest.joinToString(":") { "%02X".format(it) }
    } catch (t: Throwable) {
        Log.w(TAG, "fingerprintSha256 failed: ${t.message}")
        null
    }

    /** Wipes the stored key + cert. The next [sslContext] call will regenerate. */
    fun regenerate() {
        try {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        } catch (t: Throwable) {
            Log.w(TAG, "delete alias failed: ${t.message}")
        }
        ensureKeyPair()
    }

    private fun ensureKeyPair() {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (ks.containsAlias(ALIAS)) return
        val notBefore = Calendar.getInstance()
        val notAfter = (notBefore.clone() as Calendar).apply { add(Calendar.YEAR, 100) }
        val spec = KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setKeySize(2048)
            // Hostname mismatch is unavoidable — phone IPs change and we'd have to
            // re-issue the cert every time the user joins a new Wi-Fi. Browsers warn but
            // accept self-signed certs through "advanced → proceed".
            .setCertificateSubject(X500Principal("CN=Lenscast, O=Lenscast"))
            .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            .setCertificateNotBefore(notBefore.time)
            .setCertificateNotAfter(notAfter.time)
            // Hardware-backed when available (most modern phones); falls back to software
            // on emulators / very old devices.
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setIsStrongBoxBacked(false)
            }
            .build()
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE)
        kpg.initialize(spec)
        kpg.generateKeyPair()
        Log.i(TAG, "Generated self-signed TLS cert for Lenscast")
    }
}

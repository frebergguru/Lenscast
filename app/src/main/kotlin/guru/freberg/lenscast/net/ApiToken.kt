package guru.freberg.lenscast.net

import android.util.Base64
import java.security.SecureRandom

/**
 * Generator for the REST API bearer token (see Docs/API.md). The token only ever exists in
 * two places: encrypted at rest in DataStore (via [guru.freberg.lenscast.prefs.SecretCipher])
 * and in the device's local Settings sheet where the user copies it out. It is never sent
 * over the network by the server, so the only thing that matters here is that it's
 * unguessable — 32 bytes from [SecureRandom] (256 bits) is well past brute-forcing.
 *
 * URL-safe Base64 without padding so it drops cleanly into an `Authorization: Bearer …`
 * header, a query string, or a plugin config field without escaping.
 */
object ApiToken {
    private val rng = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(32)
        rng.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
    }
}

package guru.freberg.lenscast.net

import java.security.MessageDigest

/**
 * Shared HTTP Basic-auth helpers for the hand-rolled servers (MJPEG, RTSP, web control).
 *
 * The comparison is constant-time: a plain `==` on the password short-circuits at the first
 * differing character, which over the LAN leaks how many leading characters were guessed
 * correctly — a usable side channel for credential guessing. [MessageDigest.isEqual] compares
 * the full byte arrays without early exit.
 */
object AuthUtils {
    fun constantTimeEquals(a: String, b: String): Boolean {
        // Compare fixed-length SHA-256 digests rather than the raw bytes: [MessageDigest.isEqual]
        // returns early when the two arrays differ in length, which over the LAN would leak the
        // length of the real secret. Hashing first makes both inputs always 32 bytes, so only
        // content (never length) influences timing. Equal strings still hash equal.
        val da = MessageDigest.getInstance("SHA-256").digest(a.toByteArray(Charsets.UTF_8))
        val db = MessageDigest.getInstance("SHA-256").digest(b.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(da, db)
    }
}

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
    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}

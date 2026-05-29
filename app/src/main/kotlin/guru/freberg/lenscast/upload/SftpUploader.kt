package guru.freberg.lenscast.upload

import android.content.Context
import android.net.Uri
import android.util.Log
import guru.freberg.lenscast.prefs.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.io.FileOutputStream
import java.security.PublicKey
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * Background queue of SFTP uploads. One worker coroutine drains the queue, with exponential
 * backoff on per-job failures and bounded retries (3) before dropping a job.
 *
 * Jobs survive process restarts only if the caller re-enqueues them; the queue itself is
 * in-memory. Acceptable for the current scope — recordings finalise during a streaming
 * session, and the service stays alive across stream stops via the persistent web-control
 * notification when the user has that on.
 *
 * Threading: `enqueue` is safe from any thread. The worker runs on Dispatchers.IO.
 */
class SftpUploader(private val context: Context) {

    data class Job(val sourceUri: Uri, val displayName: String, var attemptsLeft: Int = 3)

    enum class State { IDLE, UPLOADING, SUCCESS, FAILED }

    data class Status(
        val state: State = State.IDLE,
        val current: String? = null,
        val queueSize: Int = 0,
        val lastError: String? = null,
        val lastUploaded: String? = null,
    )

    private val queue = ConcurrentLinkedQueue<Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: kotlinx.coroutines.Job? = null
    private val statusRef = AtomicReference(Status())
    private val settingsSnapshot = AtomicReference<Settings?>(null)

    fun status(): Status = statusRef.get()

    fun updateSettings(settings: Settings) { settingsSnapshot.set(settings) }

    /** Adds a finished MP4 to the upload queue. No-op when SFTP is disabled in settings. */
    fun enqueue(uri: Uri, displayName: String) {
        val s = settingsSnapshot.get() ?: return
        if (!s.sftpEnabled || s.sftpHost.isBlank() || s.sftpUser.isBlank()) return
        queue.add(Job(uri, displayName))
        publish(statusRef.get().copy(queueSize = queue.size))
        ensureWorker()
    }

    fun shutdown() {
        worker?.cancel()
        worker = null
        scope.cancel()
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val job = queue.poll() ?: break
                runJob(job)
            }
            publish(statusRef.get().copy(state = if (queue.isEmpty()) statusRef.get().state else State.UPLOADING))
        }
    }

    private suspend fun runJob(job: Job) {
        val s = settingsSnapshot.get() ?: return
        publish(Status(state = State.UPLOADING, current = job.displayName, queueSize = queue.size))
        // Stream the content into a temp file — sshj's Transfer API wants a real File, and
        // contentResolver-backed URIs don't expose one. The temp lives in cacheDir so it's
        // wiped on uninstall / clear-data.
        var tempFile: File? = null
        try {
            tempFile = stageToTemp(job)
            uploadFile(tempFile, job.displayName, s)
            publish(Status(state = State.SUCCESS, queueSize = queue.size, lastUploaded = job.displayName))
        } catch (t: Throwable) {
            Log.w(TAG, "SFTP upload failed: ${t.message}")
            job.attemptsLeft -= 1
            if (job.attemptsLeft > 0) {
                queue.add(job)
                publish(statusRef.get().copy(
                    state = State.UPLOADING,
                    queueSize = queue.size,
                    lastError = "retry ${4 - job.attemptsLeft}/3: ${t.message}",
                ))
                // Exponential backoff: 5 s, 20 s, 60 s.
                val backoff = when (job.attemptsLeft) { 2 -> 5_000L; 1 -> 20_000L; else -> 60_000L }
                delay(backoff)
            } else {
                publish(statusRef.get().copy(
                    state = State.FAILED,
                    queueSize = queue.size,
                    lastError = "dropped after 3 attempts: ${t.message}",
                ))
            }
        } finally {
            tempFile?.delete()
        }
    }

    private fun stageToTemp(job: Job): File {
        val tmp = File.createTempFile("lenscast_sftp_", ".mp4", context.cacheDir)
        context.contentResolver.openInputStream(job.sourceUri)?.use { input ->
            FileOutputStream(tmp).use { out -> input.copyTo(out) }
        } ?: throw IllegalStateException("source URI is unreadable: ${job.sourceUri}")
        return tmp
    }

    private fun uploadFile(file: File, displayName: String, s: Settings) {
        val ssh = SSHClient(DefaultConfig()).apply {
            connectTimeout = 15_000
            timeout = 30_000
            addHostKeyVerifier(verifierFor(s.sftpHostKeyFingerprint))
        }
        try {
            ssh.connect(s.sftpHost, s.sftpPort)
            ssh.authPassword(s.sftpUser, s.sftpPassword)
            ssh.useCompression()
            val sftp: SFTPClient = ssh.newSFTPClient()
            try {
                val remoteDir = s.sftpRemoteDir.trim().trimEnd('/').ifEmpty { "." }
                if (remoteDir != ".") {
                    try { sftp.mkdirs(remoteDir) } catch (_: Throwable) { /* already exists */ }
                }
                val remotePath = if (remoteDir == ".") displayName else "$remoteDir/$displayName"
                sftp.put(file.absolutePath, remotePath)
            } finally { try { sftp.close() } catch (_: Throwable) {} }
        } finally { try { ssh.disconnect() } catch (_: Throwable) {} }
    }

    /**
     * Builds a HostKeyVerifier:
     *   - if the user pinned a fingerprint, only accept hosts whose SHA-256 matches (base64
     *     `SHA256:` form or hex). MD5/SHA-1 are no longer accepted — they're collision-prone,
     *     so a pin against them could be satisfied by a forged key.
     *   - otherwise trust-on-first-use: record the key the first time a host is seen and
     *     reject if it ever changes. This catches a later MITM, unlike the old accept-any
     *     default which silently leaked the SSH password to whoever answered the handshake.
     */
    private fun verifierFor(pin: String): HostKeyVerifier {
        val target = normalizePin(pin)
        return object : HostKeyVerifier {
            override fun verify(hostname: String?, port: Int, key: PublicKey): Boolean {
                val sha256 = SecurityUtils.getFingerprint(key) // "SHA256:..." (base64)
                if (target.isNotEmpty()) {
                    return target == sha256.substringAfter(':').lowercase() ||
                        target == hashKeyHex(key, "SHA-256")
                }
                return tofuVerify(hostname, port, sha256)
            }
            override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
        }
    }

    /**
     * Trust-on-first-use against a private known-hosts file. Records `host:port fingerprint`
     * the first time a host is seen (and accepts), then requires the same fingerprint on every
     * later connect. The residual first-connect MITM is inherent to TOFU; pin a fingerprint in
     * Settings to remove it.
     */
    private fun tofuVerify(hostname: String?, port: Int, fingerprint: String): Boolean {
        val id = "${hostname ?: "?"}:$port"
        val file = File(context.filesDir, "sftp_known_hosts")
        val known: Map<String, String> = try {
            if (file.exists()) file.readLines().mapNotNull { line ->
                val sp = line.indexOf(' ')
                if (sp <= 0) null else line.substring(0, sp) to line.substring(sp + 1).trim()
            }.toMap() else emptyMap()
        } catch (_: Throwable) { emptyMap() }
        val seen = known[id]
        return when {
            seen == null -> {
                try { file.appendText("$id $fingerprint\n") } catch (_: Throwable) {}
                Log.i(TAG, "SFTP TOFU: pinned $id")
                true
            }
            seen == fingerprint -> true
            else -> {
                Log.w(TAG, "SFTP host key for $id changed since first connect; rejecting")
                false
            }
        }
    }

    /**
     * Normalise a pasted SHA-256 host-key fingerprint for comparison. Strips an optional
     * `SHA256:` prefix and any colon byte-separators, lower-casing the rest, so both the
     * OpenSSH base64 form and a colon-separated hex form compare cleanly.
     */
    private fun normalizePin(pin: String): String {
        var p = pin.trim()
        if (p.lowercase().startsWith("sha256:")) p = p.substring("sha256:".length)
        // Hex fingerprints use ':' as a byte separator; base64 SHA-256 contains no ':' so
        // this is a no-op there.
        return p.trim().lowercase().replace(":", "")
    }

    private fun hashKeyHex(key: PublicKey, algo: String): String {
        val md = java.security.MessageDigest.getInstance(algo)
        return md.digest(key.encoded).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun publish(s: Status) { statusRef.set(s) }

    companion object {
        private const val TAG = "SftpUploader"
    }
}

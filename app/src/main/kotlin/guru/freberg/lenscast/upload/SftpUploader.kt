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
     *   - if the user pinned a fingerprint, only accept hosts whose SHA-256 (or MD5) matches;
     *   - otherwise accept any key (TOFU). LAN-only deployments are the target; users who
     *     care about MITM are expected to pin.
     */
    private fun verifierFor(pin: String): HostKeyVerifier {
        val target = pin.substringAfter(':').trim().lowercase().replace(":", "")
        return object : HostKeyVerifier {
            override fun verify(hostname: String?, port: Int, key: PublicKey): Boolean {
                if (target.isEmpty()) return true
                val sha256 = SecurityUtils.getFingerprint(key) // "SHA256:..." (base64)
                val sha256Hex = hashKeyHex(key, "SHA-256")
                val md5Hex = hashKeyHex(key, "MD5")
                return target == sha256.substringAfter(':').lowercase()
                    || target == sha256Hex
                    || target == md5Hex
            }
            override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
        }
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

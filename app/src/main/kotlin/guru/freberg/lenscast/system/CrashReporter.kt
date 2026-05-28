package guru.freberg.lenscast.system

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only crash reporter — installs a `Thread.UncaughtExceptionHandler` that writes
 * the crash plus a trimmed `logcat` tail into a file under the app's private storage.
 * Nothing leaves the device unless the user explicitly shares the file. No network use,
 * no third-party SDKs.
 *
 * Mostly useful when a tester reports "the app just crashes when I X" — they can hit the
 * Share diagnostics button in Settings and send the file to the developer.
 */
object CrashReporter {

    private const val MAX_LOGCAT_LINES = 400
    private const val MAX_FILE_SIZE_BYTES = 200_000  // keep a couple of crashes' worth

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try { writeCrashReport(context, thread, throwable) } catch (_: Throwable) { /* never crash inside the handler */ }
            // Chain to the previous handler — usually `RuntimeInit$KillApplicationHandler`
            // which kills the process. Without this the app would hang on a broken state.
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun reportFile(context: Context): File = File(context.filesDir, "lenscast-diagnostics.txt")

    private fun writeCrashReport(context: Context, thread: Thread, throwable: Throwable) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val sw = StringWriter()
        sw.appendLine("=== Lenscast crash $ts ===")
        sw.appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sw.appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sw.appendLine("thread: ${thread.name}")
        sw.appendLine()
        throwable.printStackTrace(PrintWriter(sw))
        sw.appendLine()
        sw.appendLine("--- logcat tail ---")
        sw.appendLine(captureLogcatTail())
        appendBounded(context, sw.toString())
    }

    /**
     * Read recent logcat lines. Restricted to our own process to avoid dragging in noisy
     * system logs (and to respect API 30+'s "you can only read your own logs" rule on
     * non-debuggable builds — which is fine, debug builds are exactly what testers run).
     */
    private fun captureLogcatTail(): String = try {
        val pid = android.os.Process.myPid().toString()
        val proc = ProcessBuilder("logcat", "-d", "-t", MAX_LOGCAT_LINES.toString(), "--pid=$pid")
            .redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().use { it.readText() }
    } catch (t: Throwable) {
        "<logcat unavailable: ${t.message}>"
    }

    /** Truncate the file from the top when it grows past the size cap. */
    private fun appendBounded(context: Context, payload: String) {
        val file = reportFile(context)
        val existing = if (file.exists()) file.readText() else ""
        val combined = "$existing\n\n$payload"
        val trimmed = if (combined.length <= MAX_FILE_SIZE_BYTES) combined
                      else combined.takeLast(MAX_FILE_SIZE_BYTES)
        file.writeText(trimmed)
        Log.i("CrashReporter", "Wrote crash report to ${file.absolutePath} (${file.length()}B)")
    }
}

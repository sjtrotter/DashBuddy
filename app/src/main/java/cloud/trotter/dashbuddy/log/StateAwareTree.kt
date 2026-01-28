package cloud.trotter.dashbuddy.log

import android.util.Log
import cloud.trotter.dashbuddy.data.log.LogRepository
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Provider

/**
 * A custom Timber Tree that:
 * 1. Intercepts logs from Timber.
 * 2. Injects the current App State (e.g., OFFER_EVAL).
 * 3. Formats them with a timestamp.
 * 4. Sends them to the [cloud.trotter.dashbuddy.data.log.LogRepository] for file storage.
 */
class StateAwareTree(
    private val logRepository: LogRepository,
    private val stateProvider: Provider<String> // Lazy access to state to avoid circular dependencies
) : Timber.Tree() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Optional: Filter out VERBOSE logs from the file to save space
        if (priority == Log.VERBOSE) return

        val timestamp = dateFormat.format(Date())

        // Safely get the current state name (e.g., "IDLE", "DASHING")
        // We use a Provider so we get the *fresh* state at the exact moment of logging.
        val state = try {
            stateProvider.get()
        } catch (_: Exception) {
            "UNKNOWN"
        }

        val level = when (priority) {
            Log.DEBUG -> "DEBUG"
            Log.INFO -> "INFO"
            Log.WARN -> "WARN"
            Log.ERROR -> "ERROR"
            Log.ASSERT -> "ASSERT"
            else -> "VERBOSE"
        }

        // Final Format: "2026-01-27 10:00:01.123 [OFFER_EVAL] INFO/OfferParser: Parsing complete"
        val safeTag = tag ?: "NoTag"
        val logLine = StringBuilder("$timestamp [$state] $level/$safeTag: $message")

        // Append Stack Trace if it exists
        if (t != null) {
            logLine.append("\n").append(Log.getStackTraceString(t))
        }
        logLine.append("\n")

        // Send to the Repo to be written to disk
        logRepository.appendLog(logLine.toString())
    }
}
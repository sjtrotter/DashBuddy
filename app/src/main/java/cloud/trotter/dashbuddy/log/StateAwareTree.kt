package cloud.trotter.dashbuddy.log

import android.util.Log
import cloud.trotter.dashbuddy.core.data.log.LogRepository
import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
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
 * 4. Sends them to the [cloud.trotter.dashbuddy.core.data.log.LogRepository] for file storage.
 */
class StateAwareTree(
    private val logRepository: LogRepository,
    private val devSettingsRepository: DevSettingsRepository,
    private val stateProvider: Provider<String> // Lazy access to state to avoid circular dependencies
) : Timber.Tree() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // 1. Check Log Level Gate
        if (priority < devSettingsRepository.minLogLevel.value) return

        // 2. Tag (#367): explicit tags pass through; untagged lines get a fixed
        // marker. The old per-line Throwable().stackTrace walk cost a stack
        // capture on EVERY log in ALL builds — logcat (DebugTree) still infers
        // call-site tags in debug; the file log doesn't need them.
        val finalTag = tag ?: "App"

        val timestamp = dateFormat.format(Date())

        // Safely get the current state name (e.g., "IDLE", "DASHING")
        // We use a Provider so we get the *fresh* state at the exact moment of logging.
        val state = try {
            stateProvider.get()
        } catch (_: Exception) {
            "UNKNOWN"
        }

        val level = when (priority) {
            Log.VERBOSE -> "VERBOSE"
            Log.DEBUG -> "DEBUG"
            Log.INFO -> "INFO"
            Log.WARN -> "WARN"
            Log.ERROR -> "ERROR"
            Log.ASSERT -> "ASSERT"
            else -> "UNKNOWN"
        }

        // Final Format: "2026-01-27 10:00:01.123 [OFFER_EVAL] INFO/OfferParser: Parsing complete"
        val logLine = StringBuilder("$timestamp [$state] $level/$finalTag: $message")

        // Append Stack Trace if it exists
        if (t != null) {
            logLine.append("\n").append(Log.getStackTraceString(t))
        }
        logLine.append("\n")

        // Send to the Repo to be written to disk. The priority routes the line to the two sinks
        // (#551): every line hits the firehose; INFO+ additionally flows through the fail-closed
        // scrub into the shareable/export sink.
        logRepository.appendLog(logLine.toString(), priority)
    }

}
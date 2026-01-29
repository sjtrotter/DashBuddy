package cloud.trotter.dashbuddy.log

import android.util.Log
import cloud.trotter.dashbuddy.data.log.LogRepository
import cloud.trotter.dashbuddy.data.settings.SettingsRepository
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
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
    private val settingsRepository: SettingsRepository,
    private val stateProvider: Provider<String> // Lazy access to state to avoid circular dependencies
) : Timber.Tree() {

    private val anonymousClassPattern = Pattern.compile("(\\$\\d+)+$")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // 1. Check Log Level Gate
        if (priority < settingsRepository.minLogLevel.value) return

        // 2. Auto-Generate Tag if missing
        val finalTag = tag ?: createStackElementTag()

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
            Log.VERBOSE -> "VERBOSE"
            else -> "UNKNOWN"
        }

        // Final Format: "2026-01-27 10:00:01.123 [OFFER_EVAL] INFO/OfferParser: Parsing complete"
        val logLine = StringBuilder("$timestamp [$state] $level/$finalTag: $message")

        // Append Stack Trace if it exists
        if (t != null) {
            logLine.append("\n").append(Log.getStackTraceString(t))
        }
        logLine.append("\n")

        // Send to the Repo to be written to disk
        logRepository.appendLog(logLine.toString())
    }

    /**
     * Inspects the stack trace to figure out which class called Timber.
     */
    private fun createStackElementTag(): String {
        val stackTrace = Throwable().stackTrace
        if (stackTrace.size <= 5) return "Unknown"

        // We look for the first stack element that isn't Timber itself.
        // Usually index 5 or 6 in the trace is the caller.
        for (element in stackTrace) {
            val className = element.className
            if (!className.startsWith("timber.log.Timber") &&
                !className.startsWith("cloud.trotter.dashbuddy.log.StateAwareTree")
            ) {
                var tag = className.substringAfterLast('.')
                val m = anonymousClassPattern.matcher(tag)
                if (m.find()) {
                    tag = m.replaceAll("")
                }
                return tag
            }
        }
        return "Unknown"
    }
}
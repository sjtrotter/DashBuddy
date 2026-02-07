package cloud.trotter.dashbuddy.data.log.clicks

import android.content.Context
import cloud.trotter.dashbuddy.state.event.ClickEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClickLogRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    // 1. Setup Async Scope (Same as SnapshotRepo)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 2. Formatters
    private val filenameFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US) // "2026-02-05"
    private val prettyTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // 3. JSON Config (Compact output for logs)
    private val json = Json {
        prettyPrint = false // Keep it on one line per entry (JSONL)
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val logDir by lazy {
        File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "logs/clicks"
        ).apply { mkdirs() }
    }

    /**
     * Appends a click event to today's log file.
     */
    fun log(event: ClickEvent) {
        scope.launch {
            try {
                writeToDisk(event)
                // Optional: Prune old logs if needed
                pruneOldLogs()
            } catch (e: Exception) {
                Timber.e(e, "Failed to log click event")
            }
        }
    }

    private suspend fun writeToDisk(event: ClickEvent) = withContext(Dispatchers.IO) {
        val now = Date(event.timestamp)

        // File: "2026-02-05_clicks.jsonl"
        val filename = "${filenameFormat.format(now)}_clicks.jsonl"
        val file = File(logDir, filename)

        // Wrapper
        val entry = ClickLogEntry(
            timestamp = event.timestamp,
            dateReadable = prettyTimeFormat.format(now),
            action = event.action,
            targetId = event.sourceNode.viewIdResourceName,
            targetText = event.sourceNode.text,
            sourceNode = event.sourceNode
        )

        // Serialize to single line
        val jsonLine = json.encodeToString(entry)

        // Append with newline
        file.appendText("$jsonLine\n")

        Timber.v("📝 Click logged to $filename")
    }

    /**
     * Keep only the last 7 days of logs to save space.
     */
    private fun pruneOldLogs() {
        val files = logDir.listFiles() ?: return
        if (files.size > 7) {
            files.sortedBy { it.lastModified() }
                .take(files.size - 7)
                .forEach {
                    it.delete()
                    Timber.d("♻️ Pruned old click log: ${it.name}")
                }
        }
    }
}
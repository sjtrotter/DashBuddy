package cloud.trotter.dashbuddy.core.data.log

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import cloud.trotter.dashbuddy.domain.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel

/**
 * Two-sink log store (#551, CLAUDE.md Principle 7 — "the log is two products"):
 *
 *  - **[appLogFile] — the DEBUG firehose.** Every line, verbatim, at every level. On-device only,
 *    never exported. Large rotation budget.
 *  - **[shareableLogFile] — the PII-safe INFO+ slice.** Only lines whose priority ≥ [Log.INFO],
 *    each passed through the fail-closed [scrubber] sink-gate before it lands. This is the stream a
 *    user can export as a bug report ([shareableLogContents]).
 *
 * Both files are fed from the SAME single-writer channel (#364) so ordering and rotation stay
 * race-free — one queued item routes to one or both files inside one worker; there is no second
 * channel to interleave with.
 *
 * **The sink-gate is the load-bearing privacy control.** A raw merchant/customer/address string
 * reaching an INFO+ line is a privacy defect of the same class as leaking it to disk (Principle 6/7);
 * the #590 replay gate patrols the upstream path, but this sink must NOT trust call-site discipline —
 * every INFO+ line is scanned here, last. It fails closed three ways: the [scrubber] itself fails
 * closed internally, any throw out of the scrubber call is treated as a hit, and an entirely
 * **unbound (`null`) scrubber writes NOTHING to the shareable file** — a bug report with no lines is
 * safe; one with un-scrubbed lines is not.
 */
@Singleton
class LogRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
    /**
     * Fail-closed PII scrub for the shareable sink. `null` means unbound → the shareable file is
     * never written (fail closed). Bound in `:app` DI to `SensitiveTextMarkers::findMarker` so the
     * marker SSOT stays in `:core:pipeline` without a module edge (see [LogScrubber]).
     */
    private val scrubber: LogScrubber? = null,
) {
    // Run file operations on IO thread to never block the main thread
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    private data class LogLine(val text: String, val priority: Int)

    // Single-writer queue (#364): per-line fire-and-forget launches could land
    // out of order and race the rotation check. One worker drains in order,
    // routing each line to the firehose and (if INFO+) the shareable sink.
    private val lines = Channel<LogLine>(Channel.UNLIMITED)

    /**
     * Count of INFO+ lines the sink-gate redacted before they reached [shareableLogFile]. Surfaced
     * in the export UI ("N lines were auto-scrubbed") — a non-zero value means an upstream call site
     * leaked raw text that the sink caught. Counter only; never the scrubbed content (Principle 7).
     */
    private val scrubbedCounter = AtomicInteger(0)
    val autoScrubbedLineCount: Int get() = scrubbedCounter.get()

    init {
        scope.launch {
            for (item in lines) {
                writeFirehose(item.text)
                if (item.priority >= Log.INFO) writeShareable(item.text)
            }
        }
    }

    /** Firehose: verbatim, every line. The DEBUG product; on-device only, never exported. */
    private fun writeFirehose(line: String) {
        try {
            if (appLogFile.exists() && appLogFile.length() > maxLogFileSizeBytes) {
                rotateLogFile()
            }
            appLogFile.appendText(line)
        } catch (_: Exception) {
            // Fail silently to avoid crash loops
        }
    }

    /**
     * Shareable sink: fail-closed scrub, then append. Unbound scrubber → write nothing. A scrubber
     * hit (or any throw out of it) → append a redacted placeholder that carries only the safe marker
     * name plus the line's own timestamp prefix (never the original message), and bump the counter.
     */
    private fun writeShareable(line: String) {
        val scrub = scrubber ?: return // unbound → fail closed: no shareable output at all
        val marker: String? = try {
            scrub.findMarker(line)
        } catch (_: Throwable) {
            // A throwing scrubber is treated exactly as a hit — never fall through to verbatim.
            SCRUBBER_THREW
        }
        val outLine = if (marker != null) {
            scrubbedCounter.incrementAndGet()
            redactedPlaceholder(line, marker)
        } else {
            line
        }
        try {
            if (shareableLogFile.exists() && shareableLogFile.length() > maxShareableLogBytes) {
                rotateShareableLog()
            }
            shareableLogFile.appendText(outLine)
        } catch (_: Exception) {
            // Fail silently to avoid crash loops
        }
    }

    /**
     * Build the redacted stand-in for a scrubbed line. Keeps the leading timestamp (cheaply
     * available: everything before the ` [state]` bracket the [StateAwareTree] format inserts) so
     * ordering context survives, and appends only the safe [marker] constant. FAIL-SAFE: if the line
     * has no such delimiter the prefix is empty rather than the whole (un-scrubbed) line.
     */
    private fun redactedPlaceholder(line: String, marker: String): String {
        val prefix = line.substringBefore(" [", missingDelimiterValue = "")
        return if (prefix.isEmpty()) "[scrubbed:$marker]\n" else "$prefix [scrubbed:$marker]\n"
    }

    // --- Config ---
    // Uses internal storage (filesDir) or external if available
    private val logDir by lazy { context.getExternalFilesDir(null) ?: context.filesDir }

    private val appLogFile by lazy { File(logDir, "app.log") }

    /** INFO+ export sink. Smaller budget (INFO+ only) with a single rotated backup. */
    private val shareableLogFile by lazy { File(logDir, "shareable.log") }
    private val shareableRotatedFile by lazy { File(logDir, "shareable.log.1") }

    private val maxLogFileSizeBytes = 2.3 * 1024 * 1024 // 2.3 MB
    private val maxShareableLogBytes = 2.0 * 1024 * 1024 // 2.0 MB (INFO+ only)
    private val maxRotatedLogs = 50
    private val rotationPrefix = "app_log_rotated_"

    // Format for log rotation: app_log_rotated_20260127_143000.log
    // DateTimeFormatter (#406): thread-safe, consistent with DiskCaptureBus.
    private val logRotationFormat = DateTimeFormatter
        .ofPattern("yyyyMMdd_HHmmss", Locale.US)
        .withZone(ZoneId.systemDefault())

    /**
     * Appends a pre-formatted line to the log. Always written to the firehose; also written to the
     * PII-safe shareable sink (after the fail-closed scrub) when [priority] ≥ [Log.INFO].
     */
    fun appendLog(line: String, priority: Int = Log.DEBUG) {
        lines.trySend(LogLine(line, priority))
    }

    /**
     * Current contents of the PII-safe shareable log (INFO+, scrubbed) for the bug-report export
     * (#551). Best-effort snapshot — empty string when nothing has been written yet, so the export
     * can still emit a header-only file (consistent with the CSV empty behavior).
     */
    fun shareableLogContents(): String =
        runCatching { if (shareableLogFile.exists()) shareableLogFile.readText() else "" }
            .getOrDefault("")

    /**
     * Renames the current firehose file and starts a new one.
     */
    private fun rotateLogFile() {
        try {
            val timestamp = logRotationFormat.format(Instant.now())
            val rotatedName = "${rotationPrefix}${timestamp}.log"
            val rotatedFile = File(logDir, rotatedName)

            // Rename current -> rotated
            if (appLogFile.renameTo(rotatedFile)) {
                appLogFile.appendText("--- Log Rotated from ${rotatedFile.name} ---\n")
                pruneOldLogs()
            }
        } catch (_: Exception) {
            // If rotation fails, just keep appending
        }
    }

    /**
     * Shareable-sink rotation: keep exactly one backup (INFO+ is low-volume). Overwrite any prior
     * backup, rename current → backup, start fresh.
     */
    private fun rotateShareableLog() {
        try {
            shareableRotatedFile.delete()
            shareableLogFile.renameTo(shareableRotatedFile)
        } catch (_: Exception) {
            // If rotation fails, just keep appending
        }
    }

    /**
     * Deletes the oldest rotated logs if we have too many.
     */
    private fun pruneOldLogs() {
        val files = logDir.listFiles { file ->
            file.name.startsWith(rotationPrefix)
        } ?: return

        if (files.size > maxRotatedLogs) {
            // Sort by Last Modified (Oldest first)
            val sortedFiles = files.sortedBy { it.lastModified() }
            val deleteCount = files.size - maxRotatedLogs

            // Delete the oldest N files
            sortedFiles.take(deleteCount).forEach { it.delete() }
        }
    }

    private companion object {
        /** Marker used when the scrubber itself throws (never the scanned text). */
        const val SCRUBBER_THREW = "scrubber-error"
    }
}

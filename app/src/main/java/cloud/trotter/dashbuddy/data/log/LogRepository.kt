package cloud.trotter.dashbuddy.data.log

import android.content.Context
import cloud.trotter.dashbuddy.data.log.dash.DashLogRepo
import cloud.trotter.dashbuddy.data.log.debug.DebugLogRepo
import cloud.trotter.dashbuddy.pipeline.model.UiNode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val debugLogRepo: DebugLogRepo, // For the Bubble UI
    private val dashLogRepo: DashLogRepo    // For the User Chat
) {
    // Run file operations on IO thread to never block the main thread
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // --- Config ---
    // Uses internal storage (filesDir) or external if available
    private val logDir by lazy { context.getExternalFilesDir(null) ?: context.filesDir }

    private val appLogFile by lazy { File(logDir, "app.log") }

    // Dedicated folder just for UI dumps
    private val snapshotDir by lazy {
        File(logDir, "snapshots").apply { mkdirs() }
    }

    private val maxLogFileSizeBytes = 2.3 * 1024 * 1024 // 2.3 MB
    private val maxRotatedLogs = 50
    private val rotationPrefix = "app_log_rotated_"

    // Format for log rotation: app_log_rotated_20260127_143000.log
    private val logRotationFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    // Format for Snapshots: 20260127_143000_123_OfferScreen.json
    // We include Milliseconds (SSS) to keep order if multiple snapshots happen quickly
    private val snapshotFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    /**
     * Appends a pre-formatted line to the main log file.
     * Handles rotation if file gets too big.
     */
    fun appendLog(line: String) {
        scope.launch {
            try {
                // Check if rotation is needed BEFORE writing
                if (appLogFile.exists() && appLogFile.length() > maxLogFileSizeBytes) {
                    rotateLogFile()
                }

                appLogFile.appendText(line)

                // Optional: Push to Debug Bubble
                debugLogRepo.addLogMessage(line.trim())

            } catch (_: Exception) {
                // Fail silently to avoid crash loops
            }
        }
    }

    /**
     * Saves a UI snapshot to the Evidence Locker.
     * @param node The root UiNode to save.
     * @param tag The screen type or reason (e.g. "OFFER_EXPANDED", "ERROR_PARSING")
     */
    fun saveSnapshot(node: UiNode, tag: String) {
        scope.launch {
            try {
                val timestamp = snapshotFormat.format(Date())
                // Result: 20260127_143501_555_OFFER_EXPANDED.json
                val filename = "${timestamp}_${tag}.json"
                val file = File(snapshotDir, filename)

                // Use the built-in serialization we added to UiNode
                file.writeText(node.toJson())

                // Log a reference so we know where to find it
                appendLog("INFO/LogRepo: 📸 Saved UI Snapshot: ${file.name}\n")

            } catch (e: Exception) {
                appendLog("ERROR/LogRepo: Failed to save snapshot: ${e.message}\n")
            }
        }
    }

    /**
     * Renames the current log file and starts a new one.
     */
    private fun rotateLogFile() {
        try {
            val timestamp = logRotationFormat.format(Date())
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
}
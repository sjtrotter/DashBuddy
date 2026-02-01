package cloud.trotter.dashbuddy.data.log

import android.content.Context
import cloud.trotter.dashbuddy.data.log.debug.DebugLogRepo
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
    private val debugLogRepo: DebugLogRepo // For the Bubble UI
) {
    // Run file operations on IO thread to never block the main thread
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // --- Config ---
    // Uses internal storage (filesDir) or external if available
    private val logDir by lazy { context.getExternalFilesDir(null) ?: context.filesDir }

    private val appLogFile by lazy { File(logDir, "app.log") }

    private val maxLogFileSizeBytes = 2.3 * 1024 * 1024 // 2.3 MB
    private val maxRotatedLogs = 50
    private val rotationPrefix = "app_log_rotated_"

    // Format for log rotation: app_log_rotated_20260127_143000.log
    private val logRotationFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

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
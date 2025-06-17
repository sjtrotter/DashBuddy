package cloud.trotter.dashbuddy.log

import android.content.Context
import android.content.SharedPreferences
import cloud.trotter.dashbuddy.data.log.debug.DebugLogRepo
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import cloud.trotter.dashbuddy.log.Level as LogLevel

object Logger : SharedPreferences.OnSharedPreferenceChangeListener {

    private const val LOG_FILE_NAME = "app_log.txt"
    private const val ROTATED_LOG_FILE_PREFIX = "app_log_rotated_"
    private const val MAX_FILE_SIZE_MB_DEFAULT = 2.3
    private const val MAX_ROTATED_FILES_DEFAULT = 20

    private var currentLogFile: File? = null
    private var logDirectory: File? = null
    private var maxFileSizeInBytes: Long = (MAX_FILE_SIZE_MB_DEFAULT * 1024 * 1024).toLong()
    private var maxRotatedLogFiles: Int = MAX_ROTATED_FILES_DEFAULT

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileTimestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    private val lock = Any()

    private var sharedPreferences: SharedPreferences? = null
    private lateinit var logLevelPrefKey: String
    private lateinit var bubbleDebugModePrefKey: String

    @Volatile
    private var currentActiveLogLevel: LogLevel = LogLevel.INFO

    @Volatile
    var isBubbleDebugOutputEnabled: Boolean = false
        private set

    fun initialize(
        context: Context,
        prefs: SharedPreferences,
        logLevelPreferenceKey: String = "logLevel",
        bubbleDebugModeEnableKey: String = "debugMode",
        initialDefaultLogLevel: LogLevel = LogLevel.INFO,
        initialBubbleDebugStatus: Boolean = false,
    ) {
        synchronized(lock) {
            if (logDirectory != null) {
                android.util.Log.w("Logger", "Logger already initialized.")
                return
            }
            this.sharedPreferences = prefs
            this.logLevelPrefKey = logLevelPreferenceKey
            this.bubbleDebugModePrefKey = bubbleDebugModeEnableKey
            this.currentActiveLogLevel = initialDefaultLogLevel
            this.isBubbleDebugOutputEnabled = initialBubbleDebugStatus

            try {
                logDirectory = context.getExternalFilesDir(null)
                if (logDirectory == null) {
                    android.util.Log.e(
                        "Logger",
                        "Failed to get external files directory. File logging disabled."
                    )
                    return
                }
                if (!logDirectory!!.exists()) {
                    logDirectory!!.mkdirs()
                }
                currentLogFile = File(logDirectory, LOG_FILE_NAME)
                android.util.Log.i("Logger", "File logging to: ${currentLogFile?.absolutePath}")

                updateLogLevelFromPrefs()
                updateBubbleDebugStatusFromPrefs()

                sharedPreferences?.registerOnSharedPreferenceChangeListener(this)

                writeToFileInternal(
                    LogLevel.INFO,
                    "Logger",
                    "--- Log Session Started (Console/File Level: $currentActiveLogLevel, BubbleDebugOutput: $isBubbleDebugOutputEnabled) ---",
                    null
                )
                pruneOldLogs()
            } catch (e: Exception) {
                android.util.Log.e("Logger", "Error initializing Logger", e)
                logDirectory = null
                currentLogFile = null
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            logLevelPrefKey -> {
                val oldLevel = currentActiveLogLevel
                updateLogLevelFromPrefs()
                if (oldLevel != currentActiveLogLevel) {
                    val message =
                        "Console/File Log level updated from $oldLevel to: $currentActiveLogLevel"
                    i("Logger", message)
                    writeToFileInternal(LogLevel.INFO, "Logger", "--- $message ---", null)
                }
            }

            bubbleDebugModePrefKey -> {
                val oldStatus = isBubbleDebugOutputEnabled
                updateBubbleDebugStatusFromPrefs()
                if (oldStatus != isBubbleDebugOutputEnabled) {
                    val statusMsg = if (isBubbleDebugOutputEnabled) "ENABLED" else "DISABLED"
                    val message = "Bubble Debug UI Output $statusMsg"
                    i("Logger", message)
                    writeToFileInternal(LogLevel.INFO, "Logger", "--- $message ---", null)
                }
            }
        }
    }

    /**
     * Helper function to prepare text lists for clean, single-line logging.
     * It replaces newline characters with their escaped representation.
     */
    private fun formatTextsForLogging(msg: String): String {
        return msg.replace("\n", "\\n")
    }

    private fun updateLogLevelFromPrefs() {
        val savedLevelName = sharedPreferences?.getString(logLevelPrefKey, null)
        val newLevel = if (savedLevelName != null) {
            try {
                LogLevel.valueOf(savedLevelName)
            } catch (e: IllegalArgumentException) {
                android.util.Log.w(
                    "Logger",
                    "Invalid log level string '$savedLevelName' in SharedPreferences. Using previous."
                )
                currentActiveLogLevel
            }
        } else {
            currentActiveLogLevel
        }
        currentActiveLogLevel = newLevel
    }

    private fun updateBubbleDebugStatusFromPrefs() {
        isBubbleDebugOutputEnabled =
            sharedPreferences?.getBoolean(bubbleDebugModePrefKey, false) ?: false
    }

    fun v(tag: String, msg: String, tr: Throwable? = null) = log(LogLevel.VERBOSE, tag, msg, tr)
    fun d(tag: String, msg: String, tr: Throwable? = null) = log(LogLevel.DEBUG, tag, msg, tr)
    fun i(tag: String, msg: String, tr: Throwable? = null) = log(LogLevel.INFO, tag, msg, tr)
    fun w(tag: String, msg: String, tr: Throwable? = null) = log(LogLevel.WARN, tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log(LogLevel.ERROR, tag, msg, tr)

    private fun log(level: LogLevel, tag: String, msg: String, tr: Throwable? = null) {
        // --- Console/File Logging ---
        // Governed by currentActiveLogLevel
        if (level.ordinal >= currentActiveLogLevel.ordinal) {
            val androidLogLevel = when (level) {
                LogLevel.VERBOSE -> android.util.Log.VERBOSE
                LogLevel.DEBUG -> android.util.Log.DEBUG
                LogLevel.INFO -> android.util.Log.INFO
                LogLevel.WARN -> android.util.Log.WARN
                LogLevel.ERROR -> android.util.Log.ERROR
            }
            val safeTag = if (tag.length > 23) tag.substring(0, 23) else tag
            if (tr != null) {
                android.util.Log.println(
                    androidLogLevel,
                    safeTag,
                    "$msg\n${android.util.Log.getStackTraceString(tr)}"
                )
            } else {
                android.util.Log.println(androidLogLevel, safeTag, msg)
            }
            writeToFileInternal(level, safeTag, msg, tr)
        }

        // --- Bubble Debug UI Logging ---
        // Governed by isBubbleDebugOutputEnabled flag
        if (isBubbleDebugOutputEnabled) {
            // Send ALL levels of logs to the debug repo if debug mode is on
            // This ensures you see everything in the UI when debugging.
            val timestamp = dateFormat.format(Date())
            var logEntryForUi = "$timestamp ${level.name}/$tag: $msg"
            tr?.let {
                val sw = StringWriter()
                it.printStackTrace(PrintWriter(sw))
                logEntryForUi += "\n${sw}"
            }
            // Call the singleton DebugLogRepo to add the formatted message
            DebugLogRepo.addLogMessage(logEntryForUi)
        }
    }

    // ... writeToFileInternal and other private methods remain the same ...
    private fun writeToFileInternal(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?
    ) {
        synchronized(lock) {
            if (currentLogFile == null || logDirectory == null) return
            try {
                val timestamp = dateFormat.format(Date())
                var logEntry = "$timestamp ${level.name}/$tag: ${formatTextsForLogging(message)}\n"
                throwable?.let {
                    val sw =
                        StringWriter(); it.printStackTrace(PrintWriter(sw)); logEntry += "$sw\n"
                }
                currentLogFile!!.appendText(logEntry)
                if (currentLogFile!!.length() > maxFileSizeInBytes) {
                    rotateLogFile()
                } else {
                    // No need to rotate, just log
                }
            } catch (e: Exception) {
                android.util.Log.e("Logger", "Error writing to log file", e)
            }
        }
    }

    private fun rotateLogFile() {
        android.util.Log.i("Logger", "Rotating log file: ${currentLogFile?.name}")
        val timestamp = fileTimestampFormat.format(Date())
        val rotatedFileName =
            "${ROTATED_LOG_FILE_PREFIX}${timestamp}.${LOG_FILE_NAME.substringAfterLast('.', "")}"
        val rotatedFile = File(logDirectory, rotatedFileName)
        try {
            currentLogFile?.renameTo(rotatedFile); android.util.Log.i(
                "Logger",
                "Log file rotated to: ${rotatedFile.name}"
            )
        } catch (e: Exception) {
            android.util.Log.e("Logger", "Error rotating log file", e)
        }
        pruneOldLogs()
    }

    private fun pruneOldLogs() {
        val rotatedFiles = logDirectory?.listFiles { file ->
            file.name.startsWith(ROTATED_LOG_FILE_PREFIX) && file.name.endsWith(
                LOG_FILE_NAME.substringAfterLast(
                    '.',
                    ""
                )
            )
        }?.sortedByDescending { it.lastModified() }
        if (rotatedFiles != null && rotatedFiles.size > maxRotatedLogFiles) {
            android.util.Log.i(
                "Logger",
                "Pruning. Max: $maxRotatedLogFiles, Found: ${rotatedFiles.size}"
            )
            rotatedFiles.subList(maxRotatedLogFiles, rotatedFiles.size)
                .forEach { android.util.Log.d("Logger", "Would delete: ${it.name}") }
        }
    }

    fun close() {
        synchronized(lock) {
            writeToFileInternal(LogLevel.INFO, "Logger", "--- Log Session Ended ---", null)
            sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            logDirectory = null; currentLogFile = null; android.util.Log.i(
            "Logger",
            "Logger closed."
        )
        }
    }
}

package cloud.trotter.dashbuddy.log

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
    private const val MAX_FILE_SIZE_MB_DEFAULT = 2.5
    private const val MAX_ROTATED_FILES_DEFAULT = 5 // Keeps 5 old files + 1 current

    private var currentLogFile: File? = null
    private var logDirectory: File? = null
    private var maxFileSizeInBytes: Long = (MAX_FILE_SIZE_MB_DEFAULT * 1024 * 1024).toLong()
    private var maxRotatedLogFiles: Int = MAX_ROTATED_FILES_DEFAULT

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileTimestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    private val lock = Any() // For synchronizing file access

    // --- SharedPreferences related properties ---
    private var sharedPreferences: SharedPreferences? = null
    private var debugModePrefKey: String = "debug_logging_enabled" // Default key
    private var appDefaultLogLevel: LogLevel = LogLevel.INFO // Level when debug mode is OFF
    private var appDebugLogLevel: LogLevel = LogLevel.DEBUG   // Level when debug mode is ON

    @Volatile
    private var currentActiveLogLevel: LogLevel = appDefaultLogLevel // Initial default

    /**
     * Initializes the FileLogger. Call this once from Application.onCreate().
     *
     * @param context The application context.
     * @param prefs The SharedPreferences instance for reading debug mode.
     * @param debugModePreferenceKey The key for the boolean debug mode preference.
     * @param defaultLogLevel Log level when debug mode is OFF.
     * @param debugLogLevel Log level when debug mode is ON.
     * @param maxFileSizeMb Max size of the current log file in MB before rotation.
     * @param maxNumRotatedFiles Max number of rotated log files to keep.
     */
    fun initialize(
        context: Context,
        prefs: SharedPreferences,
        debugModePreferenceKey: String = "debugMode",
        defaultLogLevel: LogLevel = LogLevel.INFO,
        debugLogLevel: LogLevel = LogLevel.DEBUG,
        maxFileSizeMb: Double = MAX_FILE_SIZE_MB_DEFAULT,
        maxNumRotatedFiles: Int = MAX_ROTATED_FILES_DEFAULT
    ) {
        synchronized(lock) {
            if (logDirectory != null) {
                Log.w("FileLogger", "FileLogger already initialized.")
                return
            }
            this.sharedPreferences = prefs
            this.debugModePrefKey = debugModePreferenceKey
            this.appDefaultLogLevel = defaultLogLevel
            this.appDebugLogLevel = debugLogLevel
            this.maxFileSizeInBytes = (maxFileSizeMb * 1024 * 1024).toLong()
            this.maxRotatedLogFiles = maxNumRotatedFiles

            try {
                logDirectory = context.getExternalFilesDir(null) // App-specific storage
                if (logDirectory == null) {
                    Log.e(
                        "FileLogger",
                        "Failed to get external files directory. File logging disabled."
                    )
                    return
                }
                if (!logDirectory!!.exists()) {
                    logDirectory!!.mkdirs()
                }
                currentLogFile = File(logDirectory, LOG_FILE_NAME)
                Log.i("FileLogger", "Logging to: ${currentLogFile?.absolutePath}")

                updateLogLevelFromPrefs() // Set initial log level
                sharedPreferences?.registerOnSharedPreferenceChangeListener(this)

                writeToFileInternal(
                    LogLevel.INFO,
                    "FileLogger",
                    "--- Log Session Started (Level: $currentActiveLogLevel) ---",
                    null
                )
                pruneOldLogs() // Prune on init in case of leftover files from previous crashes
            } catch (e: Exception) {
                Log.e("FileLogger", "Error initializing FileLogger", e)
                logDirectory = null
                currentLogFile = null
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == debugModePrefKey) {
            updateLogLevelFromPrefs()
            Log.i("Logger", "Log level updated due to preference change to: $currentActiveLogLevel")
            writeToFileInternal(
                LogLevel.INFO,
                "Logger",
                "--- Log Level Changed to: $currentActiveLogLevel ---",
                null
            )
        }
    }

    private fun updateLogLevelFromPrefs() {
        val isDebugMode = sharedPreferences?.getBoolean(debugModePrefKey, false) ?: false
        currentActiveLogLevel = if (isDebugMode) appDebugLogLevel else appDefaultLogLevel
    }

    // Public logging methods
    fun v(tag: String, msg: String, tr: Throwable? = null) = log(LogLevel.VERBOSE, tag, msg, tr)
    fun d(tag: String, msg: String, tr: Throwable? = null) = log(LogLevel.DEBUG, tag, msg, tr)
    fun i(tag: String, msg: String, tr: Throwable? = null) = log(LogLevel.INFO, tag, msg, tr)
    fun w(tag: String, msg: String, tr: Throwable? = null) = log(LogLevel.WARN, tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log(LogLevel.ERROR, tag, msg, tr)

    private fun log(level: LogLevel, tag: String, msg: String, tr: Throwable? = null) {
        if (level.ordinal >= currentActiveLogLevel.ordinal) {
            val androidLogLevel = when (level) {
                LogLevel.VERBOSE -> Log.VERBOSE
                LogLevel.DEBUG -> Log.DEBUG
                LogLevel.INFO -> Log.INFO
                LogLevel.WARN -> Log.WARN
                LogLevel.ERROR -> Log.ERROR
            }
            if (tr != null) {
                Log.println(androidLogLevel, tag, "$msg\n${Log.getStackTraceString(tr)}")
            } else {
                Log.println(androidLogLevel, tag, msg)
            }
            writeToFileInternal(level, tag, msg, tr)
        }
    }

    private fun writeToFileInternal(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?
    ) {
        synchronized(lock) {
            if (currentLogFile == null || logDirectory == null) {
                // Log to Logcat only if file logging isn't initialized
                // Log.w("FileLogger", "File logger not initialized. Skipping file write for: $level/$tag: $message")
                return
            }

            try {
                val timestamp = dateFormat.format(Date())
                var logEntry = "$timestamp ${level.name}/$tag: $message\n"
                throwable?.let {
                    val sw = StringWriter()
                    it.printStackTrace(PrintWriter(sw))
                    logEntry += "$sw\n"
                }

                currentLogFile!!.appendText(logEntry)

                if (currentLogFile!!.length() > maxFileSizeInBytes) {
                    rotateLogFile()
                } else {
                    // do nothing
                }
            } catch (e: Exception) {
                Log.e("FileLogger", "Error writing to log file", e)
            }
        }
    }

    private fun rotateLogFile() {
        // This function assumes it's called from within a synchronized(lock) block
        Log.i("FileLogger", "Rotating log file: ${currentLogFile?.name}")
        val timestamp = fileTimestampFormat.format(Date())
        val rotatedFileName =
            "${ROTATED_LOG_FILE_PREFIX}${timestamp}.${LOG_FILE_NAME.substringAfterLast('.', "")}"
        val rotatedFile = File(logDirectory, rotatedFileName)

        try {
            currentLogFile?.renameTo(rotatedFile)
            Log.i("FileLogger", "Log file rotated to: ${rotatedFile.name}")
            // currentLogFile remains pointing to DEFAULT_LOG_FILE_NAME,
            // it will be created anew on the next write.
        } catch (e: Exception) {
            Log.e("FileLogger", "Error rotating log file", e)
        }
        pruneOldLogs()
    }

    private fun pruneOldLogs() {
        // This function assumes it's called from within a synchronized(lock) block
        // User preference: "don't want to run those [cutoff mechanisms] yet."
        // So, for now, we'll just log what would happen.
        // In a full implementation, you would delete files here.

        val rotatedFiles = logDirectory?.listFiles { file ->
            file.name.startsWith(ROTATED_LOG_FILE_PREFIX) && file.name.endsWith(
                LOG_FILE_NAME.substringAfterLast(
                    '.',
                    ""
                )
            )
        }?.sortedByDescending { it.lastModified() } // Newest first

        if (rotatedFiles != null && rotatedFiles.size > maxRotatedLogFiles) {
            Log.i(
                "FileLogger",
                "Pruning old logs. Max rotated files: $maxRotatedLogFiles, Found: ${rotatedFiles.size}"
            )
            val filesToDelete = rotatedFiles.subList(maxRotatedLogFiles, rotatedFiles.size)
            for (fileToDelete in filesToDelete) {
                Log.d(
                    "FileLogger",
                    "Would delete old log file: ${fileToDelete.name} (Size: ${fileToDelete.length()} bytes)"
                )
                // To actually delete:
                // if (fileToDelete.delete()) {
                //     Log.i("FileLogger", "Deleted old log file: ${fileToDelete.name}")
                // } else {
                //     Log.w("FileLogger", "Failed to delete old log file: ${fileToDelete.name}")
                // }
            }
        } else if (rotatedFiles != null) {
            Log.d(
                "FileLogger",
                "No pruning needed. Rotated files: ${rotatedFiles.size}, Max allowed: $maxRotatedLogFiles"
            )
        }
    }

    /**
     * For explicitly closing resources if needed, e.g., when application is terminating.
     * Not strictly necessary with appendText, but good for flushing.
     */
    fun close() {
        synchronized(lock) {
            writeToFileInternal(LogLevel.INFO, "FileLogger", "--- Log Session Ended ---", null)
            sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            logDirectory = null
            currentLogFile = null
            Log.i("FileLogger", "FileLogger closed.")
        }
    }
}

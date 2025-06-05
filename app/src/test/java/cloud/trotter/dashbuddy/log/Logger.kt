package cloud.trotter.dashbuddy.log

import java.text.SimpleDateFormat
import java.util.Locale
import cloud.trotter.dashbuddy.log.Level as LogLevel


object Logger {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // Public logging methods
    fun v(tag: String, msg: String, tr: Throwable? = null) = log(LogLevel.VERBOSE, tag, msg, tr)
    fun d(tag: String, msg: String, tr: Throwable? = null) = log(LogLevel.DEBUG, tag, msg, tr)
    fun i(tag: String, msg: String, tr: Throwable? = null) = log(LogLevel.INFO, tag, msg, tr)
    fun w(tag: String, msg: String, tr: Throwable? = null) = log(LogLevel.WARN, tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log(LogLevel.ERROR, tag, msg, tr)

    private fun log(level: LogLevel, tag: String, msg: String, tr: Throwable? = null) {
        println("$level/$tag: $msg")
        tr?.printStackTrace()
        return
    }
}

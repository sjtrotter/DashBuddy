package cloud.trotter.dashbuddy.test.util

import android.util.Log
import timber.log.Timber

/**
 * A Timber [Timber.Tree] that records every log call as a [Record] so tests can assert on the
 * level, tag, and (already arg-formatted) message of each line.
 *
 * This is the **seed of the #551 Phase-2 shareable-export sink guard**. Development Principle 7
 * requires INFO+ to be **PII-safe by construction** — a raw merchant/customer/address string in an
 * INFO-or-higher line is a privacy defect of the same class as leaking it to disk. Tests plant this
 * tree, drive a component, and assert that no INFO+ line carries raw third-party UI text while the
 * DEBUG firehose keeps full fidelity. Phase 2 will reuse it to gate the real export sink behind a
 * [cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers]-based fail-closed scan.
 *
 * Usage:
 * ```
 * val tree = RecordingTree()
 * Timber.plant(tree)
 * try { /* drive component */ } finally { Timber.uproot(tree) }
 * tree.assertNoInfoPlusContains("H-E-B")   // shareable stream is clean
 * tree.assertLevelContains(Log.DEBUG, "H-E-B") // firehose kept it
 * ```
 */
class RecordingTree : Timber.Tree() {

    /** One recorded log line. [message] is already formatted (Timber applies args before [log]). */
    data class Record(val priority: Int, val tag: String?, val message: String)

    private val _records = mutableListOf<Record>()

    val records: List<Record> get() = synchronized(_records) { _records.toList() }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        synchronized(_records) { _records += Record(priority, tag, message) }
    }

    /** Records at INFO or higher — the shareable-export candidate stream. */
    fun infoPlus(): List<Record> = records.filter { it.priority >= Log.INFO }

    /**
     * Fail if any INFO+ message contains [needle] — a raw-PII leak into the shareable stream.
     * The fail-closed assertion Principle 7 demands (call-site discipline is not trusted).
     */
    fun assertNoInfoPlusContains(needle: String) {
        val hit = infoPlus().firstOrNull { it.message.contains(needle) }
        check(hit == null) {
            "INFO+ log leaked '$needle': [${levelName(hit!!.priority)}/${hit.tag}] ${hit.message}"
        }
    }

    /** Assert at least one record at exactly [priority] contains [needle] (firehose fidelity kept). */
    fun assertLevelContains(priority: Int, needle: String) {
        val ok = records.any { it.priority == priority && it.message.contains(needle) }
        check(ok) {
            "No ${levelName(priority)} log contained '$needle'. Records: " +
                records.joinToString("\n") { "[${levelName(it.priority)}/${it.tag}] ${it.message}" }
        }
    }

    private fun levelName(priority: Int) = when (priority) {
        Log.VERBOSE -> "VERBOSE"
        Log.DEBUG -> "DEBUG"
        Log.INFO -> "INFO"
        Log.WARN -> "WARN"
        Log.ERROR -> "ERROR"
        else -> "P$priority"
    }
}

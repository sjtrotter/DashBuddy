package cloud.trotter.dashbuddy.domain.census

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Shared counters across the data/network worker and pipeline, without a backwards module edge. */
@Singleton
class CensusUploadStats @Inject constructor() {
    val spooled = AtomicLong()
    val spoolDropped = AtomicLong()
    val spoolCorrupt = AtomicLong()
    val spoolOversized = AtomicLong()
    val uploaded = AtomicLong()
    val duplicate = AtomicLong()
    val uploadFailures = AtomicLong()
    val oversized = AtomicLong()
    val badRequest = AtomicLong()
    private val rejected = ConcurrentHashMap<String, AtomicLong>()

    fun reject(reasons: Map<String, Int>) {
        reasons.forEach { (reason, count) ->
            rejected.computeIfAbsent(reason) { AtomicLong() }.addAndGet(count.toLong())
        }
    }

    fun rejectedCounts(): Map<String, Long> = rejected.mapValues { it.value.get() }.toSortedMap()

    fun summary(): String {
        val values = listOf(spooled, spoolDropped, spoolCorrupt, spoolOversized, uploaded, duplicate, uploadFailures, oversized, badRequest)
            .map { it.get() }
        val reasons = rejectedCounts()
        if (values.all { it == 0L } && reasons.isEmpty()) return ""
        return ",spooled=${values[0]},spoolDropped=${values[1]},corrupt=${values[2]},spoolOversized=${values[3]}," +
            "uploaded=${values[4]},duplicate=${values[5]},uploadFailures=${values[6]},rejected=$reasons," +
            "oversized=${values[7]},badRequest=${values[8]}"
    }
}

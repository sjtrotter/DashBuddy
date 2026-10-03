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
    val keystoreTransient = AtomicLong()
    val inflightCorrupt = AtomicLong()
    val enrolRejected = AtomicLong()
    val unauthorized = AtomicLong()
    val stale = AtomicLong()
    val healthRecorded = AtomicLong()
    val healthSkippedNoVersion = AtomicLong()
    val healthDropped = AtomicLong()
    val healthCorrupt = AtomicLong()
    val healthPosted = AtomicLong()
    val healthRejected = AtomicLong()
    val healthOversized = AtomicLong()
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
        val health = linkedMapOf(
            "healthRecorded" to healthRecorded, "healthSkippedNoVersion" to healthSkippedNoVersion,
            "healthDropped" to healthDropped, "healthCorrupt" to healthCorrupt, "healthPosted" to healthPosted,
            "healthRejected" to healthRejected, "healthOversized" to healthOversized,
        ).mapValues { it.value.get() }.filterValues { it != 0L }
        val reasons = rejectedCounts()
        if (health.isEmpty() && values.all { it == 0L } && reasons.isEmpty() &&
            listOf(keystoreTransient, inflightCorrupt, enrolRejected, unauthorized, stale).all { it.get() == 0L }) return ""
        return ",spooled=${values[0]},spoolDropped=${values[1]},corrupt=${values[2]},spoolOversized=${values[3]}," +
            "uploaded=${values[4]},duplicate=${values[5]},uploadFailures=${values[6]},rejected=$reasons," +
            "keystoreTransient=${keystoreTransient.get()},inflightCorrupt=${inflightCorrupt.get()}," +
            "enrolRejected=${enrolRejected.get()},unauthorized=${unauthorized.get()},stale=${stale.get()}," +
            "oversized=${values[7]},badRequest=${values[8]}" +
            health.entries.joinToString("") { ",${it.key}=${it.value}" }
    }
}

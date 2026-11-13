package cloud.trotter.log.gig.domain.census

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
    val healthSkippedBadVersion = AtomicLong()
    val healthLoadFailures = AtomicLong()
    val healthDropped = AtomicLong()
    val healthRowsRefused = AtomicLong()
    val healthCorrupt = AtomicLong()
    val healthPosted = AtomicLong()
    val healthRejected = AtomicLong()
    val healthFailed = AtomicLong()
    val healthOversized = AtomicLong()
    val envelopesHeld = AtomicLong()
    val envelopesProjectionRefused = AtomicLong()
    val envelopesSensitiveDropped = AtomicLong()
    val envelopesDropped = AtomicLong()
    val envelopesSpooled = AtomicLong()
    val envelopesPosted = AtomicLong()
    val envelopesRejected = AtomicLong()
    val envelopesNotTrusted = AtomicLong()
    /** #1620: a skeleton of a variant already kept today ([CensusSampleKey]), counted and not queued. */
    val sampledRepeat = AtomicLong()
    /** #1620: a skeleton from a delivery app the dasher's census choices leave out. */
    val platformNotChosen = AtomicLong()
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
            "healthSkippedBadVersion" to healthSkippedBadVersion, "healthLoadFailures" to healthLoadFailures,
            "healthRowsRefused" to healthRowsRefused, "healthDropped" to healthDropped, "healthCorrupt" to healthCorrupt, "healthPosted" to healthPosted,
            "healthRejected" to healthRejected, "healthFailed" to healthFailed, "healthOversized" to healthOversized,
        ).mapValues { it.value.get() }.filterValues { it != 0L }
        val envelopes = linkedMapOf(
            "envelopesHeld" to envelopesHeld,
            "envelopesProjectionRefused" to envelopesProjectionRefused,
            "envelopesSensitiveDropped" to envelopesSensitiveDropped,
            "envelopesDropped" to envelopesDropped,
            "envelopesSpooled" to envelopesSpooled,
            "envelopesPosted" to envelopesPosted,
            "envelopesRejected" to envelopesRejected,
            "envelopesNotTrusted" to envelopesNotTrusted,
        ).mapValues { it.value.get() }.filterValues { it != 0L }
        val sampling = linkedMapOf("sampledRepeat" to sampledRepeat, "platformNotChosen" to platformNotChosen)
            .mapValues { it.value.get() }.filterValues { it != 0L }
        val reasons = rejectedCounts()
        if (sampling.isEmpty() && envelopes.isEmpty() && health.isEmpty() && values.all { it == 0L } && reasons.isEmpty() &&
            listOf(keystoreTransient, inflightCorrupt, enrolRejected, unauthorized, stale).all { it.get() == 0L }) return ""
        return ",spooled=${values[0]},spoolDropped=${values[1]},corrupt=${values[2]},spoolOversized=${values[3]}," +
            "uploaded=${values[4]},duplicate=${values[5]},uploadFailures=${values[6]},rejected=$reasons," +
            "keystoreTransient=${keystoreTransient.get()},inflightCorrupt=${inflightCorrupt.get()}," +
            "enrolRejected=${enrolRejected.get()},unauthorized=${unauthorized.get()},stale=${stale.get()}," +
            "oversized=${values[7]},badRequest=${values[8]}" +
            (health + envelopes + sampling).entries.joinToString("") { ",${it.key}=${it.value}" }
    }
}

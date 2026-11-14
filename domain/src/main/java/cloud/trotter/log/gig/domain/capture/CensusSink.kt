package cloud.trotter.log.gig.domain.capture

import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.census.contract.NotifTextField
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.log.gig.domain.census.contributor.ReviewWords

/**
 * One census item the publisher hands the sink (#1146). Carries NO timestamp — the skeleton's
 * own `day` is the only time field (ADR-0011 §8: hour in flight, day at rest).
 */
data class CensusRecord(
    val platform: Platform,
    /** The CLUSTER key (ADR-0011 §8), also carried on the paired envelope sent to the server. */
    val fingerprint: String,
    /** For the envelope spool, this is the projected capture envelope. */
    val skeletonJson: String,
    val itemBytes: Int,
    /**
     * The PAIRING key for the trusted-install path (ADR-0011 round-9): the capture envelope's
     * `captureId` when one was written this frame, else null. Transport is M3's.
     */
    val captureId: String?,
    /**
     * #1620: a notification's fields that held a value, for the daily sample key only ([cloud.trotter.log.gig.domain.census.CensusSampleKey]);
     * never spooled or sent. Null for a screen, whose layout the skeleton itself carries.
     */
    val presentFields: Set<NotifTextField>? = null,
    /**
     * #1626: the words a contributor review may show, collected only while [CensusSink.collectsReviewWords] is true.
     * Held in memory only: never spooled, written or sent with the record ([ReviewWords]).
     */
    val reviewWords: ReviewWords? = null,
) {
    override fun toString(): String = "CensusRecord(platform=$platform, itemBytes=$itemBytes, [redacted])"
}

/**
 * The census sink seam, mirroring [CaptureBus] (#1146). `isEnabled == false` is a STRUCTURAL
 * skip: the publisher builds nothing. [offer] returns true when the sink accepted the record.
 */
interface CensusSink {
    val isEnabled: Boolean
    /** Non-blocking snapshot of the last server policy; no policy means screens only. */
    val acceptedSchemaIds: Set<String> get() = setOf(SkeletonSchema.SCHEMA_ID)
    /** #1626: non-blocking snapshot; true while a contributor review could take this frame, so the builder keeps its words. */
    val collectsReviewWords: Boolean get() = false
    fun offer(record: CensusRecord): Boolean
}

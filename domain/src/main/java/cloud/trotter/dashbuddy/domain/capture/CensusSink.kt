package cloud.trotter.dashbuddy.domain.capture

import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.census.contract.SkeletonSchema

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
)

/**
 * The census sink seam, mirroring [CaptureBus] (#1146). `isEnabled == false` is a STRUCTURAL
 * skip: the publisher builds nothing. [offer] returns true when the sink accepted the record.
 */
interface CensusSink {
    val isEnabled: Boolean
    /** Non-blocking snapshot of the last server policy; no policy means screens only. */
    val acceptedSchemaIds: Set<String> get() = setOf(SkeletonSchema.SCHEMA_ID)
    fun offer(record: CensusRecord): Boolean
}

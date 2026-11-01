package cloud.trotter.log.gig.domain.state

import kotlinx.serialization.Serializable

/**
 * A continuous period of being online on one platform.
 * Replaces the DoorDash-specific "Dash" concept.
 *
 * Earnings: null = no parsed/accumulated figure yet; never coerce to 0.0 for display (#1041).
 * A new `0.0` represents a parsed $0.00 total. A legacy snapshot/journal blob's `0.0` was
 * written by `encodeDefaults` for a NEVER-parsed session: an unknown sentinel, not a parsed value.
 * It still decodes as `0.0`, rather than being migrated to null, and a restored `0.0` is re-serialized
 * by later snapshots of that same dash, so the HUD renders `$0.00` for that dash after a crash-restore
 * across this upgrade (master did the same on every cold start) — accepted. What changed: a FRESH
 * never-parsed session no longer manufactures a zero.
 */
@Serializable
data class Session(
    val sessionId: String,
    val startedAt: Long,
    val earningMode: SessionType? = null,
    val runningEarnings: Double? = null,
    val accumulatedDeliveryPay: Double? = null,
    /**
     * The key (job, receipt subject, total in cents) of the receipt last added to
     * [accumulatedDeliveryPay], held only while the flow stays on that receipt or on an offer
     * overlay reached straight from it (#1460). An identical receipt re-entered from that overlay is
     * not added twice; any other frame clears the key. Null outside an episode; an old blob decodes
     * it null.
     */
    val receiptEpisodeKey: String? = null,
)

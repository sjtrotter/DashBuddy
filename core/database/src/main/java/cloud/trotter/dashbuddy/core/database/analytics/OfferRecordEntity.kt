package cloud.trotter.dashbuddy.core.database.analytics

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable analytics read-model row — one per **closing** offer event (#314):
 * `OFFER_ACCEPTED` / `OFFER_DECLINED` / `OFFER_TIMEOUT`. The closing `OfferPayload`
 * alone carries `parsedOffer + evaluation + outcome + presentedAt + decidedAt`, so
 * `OFFER_RECEIVED` rows are skipped (an offer that never closed produces no record).
 *
 * The primary key is the source event's `sequenceId` (provenance + idempotency).
 * The `est*` fields are a **frozen decision snapshot** — legitimately frozen,
 * because this row records "what the verdict said at decision time", unlike a
 * realized-net cache. This is the Phase-4 Decisions tab's entire input (offer
 * funnel, value-of-saying-no, score-vs-outcome).
 */
@Entity(
    tableName = "offer_records",
    indices = [
        Index("decidedAt"),                       // period predicates
        Index(value = ["platform", "decidedAt"]), // per-platform periods
        Index("sessionId"),
        Index("offerHash"),
        Index("linkedJobId"),   // resolution's persistent claimed-offer set (#159 F4)
    ]
)
data class OfferRecordEntity(
    /** sequenceId of the source closing offer event — provenance AND idempotency. */
    @PrimaryKey val eventSequenceId: Long,
    val sessionId: String?,
    /** Platform.wire, resolved from session context. */
    val platform: String,
    val offerHash: String,
    /** "OFFER_ACCEPTED" | "OFFER_DECLINED" | "OFFER_TIMEOUT" (AppEventType.name). */
    val outcome: String,
    val presentedAt: Long,
    val decidedAt: Long,

    // From parsedOffer:
    val payAmount: Double?,
    val distanceMiles: Double?,
    val itemCount: Int,
    val merchantName: String?,

    // Frozen estimate snapshot from evaluation (a DECISION record, legitimately frozen):
    val score: Double?,
    val action: String?,
    val quality: String?,
    val estNetPay: Double?,
    val estDollarsPerHour: Double?,
    val estDollarsPerMile: Double?,
    val estTimeMinutes: Double?,
    val estOperatingCostPerMile: Double?,
    /**
     * Fuel component of [estOperatingCostPerMile] (per-mile) = `fuelCostEstimate ÷ distanceMiles`;
     * null when distance ≤ 0. Persisted so the delivery fold's fuel/non-fuel split basis rehydrates
     * on a mid-session restart from the same offer row as the cpm — rebuild-stable (#659).
     */
    val estFuelPerMile: Double? = null,
    /** Non-fuel component of [estOperatingCostPerMile] (per-mile) = `nonFuelCostEstimate ÷ distanceMiles` (#659). */
    val estNonFuelPerMile: Double? = null,

    // ── Store entity resolution (#159, v12) ─────────────────────────────────
    /**
     * The PRIMARY store of the offer (first order's hint) for single-store offers; null for multi-store
     * offers in v1 (a per-order offer↔store bridge is phase 2). Stamped by resolution when the offer is
     * linked to a job AND its `merchantName` agrees by brand token with a resolved pickup anchor (F4).
     */
    val storeKey: String? = null,
    /**
     * The persistent claimed-offer set (#159 F4). Stamped at resolution when this offer is linked to a
     * job (exact via `jobOfferHashes`, or the brand-token-guarded temporal fallback). Survives batch
     * boundaries and is refold-deterministic, so the temporal fallback's "not already claimed" test is a
     * durable `linkedJobId IS NULL OR = thisJob` predicate, not an ephemeral in-memory set. Never used
     * for money.
     */
    val linkedJobId: String? = null,
)

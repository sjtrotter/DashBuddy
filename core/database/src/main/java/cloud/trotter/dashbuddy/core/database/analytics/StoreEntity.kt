package cloud.trotter.dashbuddy.core.database.analytics

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable store-entity row (#159) — identity / resolution ONLY (D1). One row per resolved store
 * entity; a **chain-only provisional** row (`storeKey = "doordash|heb|"`) exists while no running key
 * has been observed and coexists with keyed rows once one is learned. Reads that want chain-level
 * rollups group by [normalizedChain]; reads that want per-location detail group by [storeKey].
 *
 * No stats columns (D1): pickup/delivery counts, dwell, p50/p95 are all **derived at read time** by
 * `GROUP BY` over `pickup_records` / `delivery_records` (SSOT — and percentiles can't be folded
 * incrementally anyway). The one denormalization kept is [firstSeenAt]/[lastSeenAt] (cheap,
 * deterministic, saves a MIN/MAX join on every list read).
 *
 * Store names/addresses are **MERCHANT** data, not customer PII — fine at rest, never in INFO+ logs
 * (P7). No customer hashes live here.
 */
@Entity(
    tableName = "stores",
    indices = [Index("normalizedChain")],
)
data class StoreEntity(
    /**
     * `platform + "|" + normalizedChain + "|" + runningKey` (D2, `StoreKeys.storeKey`). The
     * `runningKey` segment is empty while unknown (chain-only provisional); the `platform` segment
     * prevents a cross-platform chain collision (F5). Deterministic — a refold reproduces it byte-for-
     * byte.
     */
    @PrimaryKey val storeKey: String,
    /** `Platform.wire` from session context (P8: never id-parsed, never a literal); also key segment 1. */
    val platform: String,
    /** Lowercased, whitespace-collapsed, qualifier-stripped chain bucket — one `:domain` normalizer (F7). */
    val normalizedChain: String,
    /** First-observed capitalization of the chain form (the issue's `chain_name`). */
    val chainDisplay: String,
    /** The platform's location discriminator (`02426`, `alamo ranch`, `0164-0045`); null while unknown. */
    val runningKey: String?,
    /** First-observed offer-surface form (`Target`). */
    val offerNameForm: String?,
    /** First-observed pickup/canonical form. */
    val pickupNameForm: String?,
    /** First-observed payout form (`Target (02426)`) — the key carrier, kept for audit/re-resolution. */
    val payoutNameForm: String?,
    /** First-observed non-null store address (enriched `PickupPayload`; null for all historical rows). */
    val address: String?,
    /** Maintained by the fold from `obs`-derived event timestamps (never wall clock). */
    val firstSeenAt: Long,
    val lastSeenAt: Long,
)

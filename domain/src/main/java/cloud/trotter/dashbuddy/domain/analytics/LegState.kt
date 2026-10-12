package cloud.trotter.dashbuddy.domain.analytics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One not-yet-consumed **to-store** driving leg (#688 phase B) — the miles from the previous leg
 * anchor to a `PICKUP_ARRIVED`, held until the job's `DELIVERY_COMPLETED` consumes it as that drop's
 * `milesToStore`. Keyed by [pickupTaskId] (its own arrival) so a grace-flap re-arrival ACCUMULATES
 * into the same entry rather than appending a phantom second one. [storeName] enables the exact
 * store-form match at consumption; null when the pickup screen carried no store (FIFO still attributes
 * within the job). MERCHANT data only — never customer PII, never an INFO+ log surface (P7).
 */
@Serializable
data class PendingStoreLeg(
    val pickupTaskId: String,
    val jobId: String,
    val storeName: String?,
    val miles: Double,
    /**
     * Latest arrival odometer for this leg. Preserves #688's conservative legacy-completion
     * retirement: closed store legs at/before that completion are retired session-wide. Unknown
     * closure order also retires. #1108 budgets independently cap the eventual mileage allocation.
     */
    val closedAtOdometer: Double? = null,
)

/**
 * Positive-delta mileage B and already allocated mileage C, re-anchored on every reading (#1108).
 * Initial inbound anchor = previous drop or dash start, independent of the current leg anchor.
 * [anchored] records whether that inbound anchor existed; otherwise the first reading seeds B = 0.
 */
@Serializable
data class JobMileageBudget(
    val lastOdometer: Double,
    val measured: Double = 0.0,
    val allocated: Double = 0.0,
    val anchored: Boolean = true,
)

/**
 * Bounded session allocation state, persisted in `session_records.legStateJson`.
 * Pending legs drop oldest at [MAX_PENDING]; budgets evict the oldest delivered job at
 * [MAX_JOB_BUDGETS]. Live-only saturation refuses new jobs. Session allocation and completion time
 * survive page boundaries.
 */
@Serializable
data class LegState(
    val prevLegOdometer: Double? = null,
    val pendingStoreLegs: List<PendingStoreLeg> = emptyList(),
    val pendingDropoffLegs: Map<String, Double> = emptyMap(),
    val jobMileageBudgets: Map<String, JobMileageBudget> = emptyMap(),
    val completionHighWater: Long? = null,
    /** Positive deltas across session odometer events; resets re-anchor without subtracting miles. */
    val sessionMeasured: Double = 0.0,
    /** Sum of machine completion allocations, bounded by [sessionMeasured]. */
    val sessionAllocated: Double = 0.0,
) {
    companion object {
        /** Bounded-ingestion cap on each pending collection (drop-oldest on overflow). */
        const val MAX_PENDING = 32
        const val MAX_JOB_BUDGETS = 128
    }
}

/**
 * Serialization SSOT for [LegState] ↔ `session_records.legStateJson` (#688 phase B). Kept in `:domain`
 * with the type (no Android/DB dep — kotlinx-serialization only) so the projector's `toEntity`/
 * `toContext` mapping routes through ONE definition.
 *
 * **Fail-closed decode:** a null/blank/malformed blob degrades to an EMPTY [LegState] — the session's
 * allocation state is unavailable until another defensible anchor is observed. This is the
 * spec-level guard that makes a garbage `legStateJson` column safe.
 */
object LegStateCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /** Encode for persistence. An empty leg state serializes to a compact object (never null). */
    fun encode(state: LegState): String = json.encodeToString(LegState.serializer(), state)

    /** Decode, fail-closed to an empty [LegState] on null/blank/garbage input. */
    fun decode(blob: String?): LegState {
        if (blob.isNullOrBlank()) return LegState()
        return try {
            json.decodeFromString(LegState.serializer(), blob)
        } catch (_: Exception) {
            LegState()
        }
    }
}

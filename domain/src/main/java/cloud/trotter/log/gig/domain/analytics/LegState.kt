package cloud.trotter.log.gig.domain.analytics

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

/** The timestamp stamped on the arrival step and the reading taken by that step's event. */
@Serializable
data class ArrivalReading(val arrivedAt: Long, val odometer: Double?)

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
    /**
     * #1310 — the reading taken by the ONE `DELIVERY_ARRIVED` event whose
     * `occurredAt == payload.arrivedAt`, i.e. the event emitted on the step that stamped
     * `Task.arrivedAt`. A re-arrival or resume re-sight carries the kept `arrivedAt` and a later
     * `occurredAt`, so it never qualifies. A null `odometer` means that event had no reading;
     * it stays unknown. Completion stamps the reading only when
     * `reading.arrivedAt == completion.arrivedAt` (a second lock against eviction + reinsertion),
     * then consumes it; a dropoff-phase unassign purges it.
     * Bounded at [MAX_PENDING], drop-oldest, independent of [pendingDropoffLegs]; a missing fact
     * folds null. An anchorless arrival can sit only here, so the maps can evict different tasks.
     * Recovery replay of an UNFIRED arrival event stamps metadata at replay time: the current
     * odometer under the original `occurredAt`. This pre-existing property applies to every
     * edge-stamped odometer, including `odometerAtConfirmation`.
     */
    val dropoffArrivalOdometers: Map<String, ArrivalReading> = emptyMap(),
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
 * spec-level guard that makes a garbage `legStateJson` column safe. A blob written before a field
 * existed decodes with that field's default (the arrival map empty: no stamped reading is known, so
 * the row folds null `odometerAtArrival` — fail-null, the other facts kept).
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

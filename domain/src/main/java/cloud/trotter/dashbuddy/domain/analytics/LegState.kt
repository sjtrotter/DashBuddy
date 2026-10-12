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

/** Immutable job origin, measured high-water mileage B, and already allocated mileage C (#1108). */
@Serializable
data class JobMileageBudget(val originOdometer: Double, val measured: Double = 0.0, val allocated: Double = 0.0)

/**
 * Bounded session allocation state, persisted in `session_records.legStateJson`.
 * Pending legs drop oldest at [MAX_PENDING]; job budgets NEVER evict or restart. Once the map is
 * full, new jobs remain UNKNOWN for the rest of the session. Completion time survives page boundaries.
 */
@Serializable
data class LegState(
    val prevLegOdometer: Double? = null,
    val pendingStoreLegs: List<PendingStoreLeg> = emptyList(),
    val pendingDropoffLegs: Map<String, Double> = emptyMap(),
    val jobMileageBudgets: Map<String, JobMileageBudget> = emptyMap(),
    val completionHighWater: Long? = null,
) {
    companion object {
        /** Bounded-ingestion cap on each pending collection (drop-oldest on overflow). */
        const val MAX_PENDING = 32
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

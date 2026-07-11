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
     * The odometer reading at which this to-store leg CLOSED (the `PICKUP_ARRIVED` odo; the LATEST on a
     * re-arrival accumulation). The ordering marker a legacy-basis completion uses to retire the
     * pending store legs — SESSION-WIDE, any job — whose closure is at/before it (#688 review Fix 1 +
     * re-verify widening): the legacy span is a session-level partition delta (prevDrop→completion,
     * regardless of job), so any leg closed inside it, including a cross-job add-on's, is already
     * inside that span and a later drop must not re-claim it per-leg — that was the mixed-basis
     * double-count. Nullable-with-default: an old persisted `legStateJson` blob (a mid-upgrade
     * in-flight session) decodes this as null, and a null / unknown closure order is treated
     * conservatively as "preceded" (retire → per-row under-attribution, never a double-count). Never
     * an INFO+ log surface (P7).
     */
    val closedAtOdometer: Double? = null,
)

/**
 * The per-session per-leg mileage accumulator (#688 phase B) — folded from the lifecycle
 * `metadata.odometer` stamps and consumed at `DELIVERY_COMPLETED` into `milesToStore`/`milesToDropoff`.
 *
 * [prevLegOdometer] is the current leg anchor (the odometer reading a new leg is measured from),
 * advanced by the enumerated anchor-event set (DASH_START / PICKUP_ARRIVED / PICKUP_CONFIRMED /
 * DELIVERY_ARRIVED / DELIVERY_CONFIRMED / DELIVERY_COMPLETED). [pendingStoreLegs] queues closed
 * to-store legs awaiting their drop; [pendingDropoffLegs] maps a drop's own `taskId` to its
 * to-dropoff leg miles (unambiguous — the drop consumes its own key).
 *
 * **Persisted with the session row** (`session_records.legStateJson`, [LegStateCodec]) because these
 * pending legs describe drops that have NOT yet completed and so cannot be re-derived from record
 * rows the way `prevDropOdometer`/`deliveredJobIds` are — a batch-boundary rehydration must restore
 * them or incremental folding would diverge from a from-zero refold (the #703 determinism class).
 *
 * **Bounded ingestion (Principle 6):** both collections are capped at [MAX_PENDING] with deterministic
 * drop-oldest, so a pathological event stream (many arrivals, no completions) cannot grow the
 * persisted context unboundedly. In the fielded shape both drain on every completion, so the cap is
 * never approached.
 */
@Serializable
data class LegState(
    val prevLegOdometer: Double? = null,
    val pendingStoreLegs: List<PendingStoreLeg> = emptyList(),
    val pendingDropoffLegs: Map<String, Double> = emptyMap(),
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
 * rows fall back to the legacy partition delta, never a crash and never wrong money. This is the
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

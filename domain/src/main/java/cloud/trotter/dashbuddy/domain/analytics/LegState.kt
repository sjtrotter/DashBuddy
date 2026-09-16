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
 * to-dropoff leg miles (unambiguous — the drop consumes its own key), scoped by
 * [dropoffLegJobIds]; [spanApportionedMiles] holds a span-basis job's reserved shares (#1108).
 *
 * **Persisted with the session row** (`session_records.legStateJson`, [LegStateCodec]) because these
 * pending legs describe drops that have NOT yet completed and so cannot be re-derived from record
 * rows the way `prevDropOdometer`/`deliveredJobIds` are — a batch-boundary rehydration must restore
 * them or incremental folding would diverge from a from-zero refold (the #703 determinism class).
 *
 * **Bounded ingestion (Principle 6):** every collection here is capped at [MAX_PENDING] with
 * deterministic drop-oldest, so a pathological event stream (many arrivals, no completions) cannot
 * grow the persisted context unboundedly ([spanApportionedMiles] and [dropoffLegJobIds] are bounded
 * by [pendingDropoffLegs], whose entries they mirror). In the fielded shape both drain on every completion, so the cap is
 * never approached.
 */
@Serializable
data class LegState(
    val prevLegOdometer: Double? = null,
    val pendingStoreLegs: List<PendingStoreLeg> = emptyList(),
    val pendingDropoffLegs: Map<String, Double> = emptyMap(),
    /**
     * `taskId → jobId` for the entries in [pendingDropoffLegs] (#1108) — the job scope
     * [pendingDropoffLegs] itself cannot carry, because its value is the bare leg miles and widening
     * it to a record would make an in-flight session's persisted blob undecodable (the codec fails
     * closed to an EMPTY state, losing every pending leg). A parallel default-empty map decodes an
     * old blob unchanged instead.
     *
     * It scopes the RESERVATION, not the retire: a span-basis completion retires every pending
     * dropoff leg (session-wide, exactly as #688 review Fix 1 retires store legs — the span is a
     * session-level delta and a cross-job leg closed inside it is equally double-countable) but
     * reserves a share only for the drops of its OWN job, since one job's driving must not be
     * attributed to another job's row. A taskId MISSING here is "unknown job", not "another job",
     * and is treated as this job's — the conservative direction, matching
     * [PendingStoreLeg.closedAtOdometer]'s null rule. Written and dropped in lockstep with
     * [pendingDropoffLegs].
     */
    val dropoffLegJobIds: Map<String, String> = emptyMap(),
    /**
     * `taskId → miles`: the share of a SPAN-basis job's mileage reserved for a drop that had not
     * completed yet when a sibling drop of the same job folded on the span basis (#1108).
     *
     * A job's drops must not sit on two mileage bases. Fielded 2026-09-13 (job …500, two drops from
     * one accept): DoorDash went straight from drop 1's confirm to drop 2's confirm, so drop 2 never
     * stamped a `DELIVERY_ARRIVED` and folded on the legacy SPAN basis — taking the whole
     * `prevDropOdometer → completion` delta, 8.82 mi, which physically CONTAINS drop 1's 6.00 mi
     * to-dropoff leg — and drop 1, folding second (the two completions share an instant and the
     * close-out sweep emitted them in reverse completion order), then claimed that leg again on the
     * leg-sum basis. Σ 14.82 mi for a job whose odometer span was 8.82.
     *
     * So a span-basis fold RETIRES the pending dropoff legs its span swallowed (session-wide) and
     * reserves each of its OWN job's drops among them an equal share of the span here; the sibling's
     * own fold reads its reservation instead of a leg sum or a (by then ~zero) span delta. Σ per job ≤ the job's span by
     * construction, with the shortfall going to deadhead when a reserved sibling never completes —
     * the sanctioned one-sided error (#688 review Fix 1). Cleared per task at the drop's completion
     * and at `TASK_UNASSIGNED`; default-empty so an old persisted blob decodes unchanged.
     */
    val spanApportionedMiles: Map<String, Double> = emptyMap(),
) {
    /**
     * Is [taskId]'s pending dropoff leg part of [jobId] (#1108)? An UNKNOWN job (a pre-#1108
     * persisted blob, whose [dropoffLegJobIds] is empty) answers YES — see that field's KDoc for why
     * the conservative direction is retire-and-apportion.
     */
    fun dropoffLegInJob(taskId: String, jobId: String): Boolean =
        dropoffLegJobIds[taskId]?.let { it == jobId } ?: true

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

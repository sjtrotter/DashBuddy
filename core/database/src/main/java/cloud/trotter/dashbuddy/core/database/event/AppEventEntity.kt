package cloud.trotter.dashbuddy.core.database.event

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import cloud.trotter.dashbuddy.domain.model.event.AppEventType

/**
 * The durable, append-only event log entity (#354) — the analytics/state-recovery source of
 * truth. `app_events` rows are never mutated or deleted; every read-model table is a
 * rebuildable projection of this log.
 *
 * ## `sequenceId` vs `occurredAt` — the ordering invariant (#732)
 *
 * **[sequenceId] is the authoritative fold/order key. [occurredAt] is NOT guaranteed to be
 * monotonic with it.**
 *
 * The lag has ONE concrete carrier, not a blanket "every graced commit lags" rule:
 * **`PICKUP_CONFIRMED`** mints its [occurredAt] from `Task.completedAt`
 * (`TaskEffects.kt` — `confirmedAt = task.completedAt ?: obs.timestamp`), and `completedAt` is
 * itself stamped at grace-**ARM** time (`pend.since` — the timestamp of the observation that
 * first signaled the destructive transition) by either `endSession` or the `TASK_RETIRE`
 * displacement path (`retireSince ?: obs.timestamp`) in `PlatformRegionStepper.kt`. The
 * `PICKUP_CONFIRMED` row itself is not appended — and so does not receive its [sequenceId] —
 * until the close-out sweep runs, which can happen LONG after the grace committed (the
 * inversion is NOT bounded by the grace window). Two field receipts: 2026-07-07 sequenceId
 * 70/71 and 2026-07-08 sequenceId 116/117 — an 11-minute inversion traced to that sweep lag.
 *
 * **Other graced-commit event types do NOT carry this lag on [occurredAt].** `DASH_STOP`
 * (`ModeEffects.kt`) and `DELIVERY_COMPLETED` (`DeliveryCompletionEffects.kt`) stamp
 * [occurredAt] at the COMMITTING observation's own timestamp (`obs.timestamp`) — the honest
 * domain time (grace-arm time when applicable) rides in the event's PAYLOAD instead
 * (`SessionStopPayload.endedAt`; the delivery payload's `completedAt`), and the analytics fold
 * reads that payload field, never the row's [occurredAt] (`RecordFolds.foldDashStop` reads
 * `p.endedAt`, never `e.occurredAt`, for the session end time). Consumers wanting "when did
 * the dash really end / the drop really complete" must read the PAYLOAD's domain timestamp,
 * not [occurredAt].
 *
 * `PlatformRegion.pendingModeResume` commits (#605) log nothing off a lagging stamp at all —
 * that grace never mints an event off its own armed time, so it's outside this invariant.
 *
 * This is a **documented, accepted invariant** (#732 Option B — the dev decided NOT to
 * re-stamp `PICKUP_CONFIRMED`'s `occurredAt` to append time, because `Task.completedAt` is the
 * honest domain moment the destructive signal appeared). Consequences for any code reading
 * this table:
 *   - **Fold/replay/windowing order** — the analytics projector's drain, crash recovery, "most
 *     recent event", or anything reconstructing history — MUST order by [sequenceId], never by
 *     [occurredAt], across graced-commit boundaries. (#732's PR description carries the full
 *     audit of existing consumers.)
 *   - **Display/grouping by real-world time** — a delivery's completion time, a session's
 *     start/end, a chronological CSV export — SHOULD read the PAYLOAD's own domain timestamp
 *     where the event type carries one (that's what `RecordFolds` actually reads, e.g.
 *     `SessionStopPayload.endedAt`), NOT [occurredAt] — except for event types with no separate
 *     payload timestamp (e.g. `PICKUP_CONFIRMED`, where [occurredAt] itself IS
 *     `Task.completedAt`, so there is nothing else to read).
 *   - [sequenceId] order is real commit/causal order (everything already durable when the
 *     grace fired sorts before it); [occurredAt] order is not, for `PICKUP_CONFIRMED`
 *     specifically.
 */
@Entity(
    tableName = "app_events",
    indices = [
        Index(value = ["aggregateId"]), // Fast lookup by Dash ID
        Index(value = ["eventType"]),   // Fast lookup by Event Type
        Index(value = ["occurredAt"])   // Fast sorting by Time
    ]
)
data class AppEventEntity(
    /**
     * Auto-incrementing primary key. Defines the absolute append/commit order of events — the
     * **authoritative fold/order key**. See the class KDoc's "sequenceId vs occurredAt"
     * section (#732): `PICKUP_CONFIRMED` — whose [occurredAt] carries the grace-armed
     * `Task.completedAt`, not append time — can append at a `sequenceId` higher than an
     * intervening event's while carrying an EARLIER [occurredAt]. Other event types'
     * [occurredAt] tracks commit-observation time and does not invert against [sequenceId].
     */
    @PrimaryKey(autoGenerate = true)
    val sequenceId: Long = 0,

    /**
     * A String representing the specific Dash session (UUID or Stringified Long).
     * Using String allows flexibility if you switch from Long IDs to UUIDs later.
     */
    val aggregateId: String?,

    /** A discriminator string (e.g., "OFFER_RECEIVED", "ORDER_PICKED_UP"). */
    val eventType: AppEventType,

    /** The JSON serialization of the specific event data class. */
    val eventPayload: String,

    /**
     * The Unix timestamp of when the event happened — the DOMAIN time for most event types,
     * but NOT the append order. **Not guaranteed monotonic with [sequenceId]**:
     * `PICKUP_CONFIRMED` stamps this from `Task.completedAt`, which can itself be a grace-ARM
     * timestamp (`pend.since`, #431) rather than append/commit time — the row can be appended
     * much later, at the close-out sweep. Other graced-commit event types (`DASH_STOP`,
     * `DELIVERY_COMPLETED`) stamp this at commit-OBSERVATION time instead and carry the
     * honest domain time in their PAYLOAD (`SessionStopPayload.endedAt`; the delivery
     * payload's `completedAt`) — read the payload for those, not this column. See the class
     * KDoc's "sequenceId vs occurredAt" section (#732) for the full invariant and which
     * consumers must order/read by which column.
     */
    val occurredAt: Long = System.currentTimeMillis(),

    /** Optional JSON for device state (battery, GPS, etc.) at the time of event. */
    val metadata: String? = null
)
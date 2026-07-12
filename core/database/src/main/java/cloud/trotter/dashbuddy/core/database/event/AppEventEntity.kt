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
 * A graced destructive commit (`PlatformRegion.pendingDestructive` / the `GRACE_COMMIT` timer,
 * #431 — a dash-end or task-retire that debounces behind a short grace window before it's
 * believed) stamps its eventual event's [occurredAt] at grace-ARM time (`pend.since` — the
 * timestamp of the observation that first signaled the destructive transition; see
 * `PlatformRegionStepper.stepCore`'s lazy-expiry commit, ~L338-342). But the row is not
 * INSERTED — and so does not receive its [sequenceId] — until the grace actually COMMITS,
 * which happens later, after zero or more intervening, non-graced events have already been
 * appended with their own (later, but lower-`sequenceId`) `occurredAt`. Net effect: a
 * higher-`sequenceId` row can carry an EARLIER `occurredAt` than a lower-`sequenceId` row
 * already ahead of it in the log. Two field receipts: 2026-07-07 sequenceId 70/71 and
 * 2026-07-08 sequenceId 116/117 (an 11-minute inversion).
 *
 * This is a **documented, accepted invariant** (#732 Option B — the dev decided NOT to
 * re-stamp): `occurredAt` stays honest about WHEN the destructive signal really appeared
 * (`pend.since`) rather than lying with the append-time wall clock. Consequences for any code
 * reading this table:
 *   - **Fold/replay/windowing order** — the analytics projector's drain, crash recovery, "most
 *     recent event", or anything reconstructing history — MUST order by [sequenceId], never by
 *     [occurredAt]. (#732's PR description carries the full audit of existing consumers.)
 *   - **Display/grouping by real-world time** — a delivery's completion time, a session's
 *     start/end, a chronological CSV export — SHOULD use [occurredAt] (or a payload's own
 *     domain timestamp, which is what most `RecordFolds` sites actually read): that IS the
 *     honest real-world moment, append-order artifacts aside.
 *   - [sequenceId] order is real commit/causal order (everything already durable when the
 *     grace fired sorts before it); [occurredAt] order is not.
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
     * section (#732): a graced destructive commit can append at a `sequenceId` higher than an
     * intervening event's while carrying an EARLIER [occurredAt].
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
     * The Unix timestamp of when the event happened — the DOMAIN time, not necessarily the
     * append order. **Not guaranteed monotonic with [sequenceId]**: a graced destructive
     * commit (#431) stamps this at grace-ARM time (`pend.since`), not append/commit time. See
     * the class KDoc's "sequenceId vs occurredAt" section (#732) for the full invariant and
     * which consumers must order by which column.
     */
    val occurredAt: Long = System.currentTimeMillis(),

    /** Optional JSON for device state (battery, GPS, etc.) at the time of event. */
    val metadata: String? = null
)
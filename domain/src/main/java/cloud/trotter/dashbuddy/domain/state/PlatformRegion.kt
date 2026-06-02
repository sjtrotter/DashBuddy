package cloud.trotter.dashbuddy.domain.state

import cloud.trotter.dashbuddy.domain.model.ratings.RatingsSnapshot

/**
 * Region 2+ — per-platform durable state.
 *
 * Mode lives HERE, not globally. Each platform has its own session lifecycle,
 * active job/task, and transition tracking.
 *
 * A platform region only steps when an observation's [Platform] matches.
 */
data class PlatformRegion(
    val platform: Platform,
    val mode: Mode = Mode.Offline,
    val session: Session? = null,
    val activeJob: Job? = null,
    val activeTask: Task? = null,
    val recentTasks: List<Task> = emptyList(),
    val sessionGraceDeadline: Long? = null,
    /**
     * Deadline after which a transient idle/offer seen mid-task retires the
     * active task. Armed when leaving a task flow to a non-task flow while
     * online; cancelled on returning to a task flow; lazily expired in
     * [PlatformRegionStepper.step]. Keeps a brief informational screen (the
     * timeline, or the idle map flashing before the delivery summary) from
     * forgetting the task.
     */
    val taskClearGraceDeadline: Long? = null,
    val lastTransitionKind: TransitionKind? = null,
    val zoneName: String? = null,
    val sessionType: SessionType? = null,
    val ratings: RatingsSnapshot? = null,
    val surgeMultiplier: Double? = null,
    val lastPostTaskPayHash: Int? = null,
    /**
     * Most recent PostTask observation's parsed fields. Captured during
     * PostTask so the closing `DELIVERY_COMPLETED` event (emitted on
     * leaving PostTask) can include the full pay breakdown.
     */
    val lastPostTaskFields: ParsedFields.PostTaskFields? = null,
    val lastObservedAt: Long = 0,
    /** Timestamp when Flow entered Idle while mode is Online. Null otherwise. */
    val idleEnteredAt: Long? = null,
    /**
     * Per-task idempotency for the post-task announcement bubble. Set to the
     * dropoff `taskId` the moment EffectMap.diffPostTask emits the "Saved: $X"
     * bubble for that delivery. Subsequent PostTask observations for the same
     * taskId no-op — including collapse/re-expand cycles that previously
     * re-tripped the hash-based gate. Naturally resets when the next delivery
     * starts (its taskId differs from this stored value).
     */
    val lastAnnouncedPostTaskTaskId: String? = null,
)

/**
 * Classification of a mode transition for logging and lifecycle decisions.
 * Stored on [PlatformRegion.lastTransitionKind] so downstream (EffectMap)
 * can distinguish recovery starts from normal starts.
 *
 * This type lives in :domain so PlatformRegion can reference it without
 * depending on :core:state. The policy logic that produces these values
 * lives in TransitionPolicy (:core:state).
 */
enum class TransitionKind {
    /** Observation carries no mode signal. */
    NoSignal,
    /** Observation confirms the current mode — no change. */
    Confirmed,
    /** Mode change to a flow that was in the declared outcomes (or no outcomes). */
    Expected,
    /** Mode change to a flow NOT in the declared outcomes. */
    Unexpected,
}

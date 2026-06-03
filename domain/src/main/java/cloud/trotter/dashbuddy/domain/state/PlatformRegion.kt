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
    /**
     * A pending "destructive" transition — ending the dash or retiring the
     * active task — that is **provisional** until confirmed. Armed when leaving
     * a more-active state (online→offline, or task-flow→non-task) so a transient
     * screen flash (backing out of the app mid-pickup, or the idle map flashing
     * before the dash summary) doesn't immediately drop the task/session.
     * Resolved in [PlatformRegionStepper]: confirmed by an authoritative signal
     * (session:ended, PostTask, a fresh dash) or deadline expiry → commit;
     * cancelled when the prior more-active state returns within the window.
     * Replaces the former separate sessionGraceDeadline / taskClearGraceDeadline.
     */
    val pendingDestructive: PendingDestructive? = null,
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
 * A provisional transition toward a less-active state, pending confirmation.
 * See [PlatformRegion.pendingDestructive]. Plain data (Gson-serializable) so it
 * survives crash-recovery replay; resolution is driven by `obs.timestamp`, never
 * a wall clock, keeping the reducer pure.
 */
data class PendingDestructive(
    val kind: DestructiveKind,
    /** The obs.timestamp that armed it. */
    val since: Long,
    /** Once an observation's timestamp passes this, the transition is committed. */
    val deadline: Long,
    val reason: String? = null,
)

enum class DestructiveKind {
    /** End the dash/session — online→offline without an authoritative end signal. */
    SESSION_END,

    /** Retire the active task — a task flow gave way to idle/offer mid-delivery. */
    TASK_RETIRE,
}

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

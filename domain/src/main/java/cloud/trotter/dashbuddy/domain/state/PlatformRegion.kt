package cloud.trotter.dashbuddy.domain.state

import kotlinx.serialization.Serializable

import cloud.trotter.dashbuddy.domain.model.ratings.RatingsSnapshot

/**
 * Region 2+ — per-platform durable state.
 *
 * Mode lives HERE, not globally. Each platform has its own session lifecycle,
 * active job/task, and transition tracking.
 *
 * A platform region only steps when an observation's [Platform] matches.
 */
@Serializable
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
    /**
     * Monotonic counter for deterministic entity-id minting (#344). Bumped by the
     * stepper on every session/job/task mint and persisted with snapshots, so
     * crash-recovery replay reproduces the live run's IDs — and two mints sharing
     * an observation timestamp still get distinct IDs.
     */
    val mintCounter: Long = 0,
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
    /**
     * A graced screen-implied resume out of [Mode.Paused] (#605), provisional
     * until committed. See [PendingModeResume]. Default-null so existing
     * snapshots deserialize unchanged. Distinct from [pendingDestructive]
     * because during the field flap that pending is BUSY holding the
     * just-completed delivery's `TASK_RETIRE` grace — the two slots cannot share.
     */
    val pendingModeResume: PendingModeResume? = null,
    /**
     * The most-recently accepted offer, stashed at accept time (#526 D1), pending consumption
     * by the job-mint that follows. Default-null so existing snapshots deserialize unchanged.
     *
     * WHY: the job is normally minted on the `OfferPresented → task-flow` edge, reading the offer
     * straight off `FlowRegion.pendingOffer`. But a `waiting_for_offer` teardown frame can land
     * between the accept click and the first task frame (the F3 race, verified in the 07-05
     * two-pickup capture), popping `pendingOffer` first — so that edge never fires and the job is
     * minted by the bare fallback with no economics, no dropoff/pickup placeholders, no store hint.
     * The stash survives the teardown: it's mirrored (idempotently, keyed by offerHash) on every
     * step whose flow still holds an accept-latched `pendingOffer`, and consumed by whichever
     * mint path runs — accept-adjacent, add-on, or fallback. Cleared on consumption, on a
     * superseding new offer, on session end, and lazily when older than the accept grace.
     *
     * Plain data (kotlinx-serializable); all timestamps are `obs.timestamp`, so it is replay-stable.
     */
    val lastAcceptedOffer: AcceptStash? = null,
    /**
     * The last **non-null** [Flow] this region actually stepped on (#438 item 5 / D3). The global
     * R0 [FlowRegion] is shared, so under concurrency `FlowRegion.flow` is whatever platform last
     * touched the screen; keying this region's lifecycle edges (PostTask entry/exit, task
     * retire/completion) off the *global* flow lets a foreign platform's frame fire THIS platform's
     * edges (a premature completion, a duplicate receipt bubble). This records the flow this
     * platform's own observations drove, so the edge diffs are per-region. (The accept edge's
     * cross-platform guard is separate — `offerBelongsToRegion`, since the offer still lives in the
     * shared R0 until B3.)
     *
     * Stamped by the [step] wrapper from `flowObs.flow` — NOT `nextFlow.flow`: a flow-less obs
     * (flow=null clicks/notifications) leaves it unchanged, because `nextFlow.flow` on such a frame
     * is the other platform's flow (the exact contamination this removes). Default-null so existing
     * snapshots deserialize unchanged; a null value falls back to the global flow at the read sites,
     * making single-platform behavior identical.
     */
    val lastActedFlow: Flow? = null,
)

/**
 * An accepted offer captured at accept time (#526 D1), so a job can be minted with full economics
 * and pre-created placeholders even when the `OfferPresented → task-flow` edge is skipped by a
 * teardown frame (the F3 race). See [PlatformRegion.lastAcceptedOffer].
 *
 * [storeHints] is the raw per-order store list (`orders.map { storeName }`) — empty when the offer
 * wasn't parsed; the dropoff count is `storeHints.size` (fallback 1), the distinct pickup stores are
 * its case-insensitive dedup (#499). [acceptedAt] is the `obs.timestamp` of the accept-latch step
 * that first armed the stash (preserved across idempotent re-mirrors), and becomes the minted
 * job's [AcceptedOfferEconomics.acceptedAt].
 */
@Serializable
data class AcceptStash(
    val offerHash: String?,
    val payAmount: Double? = null,
    val netPay: Double? = null,
    val estMinutes: Double? = null,
    val distanceMiles: Double? = null,
    val storeHints: List<String> = emptyList(),
    val acceptedAt: Long,
)

/**
 * A provisional screen-implied resume out of [Mode.Paused], pending confirmation
 * (#605). DoorDash's pause sheet is a `BottomSheetModal` on top of the just-
 * completed delivery summary, so accessibility frames alternate paused ↔ online;
 * flipping mode on the first online frame re-mints `DASH_PAUSED` and a spurious
 * "resumed" card on every edge. Instead, an online-implying **Screen** while
 * Paused arms this pending and stays Paused: a Paused-implying frame inside the
 * window CANCELS it (the modal is still up — the 06-28 case, receipt visible
 * ~4.3s < grace), sustained online past [deadline] COMMITS the resume once (lazy
 * expiry + a `MODE_RESUME_COMMIT` wake timer), and an `OfferPresented` screen
 * commits immediately (an offer is authoritative online evidence, structurally
 * absent from the flap).
 *
 * Plain data (kotlinx-serializable) so it survives crash-recovery replay;
 * resolution is driven by `obs.timestamp`, never a wall clock, keeping the
 * reducer pure.
 */
@Serializable
data class PendingModeResume(
    /** The obs.timestamp of the first online frame that armed it. */
    val since: Long,
    /** Once an observation's timestamp passes this, the resume is committed. */
    val deadline: Long,
)

/**
 * A provisional transition toward a less-active state, pending confirmation.
 * See [PlatformRegion.pendingDestructive]. Plain data (Gson-serializable) so it
 * survives crash-recovery replay; resolution is driven by `obs.timestamp`, never
 * a wall clock, keeping the reducer pure.
 */
@Serializable
data class PendingDestructive(
    val kind: DestructiveKind,
    /** The obs.timestamp that armed it. */
    val since: Long,
    /** Once an observation's timestamp passes this, the transition is committed. */
    val deadline: Long,
    val reason: String? = null,
    /**
     * Armed by an authoritative-looking signal (the dash-summary screen)
     * rather than inferred from an offline flash (#431). Authoritative
     * pendings use the short grace and are NOT cancelled by a mere
     * online-resume — a post-summary online flash must not resurrect a
     * really-ended session. Only a task-flow observation (unambiguously
     * still dashing) cancels them.
     */
    val authoritative: Boolean = false,
    /**
     * The [Flow] this pending was armed *toward* — the destination screen whose
     * appearance armed the transition (#596). Recorded only for `TASK_RETIRE`:
     * the idle/offer arm stamps the flow it left the task for (`Idle`,
     * `OfferPresented`), the receipt arm stamps `PostTask`. A physically-complete
     * job is allowed to close on a retire commit (#596 T1/T2) **only when this is
     * not [Flow.OfferPresented]** — a retire armed by the dasher deliberating on a
     * mid-route add-on offer must NOT false-complete the still-undelivered final
     * drop; that accept is an add-on, not an independent job.
     */
    val armedFromFlow: Flow? = null,
    /**
     * The summary screen's parsed fields, stashed at arm time (#431) so the
     * deferred commit's DASH_STOP payload keeps full fidelity (earnings,
     * duration, offer counts) even though the committing observation is a
     * grace timeout, not the summary itself.
     */
    val endFields: ParsedFields.SessionEndedFields? = null,
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

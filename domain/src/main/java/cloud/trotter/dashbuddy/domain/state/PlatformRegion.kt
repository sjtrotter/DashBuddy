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
     * This platform's presented + accepted-pending-consumption offers (#438 item 7 / B3), moved off
     * the shared global `FlowRegion.pendingOffer` so concurrent platforms no longer collide on one
     * scalar slot. Default-empty so existing snapshots deserialize unchanged (a live pending offer
     * at upgrade is lost — accepted, offers live ~30s; alpha).
     *
     * Lifecycle (in [cloud.trotter.dashbuddy.core.state.OfferLifecycle]): driven by THIS platform's
     * own observations only — pushed/replaced/enriched on own `OfferPresented` frames, click-latched,
     * eval-landed by offerHash, and resolved when the own flow leaves offer-presentation. An
     * accept-latched offer survives that edge as an [PendingOffer.acceptedAt]-marked
     * accepted-pending-consumption entry (this replaces the #526 accept stash) that the task edge
     * consumes into the job mint. Today the list holds at most one presented offer (the pre-B3
     * single-offer replace semantics per platform); N>1 waits on #251.
     *
     * Plain data (kotlinx-serializable); all timestamps are `obs.timestamp`, so it is replay-stable.
     */
    val pendingOffers: List<PendingOffer> = emptyList(),
    /**
     * The last **non-null** [Flow] this region actually stepped on (#438 item 5 / D3). The global
     * R0 [FlowRegion] is shared, so under concurrency `FlowRegion.flow` is whatever platform last
     * touched the screen; keying this region's lifecycle edges (PostTask entry/exit, task
     * retire/completion) off the *global* flow lets a foreign platform's frame fire THIS platform's
     * edges (a premature completion, a duplicate receipt bubble). This records the flow this
     * platform's own observations drove, so the edge diffs are per-region. (Since #438 B3 the accept
     * edge reads THIS region's own accepted-pending-consumption offer from [pendingOffers], so it is
     * structurally per-region too — the interim `offerBelongsToRegion` cross-region guard is gone.)
     *
     * Stamped by the [step] wrapper from `flowObs.flow` — NOT `nextFlow.flow`: a flow-less obs
     * (flow=null clicks/notifications) leaves it unchanged, because `nextFlow.flow` on such a frame
     * is the other platform's flow (the exact contamination this removes). Default-null so existing
     * snapshots deserialize unchanged; a null value falls back to the global flow at the read sites,
     * making single-platform behavior identical.
     */
    val lastActedFlow: Flow? = null,
    /**
     * Edge-gate state for the D6 store-lineage join-miss WARN (#526/#733): the `taskId` of the
     * dropoff for which the WARN was last emitted. The join-miss log is keyed **once per taskId** —
     * NOT per (taskId, customerNameHash) edge — because a two-form customer surface alternates the
     * hash A↔B per frame, and a per-hash key would re-storm the log on every flap (the field ×23).
     * Kept in region state (not a side channel) so the gate is pure and replay-deterministic.
     * Default-null so existing snapshots deserialize unchanged.
     */
    val lastJoinMissWarnTaskId: String? = null,
    /**
     * A dash running-total read that is parked, waiting out a settle window before it may move
     * [Session.runningEarnings] (#1029 — the **settle gate**).
     *
     * The platform renders that total as an animated digit-wheel, and captures land mid-animation.
     * `parseGlyphCurrency` throws out the malformed intermediates, but roughly one fielded read in
     * eight is well-FORMED and wrong ($470.00 during a $16.70 dash) — a string function cannot tell
     * that apart from a real figure, because nothing about the string is wrong. What distinguishes
     * them is TIME: a spin value is transient. So a parsed total that differs from the committed
     * one is parked here, and commits once it has stood **unchallenged** for
     * `GraceConfig.sessionPaySettleMs` — a different read REPLACES the park (with a fresh
     * deadline), a read equal to the committed total CLEARS it (the wheel is at rest), and the
     * commit itself happens by lazy expiry on the first observation AT or past
     * [PendingSessionPay.deadline] (a `SESSION_PAY_SETTLE` wake timer guarantees one arrives).
     *
     * REPETITION CANNOT BE THE DISCRIMINATOR, and that is not a style choice: `IdleFields.dedupeHash`
     * folds `sessionPay` into the screen's `Observation.identity()`, and `FrameGate.admit` drops a
     * frame whose identity equals the last admitted one. On a settled wheel ($16.70, $16.70, …) only
     * the FIRST frame ever reaches the state machine, so a "second agreeing read" would never arrive
     * and the figure would freeze for most of a dash. Elapsed time without contradiction is the only
     * signal available here. **This is the canonical statement of that rationale** — every other
     * site (the stepper, `ModeEffects`, `TimeoutType.SESSION_PAY_SETTLE`, CLAUDE.md) points here
     * rather than restating it.
     *
     * The park is **flow-scoped** ([PendingSessionPay.flow]): a read is evidence only while the
     * surface it was read from is on screen, so leaving that surface DROPS the park instead of
     * letting the wake timer commit a figure nothing can contradict any more — fail-null (#745).
     * The committed total simply stands, and a return to the surface re-parks.
     *
     * BOTH running-total feeds are gated: the on-dash earnings pill (`IdleFields.sessionPay`) and
     * the receipt's own "This dash so far" figure (`PostTaskFields.sessionEarnings`) — the receipt
     * renders the SAME digit-wheel component, so exempting it would leave the identical failure
     * mode open on the surface that closes a delivery. Conversely every write of
     * [Session.runningEarnings] that does NOT go through the gate (the PostTask-entry pay
     * accumulation, the dash-summary total) SUPERSEDES any park older than itself, so a stale park
     * can never expire over fresher evidence.
     *
     * Cost: a genuinely-changed total lands one settle window late, which for a figure the dasher is
     * glancing at is the right trade against showing them a number that never existed.
     *
     * Cleared whenever the session it describes begins or ends, and DROPPED wholesale by crash
     * recovery (`AppState.droppingSessionPayParks`): a restored park is pre-crash evidence whose
     * surface is long gone and whose wake timer no restore path re-arms — fail-null (#745).
     * Platform-agnostic: the gate is per-region state, keyed by nothing but this region's own
     * reads. Default-null so existing snapshots deserialize unchanged.
     */
    val pendingSessionPay: PendingSessionPay? = null,
) {
    /**
     * This platform's current PRESENTED offer — the accepted-pending-consumption survivors
     * ([PendingOffer.acceptedAt] non-null) excluded. The single-offer slot today (#251 makes N>1
     * reachable). The one SSOT for "what offer is on screen for this platform" — the live bubble
     * card (`LiveCardBuilder`), the HUD accept/decline dispatch (`BubbleViewModel`), and the
     * `:core:state` offer diffs all read it, so none re-derive it.
     */
    fun presentedOffer(): PendingOffer? = pendingOffers.lastOrNull { it.acceptedAt == null }
}

/**
 * A dash running-total read waiting out its settle window (#1029).
 * See [PlatformRegion.pendingSessionPay] for why the discriminator is elapsed
 * time rather than repetition (FrameGate identity dedup never re-admits an
 * identical wheel read).
 *
 * Plain data (kotlinx-serializable) so it survives crash-recovery replay;
 * resolution is driven by `obs.timestamp`, never a wall clock, keeping the
 * reducer pure.
 */
@Serializable
data class PendingSessionPay(
    /** The parsed total being held. */
    val value: Double,
    /** The obs.timestamp of the read that parked it. */
    val since: Long,
    /** Once an observation's timestamp reaches this, the value is committed. */
    val deadline: Long,
    /**
     * The R0 [Flow] the read was parked under — the surface it was read from.
     *
     * A read is evidence only while that surface is on screen: an offer overlay landing half a
     * second after a mid-spin pill read would otherwise leave the park unchallengeable (no other
     * screen carries a running total), and the wake timer would commit the spin value. Leaving the
     * surface therefore DROPS the park — the committed figure stands, and the next return to the
     * surface re-parks; that returning frame IS admitted, because its observation identity differs
     * from the interloper's.
     *
     * **Ownership is (this flow, the PLATFORM that put it on screen)**, not the flow alone. R0 is
     * SHARED across platforms — `FlowRegion.activePlatform` names whose screen last set it — while
     * `StateMachine.stepPlatforms` steps only `obs.platform`'s region, so a DoorDash park is never
     * stepped by an Uber frame and a flow-only test would let this platform's wake timer commit a
     * figure R0 stopped showing long ago (and two idle screens on two platforms defeat it outright:
     * R0 stays `Idle` throughout). A NON-flow observation — a timer, a click, a loopback — cannot
     * itself be the departure frame, so it checks ownership BEFORE the expiry and drops a park it
     * no longer owns instead of committing it. Another platform's screen therefore still drops this
     * platform's park; fail-null, and accepted — the alternative is committing a figure this
     * platform can no longer see.
     *
     * **Ownership must hold on BOTH sides of an observation** (#1052). Checking only the RESULTING
     * R0 is blind to a departure this region was never stepped for: another platform's frame moves
     * the shared R0 without reaching this region at all, and when the owner returns R0 reads owned
     * again — so the ORIGINAL deadline would commit a figure that was off screen for the whole
     * interlude. The stepper therefore also requires ownership on R0 as it was BEFORE the
     * observation, and drops the park when that fails, whatever the observation is; the returning
     * frame re-parks with a FRESH window. A flow-LESS observation (a timer, a click, a notification
     * carrying no flow) orders like a timer: ownership is settled before any expiry.
     *
     * **Leaving [Mode.Online] drops it too** (#1052). A paused or offline dash cannot change its
     * running total, and the pill's surface is gone even where R0 still reads the park's flow —
     * `dash_paused` declares a mode hint and NO flow, and the offline map keeps `Idle` — so nothing
     * on screen could contradict the park any more.
     */
    val flow: Flow,
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

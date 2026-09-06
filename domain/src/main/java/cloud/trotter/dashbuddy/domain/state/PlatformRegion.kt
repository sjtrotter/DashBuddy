package cloud.trotter.dashbuddy.domain.state

import kotlinx.serialization.Serializable

import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay
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
    /**
     * What the just-CLOSED job's post-delivery receipt looked like at the moment the job closed
     * (#1033 layer 2) — the minimal marker that says "this job's `DELIVERY_COMPLETED`s have been
     * minted, and here is the receipt they carried".
     *
     * `completeActiveJob` is the ONE place a job leaves the active slot, and it CLEARS
     * [lastPostTaskFields] as it goes — which is exactly why this marker is needed: after the close
     * there is nothing left in the region to say whether the completions were priced off an itemized
     * receipt or off a bare/absent one. A receipt that is EXPANDED after that close (the fielded
     * 2026-08-23 shape: collapsed → commit → expand 1.3 s late) then re-sets [lastPostTaskFields],
     * and `EffectMap` compares it against this marker to decide whether a `DELIVERY_RECEIPT_REPRICE`
     * is owed.
     *
     * **The re-price window is strictly between the close and the next job's mint** (#1033 review R1).
     * `lastAnnouncedPostTaskTaskId` falls back to `recentTasks.lastOrNull()`, so a marker that
     * outlived its job would let a NEXT job whose task screens were all missed anchor its receipt on
     * the PREVIOUS job's last drop — appending that job's money as a re-price of this one. The
     * stepper therefore clears this the moment any other job is active
     * (`clearClosedJobReceiptOnNewJob`), and the emitter independently refuses to fire while any job
     * is live.
     *
     * Derived wholly from the region's own records at the close, so it is replay-stable; cleared by
     * `endSession` (a dash's receipt must not survive into the next one). Fail-null by construction:
     * with no marker no re-price is ever emitted, so a close path that never stamps one (an
     * `endSession` bail) simply keeps today's behaviour. Default-null so existing snapshots
     * deserialize unchanged.
     */
    val lastClosedJobReceipt: ClosedJobReceipt? = null,
    /**
     * A receipt re-price decided by the stepper on THIS transition (#1033 review round 4) — see
     * [PendingReceiptReprice]. Set by the PostTask arm of `updateSessionFields` at the same moment it
     * updates [lastClosedJobReceipt], and cleared at the top of the next step, so it is a one-step
     * handoff to `EffectMap` rather than durable state. Default-null so existing snapshots
     * deserialize unchanged.
     */
    val pendingReceiptReprice: PendingReceiptReprice? = null,
    /**
     * The current job's FIRST-occurrence receipt anchors (#1033 review rounds 6–7) — see
     * [JobReceiptAnchors]. Read at the close into [ClosedJobReceipt]; kept across that close (the
     * receipt is still on screen after it), reset when another job becomes active, cleared at
     * `endSession`.
     */
    val jobReceiptAnchors: JobReceiptAnchors? = null,
    /**
     * `obs.timestamp` of the last accept-latch RESOLUTION on this platform (#1033 review round 6) —
     * the moment `OfferLifecycle` turned a latched offer into an accepted-pending-consumption
     * survivor, whether or not a job was ever minted from it.
     *
     * **Never cleared by the survivor's expiry, nor by the mint** — those are exactly the events that
     * used to make the machine forget an acceptance had happened and re-open the re-price window
     * minutes later. Cleared only at `endSession`: an accept belongs to the dash it was taken in.
     */
    val lastAcceptResolvedAt: Long? = null,
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
 * The receipt state of a job at the instant it closed (#1033 layer 2) — see
 * [PlatformRegion.lastClosedJobReceipt].
 *
 * Deliberately NOT the receipt itself: all the re-price decision needs is "which job" and "did the
 * completions that were just minted already carry THIS itemization". Keeping only the total + the
 * itemized flag also keeps the marker cheap to serialize into every snapshot.
 */
@Serializable
data class ClosedJobReceipt(
    /** The job whose completions were minted at this close. */
    val jobId: String,
    /**
     * When this job's receipt FIRST appeared on screen ([JobReceiptAnchors.firstEnteredAt] at the
     * close) — the anchor for the ONE ownership question the machine can actually answer (#1033
     * review round 6).
     *
     * **A receipt carries no job identity.** Rounds 2–5 each tried a heuristic to decide whether a
     * late-arriving itemization belonged to the closed job — the announce anchor (which falls back to
     * `recentTasks.lastOrNull()`), an accepted-since flag, a same-total match — and every one of them
     * leaked, because none of them is evidence about the receipt itself. What IS knowable is
     * temporal: while no acceptance has resolved since this receipt appeared, no other job can exist
     * to own a receipt, so the frame must be a re-render of this one's. The moment an acceptance
     * resolves, that guarantee is gone and nothing on a later frame restores it.
     *
     * So the rule is exactly: re-price while
     * `lastAcceptResolvedAt == null || lastAcceptResolvedAt < receiptSeenAt`. FIRST appearance, not
     * latest (round 7): moving the cutoff forward on a re-entry admits MORE receipts, because fewer
     * accepts then satisfy the comparison — an accept resolved during an offer overlay would be
     * stepped straight over. There is deliberately NO same-total exception — a total is not identity,
     * and it let a stacked job's $20 receipt redistribute the closed job's drops ($5/$15 over a real
     * $10/$10) and install a foreign tip/base split.
     *
     * **Documented consequence (fail-null, #745):** the stacked shape — accept the next offer while
     * this receipt is up, then expand it LATE — is refused. Layer 1's 8 s collapsed-receipt window is
     * the path that lands that expansion; an expansion later than that keeps the #691 `OFFER_PAY`
     * estimate. Null (no PostTask entry known at the close) refuses everything, same direction.
     */
    val receiptSeenAt: Long? = null,
    /**
     * How many times this job's drops have been re-priced (#1033 review round 6). Bumped on every
     * admitted decision and carried into the emitted effect key
     * (`log:DELIVERY_RECEIPT_REPRICE:<taskId>:<jobId>:r<n>`).
     *
     * The key USED to fold in the receipt's own hash, which made an X → Y → X itemization sequence
     * collide with its own first event: the durable `effects_fired` idempotency dropped the third
     * emission, so the row stayed at Y while this marker said X. A monotonic revision cannot repeat,
     * so the row always follows the marker.
     */
    val repriceRevision: Int = 0,
    /**
     * The itemization this marker's LAST decision was made from (#1033 review round 8) — the only
     * thing the stepper suppresses on, compared STRUCTURALLY (round 10).
     *
     * It was a `hashCode()` for one round, which silently swallowed a real correction: base $2.05 +
     * tip $5.50 and base $2.00 + tip $5.60 collide on Kotlin's `-1866903064`, so the second receipt
     * read as "already decided" and the row kept $7.55. `ParsedPay` is `@Serializable` and a data
     * class, so keeping the value itself costs a few bytes of snapshot and cannot alias.
     *
     * **The marker deliberately does NOT model what the completion rows hold.** It tried, through
     * rounds 6–7, and both attempts failed toward refusing a legitimate correction: mirroring the
     * mint's exit edge does not mirror its task-eligibility/final-shape filtering, and one task's
     * first completion cannot describe a multi-drop job at all. The stepper cannot know what
     * persisted — the projector can, and that is where "already priced" now lives
     * (`AnalyticsProjector.applyReceiptReprice` compares the row and no-ops).
     *
     * What is left here is the narrow, honest thing the stepper DOES know: whether it has already
     * decided this exact itemization, so a receipt frame re-rendering unchanged does not spend a
     * revision. A post-close expanded receipt whose itemization the mint already carried therefore
     * emits one redundant event per drop — "the receipt says X" — which the apply resolves to a
     * no-op. Cheap, and it cannot lose a correction.
     */
    val lastDecidedPay: ParsedPay? = null,
)

/**
 * The current job's receipt ownership anchor, which must describe the job's **FIRST** occurrence
 * rather than its latest (#1033 review round 7).
 *
 * Created on the job's first `PostTask` ENTRY, reset when any other job becomes active, cleared at
 * `endSession`.
 */
@Serializable
data class JobReceiptAnchors(
    /** The job that owned the flow when the receipt first appeared; null if it had already closed. */
    val jobId: String?,
    /**
     * `obs.timestamp` of the job's FIRST entry into `PostTask` — when its receipt first appeared.
     *
     * **Not the latest entry.** Leaving the receipt for an offer overlay and coming back is a
     * re-entry, and re-stamping there moved the ownership cutoff FORWARD — which admits MORE
     * receipts, because fewer accepts then satisfy `acceptResolvedAt >= receiptSeenAt`. An accept
     * that resolved during the overlay would have been stepped over entirely.
     */
    val firstEnteredAt: Long,
    /**
     * Has this job's flow left `PostTask` at least once — i.e. has the completion mint RUN for it?
     * (#1033 review round 10.)
     *
     * A purely structural fact about the flow; deliberately NOT a claim about what any completion
     * carried (round 8 deleted that, twice burnt). Its one use is the terminal-session teardown,
     * which must tell a drop whose completion was already emitted on an earlier PostTask exit — and
     * so has a row to correct — from one the bail is force-completing right now, which has none. A
     * mistake in either direction is caught downstream: the projector's by-(jobId, taskId) lookup
     * finds no row and counts a skip.
     */
    val exitedPostTask: Boolean = false,
)

/**
 * A receipt re-price the stepper DECIDED on this transition, handed to `EffectMap` to emit (#1033
 * review round 4).
 *
 * UDF: the decision is pure state (the stepper owns "is this receipt this job's, and is it owed a
 * re-price"), the effect diff only reports what was decided. The alternative — deciding inside
 * `EffectMap.diffReceiptReprice` — is what made the round-3 defects unfixable in place: an effect
 * diff cannot update [ClosedJobReceipt] as it emits, so the marker could never learn that the job
 * had just been re-priced.
 *
 * A **one-step handoff**: the stepper clears it at the top of the very next transition, before
 * anything else, so it describes exactly one step. `@Serializable` because it can be caught in a
 * snapshot mid-flight; on restore the next step's top-of-step clear drops it, so a restored value
 * can never re-emit (the effect diff also requires it to have CHANGED).
 */
@Serializable
data class PendingReceiptReprice(
    /** The closed job whose delivered drops are being re-priced. */
    val jobId: String,
    /** The late-expanded receipt itself — rides the payload verbatim. */
    val parsedPay: ParsedPay,
    /** `taskId -> this drop's apportioned share`; Σ == `parsedPay.total` to the cent. */
    val shares: Map<String, Double>,
    /**
     * The deciding observation's `obs.timestamp` — the emitted events' `occurredAt`. Carried
     * explicitly rather than read back off the region, so the emission is `obs`-driven and
     * replay-stable exactly as it was when the diff still saw the observation.
     */
    val decidedAt: Long,
    /**
     * [ClosedJobReceipt.repriceRevision] AFTER this decision — the effect key's uniqueness carrier.
     */
    val revision: Int,
    /** The capture the expanded receipt was read from, when the observation carried one (debug). */
    val sourceCaptureId: String? = null,
)

/**
 * A dash running-total read waiting out its settle window (#1029).
 * See [PlatformRegion.pendingSessionPay] for why the discriminator is elapsed
 * time rather than repetition (FrameGate identity dedup never re-admits an
 * identical wheel read).
 *
 * **A park is FROZEN, not discarded, while the dash is not [Mode.Online]; its window restarts on
 * the transition back to Online** (#1052 round 3 — the canonical statement of this rule; the
 * stepper's expiry block, `applyModeTransition` and CLAUDE.md §3 point here). A read taken under a
 * pause sheet is still the freshest evidence there is, so it parks in any mode; what a non-Online
 * dash cannot do is CONFIRM it — its total is not moving and no screen behind the sheet can
 * contradict the figure — so the expiry is skipped wholesale while non-Online: no commit, no drop.
 * Killing the park instead was round 1's and round 2's rule, and it stranded exactly the read the
 * gate exists to land: the fielded #605 flap keeps the mode at Paused across the online-implying
 * frames (the resume is graced), the confirmed resume arrives as a TIMER with no frame behind it,
 * and every later identical idle capture is dropped by `FrameGate`'s identity dedup — so a legitimate
 * $25.20 first seen while Paused would never be seen again. Re-basing [since]/[deadline] on the
 * transition INTO Online is what makes the frozen evidence land: the park must still stand a FULL
 * window unchallenged, with the ownership rules applying, before it may commit — and, since
 * round 4, only once a fresh readable read on its own surface has AGREED with it
 * ([unconfirmed]), because a window in which nothing was ever on screen challenges nothing.
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
     * Ownership is checked only while the dash is [Mode.Online]: a park on a non-Online region is
     * FROZEN (see [PendingSessionPay]), and the rules above resume the moment the dash does — the
     * first Online observation after a resume still has to own R0 on both sides.
     */
    val flow: Flow,
    /**
     * True while this park is RE-BASED pre-pause evidence that no live read has agreed with yet
     * (#1052 round 4).
     *
     * Re-basing on the resume ([PlatformRegionStepper]'s `applyModeTransition`) hands a frozen read
     * a brand-new window — and a window is only evidence if something could have challenged it.
     * On the fielded shape nothing can: the resume commits as a wake TIMER, the settle timer is
     * re-armed by that same re-base, and if no readable idle frame lands in between then the FIRST
     * observation of the new window is the settle fire itself. The old rule committed there, which
     * means a mid-spin figure read before the pause became the dasher's earnings on the strength of
     * a window in which nothing was ever on screen.
     *
     * So a re-based park is held UNCONFIRMED until a fresh read on its own surface agrees with it
     * (`settleSessionPay`'s equal-value arm clears the flag; a different value replaces the park
     * outright, which parks confirmed by construction). At the deadline an unconfirmed park is
     * DROPPED rather than committed: a null read means the wheel was unreadable, and a bare timer
     * means nothing was looked at at all — fail-null (#745). The committed figure stands, and the
     * settled value arrives later as a NEW observation identity (`FrameGate` admits it, because the
     * pause frames moved `lastIdentity`) and re-parks normally.
     *
     * Costs nothing when the wheel is stable and readable: the resume is exactly the point at which
     * FrameGate guarantees one admitted idle frame, since the paused frames displaced the identity
     * the pre-pause read was admitted under. Default false so an ordinary live park — and every
     * pre-#1052-round-4 snapshot — is confirmed by construction.
     */
    val unconfirmed: Boolean = false,
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

package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.model.ratings.RatingsSnapshot
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.AcceptStash
import cloud.trotter.dashbuddy.domain.state.AcceptedOfferEconomics
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.PendingModeResume
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.SessionType
import cloud.trotter.dashbuddy.domain.state.StoreNameMatch
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import cloud.trotter.dashbuddy.domain.state.TransitionKind
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import cloud.trotter.dashbuddy.domain.state.UNKNOWN_STORE

/**
 * Region 2+ stepper — per-platform durable state.
 *
 * Manages **screen-authoritative** mode transitions, session lifecycle with
 * grace periods, job lifecycle (offer → tasks → completion), and task lifecycle
 * (navigation → arrival → completion).
 *
 * Key principles:
 * - **Screens are authoritative.** A screen observation that implies a mode
 *   change applies it immediately — no threshold counting.
 * - **Clicks record user intent.** They participate in flow/lifecycle updates
 *   but do NOT drive mode transitions.
 * - **Session grace.** When transitioning to Offline (except via SessionEnded),
 *   the session is preserved with a grace deadline. If mode returns to Online
 *   within the grace window, the same session resumes.
 */
@Singleton
class PlatformRegionStepper @Inject constructor() {

    companion object {
        /** Max completed tasks retained per platform during a session. */
        const val MAX_RECENT_TASKS = 20

        /**
         * #526 D1: how long an accept stash stays consumable. An accept is always followed by the
         * task flow within a couple of minutes; a generous window recovers the F3 teardown race
         * while a stale stash still can't leak into an unrelated later job.
         */
        const val ACCEPT_STASH_GRACE_MS = 120_000L
    }

    /**
     * Deterministic entity-id mint (#344): derived only from replay-stable inputs
     * (platform, obs.timestamp, [PlatformRegion.mintCounter]) so crash-recovery
     * replay reproduces the live run's IDs. UUID.randomUUID broke effect
     * idempotency keys ("start_session:$sessionId") and diverged restored state
     * from already-persisted rows. Every mint MUST bump mintCounter in the same
     * copy() — use [offset] when minting more than one id per observation.
     */
    private fun mintId(
        kind: String,
        region: PlatformRegion,
        obs: Observation,
        offset: Long = 0,
    ): String = "$kind-${region.platform.wire}-${obs.timestamp}-${region.mintCounter + offset}"

    /**
     * #503 slice 3b: pre-create one customer-TBD dropoff placeholder per order an accepted offer
     * covers, so a Job owns ordered dropoffs for a stack (not just a single drop). Each dropoff
     * screen later RESOLVES its customer onto the next open placeholder by name (slice 3 + 3b-2).
     * The count comes from the offer's parsed order list (fallback 1 when the offer wasn't parsed);
     * ids are minted at [startOffset]..[startOffset]+count-1 off the region's mint counter, which the
     * caller advances past in the same copy().
     */
    private fun preCreatedDropoffs(
        region: PlatformRegion,
        obs: Observation,
        jobId: String,
        count: Int,
        startOffset: Long,
    ): List<Task> = (0 until count).map { i ->
        Task(
            taskId = mintId("task", region, obs, offset = startOffset + i),
            jobId = jobId,
            phase = TaskPhase.DROPOFF,
            customerNameHash = null,
            startedAt = obs.timestamp,
        )
    }

    /**
     * #526 D1: the accepted-offer inputs a job mint needs, sourced from EITHER the live pending offer
     * ([acceptInputsFromPending], accept-adjacent happy path) or the stash ([acceptInputsFromStash],
     * the F3 teardown-race recovery). One shape so [consumeAcceptIntoJob] is source-agnostic.
     */
    private data class AcceptInputs(
        val offerHash: String?,
        val economics: AcceptedOfferEconomics,
        /** Raw per-order store hints (`orders.map { storeName }`); empty when the offer wasn't parsed. */
        val storeHints: List<String>,
        /** One dropoff placeholder per order; fallback 1 when the offer wasn't parsed. */
        val dropoffCount: Int,
    )

    private fun acceptInputsFromPending(pending: PendingOffer?, acceptedAt: Long): AcceptInputs {
        val parsedOffer = pending?.offerFields?.parsedOffer
        val eval = pending?.evaluation
        val storeHints = parsedOffer?.orders?.map { it.storeName } ?: emptyList()
        return AcceptInputs(
            offerHash = pending?.offerHash,
            economics = AcceptedOfferEconomics(
                offerHash = pending?.offerHash,
                payAmount = eval?.payAmount ?: parsedOffer?.payAmount,
                netPay = eval?.netPayAmount,
                estMinutes = eval?.estimatedTimeMinutes ?: parsedOffer?.timeToCompleteMinutes?.toDouble(),
                distanceMiles = eval?.distanceMiles ?: parsedOffer?.distanceMiles,
                acceptedAt = acceptedAt,
            ),
            storeHints = storeHints,
            dropoffCount = storeHints.size.takeIf { it > 0 } ?: 1,
        )
    }

    private fun acceptInputsFromStash(stash: AcceptStash): AcceptInputs = AcceptInputs(
        offerHash = stash.offerHash,
        economics = AcceptedOfferEconomics(
            offerHash = stash.offerHash,
            payAmount = stash.payAmount,
            netPay = stash.netPay,
            estMinutes = stash.estMinutes,
            distanceMiles = stash.distanceMiles,
            acceptedAt = stash.acceptedAt,
        ),
        storeHints = stash.storeHints,
        dropoffCount = stash.storeHints.size.takeIf { it > 0 } ?: 1,
    )

    /**
     * #526 D1: consume an accepted offer into the job graph — fresh mint, #596 T2 close+mint on an
     * independent offer over a finished job, or add-on append. Clears [PlatformRegion.lastAcceptedOffer]
     * on every path (the accept is now realized). The single entry point shared by the accept-adjacent,
     * D1c add-on, and fallback branches.
     */
    private fun consumeAcceptIntoJob(
        region: PlatformRegion,
        obs: Observation.FlowObservation,
        inputs: AcceptInputs,
    ): PlatformRegion {
        val existing = region.activeJob ?: return mintFreshJobFromAccept(region, obs, inputs)

        // #596 T2: an accept while the active job is already physically complete is an INDEPENDENT
        // offer, not an add-on — do NOT fold it into a never-closed job (the 06-30 job-61 case: one
        // never-closed job swallowed three offers, $19 of $45.75 unattributed). Close the old job and
        // mint a fresh one. At accept time the final drop may still be inside its TASK_RETIRE grace
        // (activeTask not yet retired); commit that retire inline first (honest completion = pend.since,
        // matching retireActiveTask) so the completeness check sees the finished drop. Skipped when the
        // retire was armed by deliberating on THIS add-on offer (armedFromFlow == OfferPresented).
        run {
            val pend = region.pendingDestructive?.takeIf { it.kind == DestructiveKind.TASK_RETIRE }
            if (pend?.armedFromFlow == Flow.OfferPresented) return@run
            val justRetired = if (pend != null) region.activeTask?.copy(completedAt = pend.since) else null
            val recentForCheck =
                if (justRetired != null) region.recentTasks + justRetired else region.recentTasks
            if (isJobPhysicallyComplete(existing, recentForCheck, justRetired)) {
                val committed = if (justRetired != null) {
                    region.copy(
                        activeTask = null,
                        recentTasks = recentForCheck.takeLast(MAX_RECENT_TASKS),
                        pendingDestructive = null,
                    )
                } else region
                return mintFreshJobFromAccept(completeActiveJob(committed), obs, inputs)
            }
        }

        return appendAddOn(region, existing, obs, inputs)
    }

    /**
     * Mint a fresh Job from an accepted offer: jobId at offset 0, the D customer-TBD dropoff
     * placeholders at offsets 1..D, then (#526 D2) one PICKUP placeholder per distinct store at
     * offsets D+1..D+P — all in one copy() that advances the mint counter past the whole batch (#344).
     */
    private fun mintFreshJobFromAccept(
        base: PlatformRegion,
        obs: Observation.FlowObservation,
        inputs: AcceptInputs,
    ): PlatformRegion {
        val jobId = mintId("job", base, obs)
        val dropoffs = preCreatedDropoffs(base, obs, jobId, inputs.dropoffCount, startOffset = 1)
        return base.copy(
            activeJob = Job(
                jobId = jobId,
                offerStoreHint = inputs.storeHints,
                parentOfferHash = inputs.offerHash,
                acceptedOffers = listOf(inputs.economics),
                startedAt = obs.timestamp,
                tasks = dropoffs,
            ),
            // job(1) + D dropoffs, all minted in this one copy() (#344).
            mintCounter = base.mintCounter + 1 + inputs.dropoffCount,
            lastAcceptedOffer = null,
        )
    }

    /**
     * Append an add-on accept into the active job: its own D dropoff placeholders (offsets 0..D-1).
     * No job mint this step. Deduped by offerHash so a re-entered OfferPresented for the same offer
     * can't double-count.
     */
    private fun appendAddOn(
        region: PlatformRegion,
        existing: Job,
        obs: Observation.FlowObservation,
        inputs: AcceptInputs,
    ): PlatformRegion {
        val alreadyCounted = inputs.offerHash != null &&
            existing.acceptedOffers.any { it.offerHash == inputs.offerHash }
        if (alreadyCounted) return region.copy(lastAcceptedOffer = null)
        val addOnDropoffs = preCreatedDropoffs(region, obs, existing.jobId, inputs.dropoffCount, startOffset = 0)
        return region.copy(
            activeJob = existing.copy(
                acceptedOffers = existing.acceptedOffers + inputs.economics,
                offerStoreHint = existing.offerStoreHint + inputs.storeHints,
                tasks = existing.tasks + addOnDropoffs,
            ),
            mintCounter = region.mintCounter + inputs.dropoffCount,
            lastAcceptedOffer = null,
        )
    }

    fun step(
        prev: PlatformRegion,
        prevFlow: FlowRegion,
        nextFlow: FlowRegion,
        obs: Observation,
        policy: TransitionPolicy,
    ): PlatformRegion = armAcceptStash(
        reconcileDropoffStore(reconcileJobTasks(stepCore(prev, prevFlow, nextFlow, obs, policy))),
        nextFlow,
        obs,
    )

    /**
     * #526 D1a — mirror an accept-latched pending offer into [PlatformRegion.lastAcceptedOffer] so a
     * job can still be minted with full economics + placeholders when the `OfferPresented → task`
     * edge is skipped by a teardown frame (F3). Runs AFTER [stepCore] on every step: consumption
     * (inside `updateJobLifecycle`) reads a stash armed on a PRIOR step, then this re-mirrors THIS
     * step's offer.
     *
     * The mirror is idempotent (keyed by `offerHash`, [AcceptStash.acceptedAt] preserved across
     * re-mirrors); a superseding pending offer with a different hash and a stash older than the
     * accept grace both clear it, so no corpse rides the snapshot. Session end clears it in
     * [endSession]. Pure: [isAcceptLatched] + `obs.timestamp` only.
     */
    private fun armAcceptStash(
        region: PlatformRegion,
        nextFlow: FlowRegion,
        obs: Observation,
    ): PlatformRegion {
        var stash = region.lastAcceptedOffer
        val po = nextFlow.pendingOffer
        // Supersession: a different offer on screen invalidates a stale stash.
        if (stash != null && po != null && po.offerHash != stash.offerHash) stash = null
        // Lazy expiry: don't carry a corpse.
        if (stash != null && isStashExpired(stash, obs)) stash = null
        // Mirror an accept-latched pending offer.
        if (po != null && po.isAcceptLatched()) {
            val parsedOffer = po.offerFields.parsedOffer
            val eval = po.evaluation
            val acceptedAt = stash?.takeIf { it.offerHash == po.offerHash }?.acceptedAt ?: obs.timestamp
            stash = AcceptStash(
                offerHash = po.offerHash,
                payAmount = eval?.payAmount ?: parsedOffer.payAmount,
                netPay = eval?.netPayAmount,
                estMinutes = eval?.estimatedTimeMinutes ?: parsedOffer.timeToCompleteMinutes?.toDouble(),
                distanceMiles = eval?.distanceMiles ?: parsedOffer.distanceMiles,
                storeHints = parsedOffer.orders.map { it.storeName },
                acceptedAt = acceptedAt,
            )
        }
        return if (stash === region.lastAcceptedOffer) region else region.copy(lastAcceptedOffer = stash)
    }

    private fun isStashExpired(stash: AcceptStash, obs: Observation): Boolean =
        obs.timestamp - stash.acceptedAt > ACCEPT_STASH_GRACE_MS

    /**
     * #526: a dropoff's store is resolved from the job's PICKUP lineage, not from the dropoff card's
     * own text format (which varies per merchant — `Target (02426)` / `Maple Street Biscuit - Alamo
     * Ranch` — and can't be reliably parsed, see the rule comment). A dropoff always follows its
     * pickup, and the job already holds the pickups with their authoritative, screen-parsed store
     * names, so:
     *  - **one distinct pickup store** (the common single delivery, and same-store stacks) → every
     *    dropoff is that store. No dropoff parse needed; zero format risk.
     *  - **multiple pickup stores** (a multi-store stack) → match the dropoff's parsed store *candidate*
     *    (the rule's best-effort `storeName`) to the pickup with the most shared leading name tokens.
     *    The match also VALIDATES: a garbage candidate matches no pickup → resolves to null, never a
     *    wrong store (a wrong store would silently mis-attribute the order/economics).
     *  - **no pickup seen yet** → keep the candidate as-is.
     * The resolved value is the matched pickup's canonical (offer-aligned) store name, so pickup and
     * dropoff carry a consistent label. The dropoff card's running-key form still lives on the
     * payout for the post-session store-entity projector (#159).
     */
    private fun reconcileDropoffStore(region: PlatformRegion): PlatformRegion {
        val active = region.activeTask ?: return region
        if (active.phase != TaskPhase.DROPOFF) return region
        val pickupStores = region.recentTasks
            .filter { it.jobId == active.jobId && it.phase == TaskPhase.PICKUP && it.storeName != null }
            .map { it.storeName!! }
            .distinctBy { it.lowercase(Locale.ROOT) }
        val resolved = when {
            pickupStores.size == 1 -> pickupStores.single()
            pickupStores.isEmpty() -> active.storeName
            else -> {
                val cand = active.storeName ?: return region
                // Match the dropoff's candidate to the pickup with the most shared brand tokens
                // (the SSOT comparison in [StoreNameMatch]); no match → null, never a wrong store.
                StoreNameMatch.bestMatch(pickupStores, cand)
            }
        }
        return if (resolved == active.storeName) region else region.copy(activeTask = active.copy(storeName = resolved))
    }

    /**
     * Step 1 of the #503 job-container re-model (additive): mirror the active job's task lineage
     * onto [Job.tasks] after every core step — the completed tasks still in [PlatformRegion.recentTasks]
     * plus the active task. Pure derivation from existing region state; nothing reads [Job.tasks] yet,
     * so this changes no behavior. Later slices make the list authoritative (resume from it; create
     * dropoff subtasks onto it from the offer).
     */
    private fun reconcileJobTasks(region: PlatformRegion): PlatformRegion {
        val job = region.activeJob ?: return region
        val lineage = region.recentTasks.filter { it.jobId == job.jobId } +
            listOfNotNull(region.activeTask?.takeIf { it.jobId == job.jobId })
        val lineageIds = lineage.mapTo(HashSet()) { it.taskId }
        // #503 slice 3: preserve pre-created (offer-spawned, not-yet-activated) subtasks — those on
        // the job but not yet in the active/completed lineage — then the lineage. Once an expected
        // subtask is activated its taskId enters the lineage, so it isn't double-listed.
        val pending = job.tasks.filter { it.taskId !in lineageIds && it.completedAt == null }
        val jobTasks = pending + lineage
        return if (job.tasks == jobTasks) region else region.copy(activeJob = job.copy(tasks = jobTasks))
    }

    private fun stepCore(
        prev: PlatformRegion,
        prevFlow: FlowRegion,
        nextFlow: FlowRegion,
        obs: Observation,
        policy: TransitionPolicy,
    ): PlatformRegion {
        var current = prev

        // Lazy expiry: a pending destructive transition (dash-end / task-retire)
        // whose deadline has passed is confirmed — committed now. Driven by
        // obs.timestamp (never a wall clock) so crash-recovery replay matches.
        // Runs BEFORE the timeout branch: a routed timeout (#342) is exactly the
        // kind of non-flow observation that must be able to commit an overdue
        // provisional transition.
        current.pendingDestructive?.let { pend ->
            if (obs.timestamp > pend.deadline) {
                // Commit stamped at pend.since — the obs.timestamp of the signal
                // that armed the grace — not the deadline: the dash/task really
                // ended when the destructive signal appeared, the grace only
                // delayed our belief in it (#431).
                current = commitDestructive(current, pend.kind, pend.since)
            }
        }

        // #605: lazy expiry of a graced screen-implied resume out of Paused. A
        // sustained online past the deadline (no intervening paused frame cancelled
        // it) commits the resume once — flip Paused→Online. Runs BEFORE the timeout
        // branch so the MODE_RESUME_COMMIT wake timer (a non-flow observation) can
        // commit an overdue resume, exactly like the destructive lazy expiry above.
        // Driven by obs.timestamp (never a wall clock) so crash-recovery replay
        // matches. Independent of pendingDestructive: during the field flap that slot
        // is BUSY holding the just-completed delivery's TASK_RETIRE grace.
        current.pendingModeResume?.let { pend ->
            if (obs.timestamp > pend.deadline) {
                current = commitModeResume(current, obs, policy)
            }
        }

        // Handle timeout-driven transitions
        if (obs is Observation.Timeout) return handleTimeout(current, obs, policy)

        // Authoritative dash-start signal (#279-B): the set-end-time screen while a
        // dash-end is pending in its grace window means the old dash really ended
        // and a new one is starting — commit the end now so the next Online mints
        // a fresh session instead of resuming the old one.
        current.pendingDestructive?.let { pend ->
            if (pend.kind == DestructiveKind.SESSION_END) {
                val startParsed = (obs as? Observation.FlowObservation)?.parsed
                if ((startParsed as? ParsedFields.IdleFields)?.startingSession == true) {
                    // Honest end time = when the destructive signal appeared (#431).
                    current = endSession(current, pend.since)
                }
            }
        }

        val flowObs = obs as? Observation.FlowObservation ?: return current

        // Resolve what mode this observation implies
        val impliedMode = policy.resolveMode(flowObs.flow, flowObs.modeHint)

        val afterMode = when {
            // No mode signal
            impliedMode == null -> current.copy(lastObservedAt = obs.timestamp)

            // Same mode — confirmed. A Paused-implying frame also CANCELS any armed
            // resume grace (#605): the modal is still up, the online flap was noise.
            impliedMode == current.mode -> current.copy(
                lastObservedAt = obs.timestamp,
                lastTransitionKind = TransitionKind.Confirmed,
                pendingModeResume = if (impliedMode == Mode.Paused) null else current.pendingModeResume,
            )

            // Click — records user intent, does NOT drive mode
            flowObs is Observation.Click -> current.copy(lastObservedAt = obs.timestamp)

            // #605: a screen-implied resume OUT of Paused is GRACED, not immediate.
            // DoorDash's pause sheet sits on the just-completed delivery summary, so
            // frames flap paused ↔ online; flipping on the first online frame re-mints
            // DASH_PAUSED and a spurious resume card on every edge. Arm a short pending
            // (keeping the original `since` so repeated online frames don't push the
            // deadline out) and STAY Paused: a paused frame cancels it (above), sustained
            // online past the deadline commits it (lazy expiry / MODE_RESUME_COMMIT timer),
            // and an OfferPresented screen — authoritative online evidence, structurally
            // absent from the flap — commits immediately (excluded here → falls to the
            // authoritative else). Screen-only: notifications carry no mode hints (verified),
            // and keeping the grace to the screen channel matches the observed defect.
            current.mode == Mode.Paused && impliedMode == Mode.Online &&
                obs is Observation.Screen && flowObs.flow != Flow.OfferPresented -> {
                val since = current.pendingModeResume?.since ?: obs.timestamp
                current.copy(
                    lastObservedAt = obs.timestamp,
                    pendingModeResume = PendingModeResume(
                        since = since,
                        deadline = since + policy.pauseResumeGraceMs,
                    ),
                )
            }

            // Screen or Notification — authoritative, apply immediately
            else -> {
                val kind = policy.classify(
                    current.mode, impliedMode, flowObs.expectedOutcomes, flowObs,
                )
                val transitioned = applyModeTransition(current, impliedMode, obs, kind, policy)
                // Heal lifecycle when coming Online from Offline (app restart
                // mid-task) or on any Unexpected transition. healActiveLifecycle
                // self-guards: only acts if the flow is a task flow with no
                // active job, so it's safe to call broadly.
                if (kind == TransitionKind.Unexpected ||
                    (current.mode == Mode.Offline && impliedMode == Mode.Online)) {
                    healActiveLifecycle(transitioned, obs)
                } else {
                    transitioned
                }
            }
        }

        // Update session/job/task lifecycle based on flow changes
        return updateLifecycle(afterMode, prevFlow, nextFlow, flowObs, policy)
    }

    // =========================================================================
    // MODE TRANSITIONS
    // =========================================================================

    private fun applyModeTransition(
        prev: PlatformRegion,
        newMode: Mode,
        obs: Observation,
        kind: TransitionKind,
        policy: TransitionPolicy,
    ): PlatformRegion {
        var region = prev.copy(
            mode = newMode,
            lastObservedAt = obs.timestamp,
            lastTransitionKind = kind,
        )

        // Session lifecycle on mode transitions. An authoritative `session:ended`
        // is committed mode-independently in updateLifecycle (covers both the
        // before-idle and after-idle orderings), so it isn't special-cased here.
        when {
            prev.mode != Mode.Online && newMode == Mode.Online -> {
                val pend = region.pendingDestructive
                if (pend?.kind == DestructiveKind.SESSION_END && !pend.authoritative &&
                    region.session != null
                ) {
                    // Grace active + the same session is still present → a genuine
                    // resume (a transient offline flash). A real new-dash start
                    // would already have committed the end in step() (startingSession).
                    // An AUTHORITATIVE pending (armed by the dash summary, #431) is
                    // deliberately NOT cancelled here: a post-summary online flash
                    // must not resurrect a really-ended session. Only a task-flow
                    // observation cancels it (updateTaskLifecycle).
                    region = region.copy(pendingDestructive = null)
                } else if (region.session == null) {
                    // No session — start a fresh one.
                    region = region.copy(
                        session = Session(
                            sessionId = mintId("session", region, obs),
                            startedAt = obs.timestamp,
                        ),
                        mintCounter = region.mintCounter + 1,
                    )
                }
            }
            prev.mode != Mode.Offline && newMode == Mode.Offline -> {
                // Non-authoritative offline — arm a provisional dash-end, keeping
                // the session alive until it's confirmed or cancelled.
                if (region.session != null &&
                    region.pendingDestructive?.kind != DestructiveKind.SESSION_END
                ) {
                    region = region.copy(
                        pendingDestructive = PendingDestructive(
                            kind = DestructiveKind.SESSION_END,
                            since = obs.timestamp,
                            deadline = obs.timestamp + policy.gracePeriodMs,
                        ),
                    )
                }
            }
        }

        // #605: any COMMITTED mode transition out of Paused resolves a graced
        // screen-implied resume — clear the pending. Covers all three exits: a
        // sustained-online commit (Paused→Online), an instant OfferPresented commit
        // (Paused→Online), and the pause-safety timeout (Paused→Offline).
        if (prev.mode == Mode.Paused && newMode != Mode.Paused) {
            region = region.copy(pendingModeResume = null)
        }

        return region
    }

    /**
     * Commit a graced screen-implied resume out of [Mode.Paused] (#605) — the grace
     * lapsed sustained-online (lazy expiry / `MODE_RESUME_COMMIT` wake timer) or an
     * `OfferPresented` screen proved online. Mirrors [applyModeTransition]'s
     * Paused→Online path (same-session grace-resume vs. fresh-session logic); that
     * function also nulls [PendingModeResume] on any transition out of Paused.
     */
    private fun commitModeResume(
        region: PlatformRegion,
        obs: Observation,
        policy: TransitionPolicy,
    ): PlatformRegion =
        applyModeTransition(region, Mode.Online, obs, TransitionKind.Expected, policy)

    /**
     * When an unexpected transition fires (e.g., app launched mid-pickup),
     * synthesize missing lifecycle entities so the state is consistent.
     */
    private fun healActiveLifecycle(region: PlatformRegion, obs: Observation): PlatformRegion {
        val flowObs = obs as? Observation.FlowObservation ?: return region
        val flow = flowObs.flow ?: return region

        // If we're healing into an active task flow but have no active job/task,
        // synthesize them with recovered=true
        if (flow.isTaskFlow() && region.activeJob == null) {
            val taskPhase = flow.toTaskPhase() ?: return region
            val parsed = flowObs.parsed
            val storeName = (parsed as? ParsedFields.TaskFields)?.storeName

            val jobId = mintId("job", region, obs)
            val taskId = mintId("task", region, obs, offset = 1)

            return region.copy(
                activeJob = Job(
                    jobId = jobId,
                    offerStoreHint = listOfNotNull(storeName),
                    parentOfferHash = null, // unknown — healed
                    startedAt = obs.timestamp,
                ),
                activeTask = Task(
                    taskId = taskId,
                    jobId = jobId,
                    phase = taskPhase,
                    storeName = storeName,
                    startedAt = obs.timestamp,
                    recovered = true,
                ),
                mintCounter = region.mintCounter + 2,
            )
        }

        return region
    }

    // =========================================================================
    // TIMEOUT HANDLING
    // =========================================================================

    private fun handleTimeout(
        prev: PlatformRegion,
        obs: Observation.Timeout,
        policy: TransitionPolicy,
    ): PlatformRegion {
        return when (obs.type) {
            TimeoutType.SESSION_PAUSED_SAFETY -> {
                // Pause timer expired — transition to offline via applyModeTransition
                // so it gets grace treatment
                if (prev.mode == Mode.Paused) {
                    applyModeTransition(
                        prev, Mode.Offline, obs, TransitionKind.Expected, policy,
                    )
                } else prev
            }
            else -> prev // Automation timeouts handled by EffectMap
        }
    }

    // =========================================================================
    // LIFECYCLE MANAGEMENT
    // =========================================================================

    /**
     * Update session/job/task based on flow transitions.
     */
    private fun updateLifecycle(
        region: PlatformRegion,
        prevFlow: FlowRegion,
        nextFlow: FlowRegion,
        obs: Observation.FlowObservation,
        policy: TransitionPolicy,
    ): PlatformRegion {
        // Authoritative session end: the dash-summary screen. It no longer ends
        // the session on the spot (#431) — one misrecognized frame used to split
        // a live session irrecoverably. Instead it arms (or tightens) a SHORT
        // authoritative grace: a contradicting task-flow frame inside the window
        // cancels (misrecognition), anything else lets it commit at the deadline
        // (GRACE_COMMIT timer or lazy expiry). The summary's parsed fields ride
        // the pending so the deferred DASH_STOP payload keeps full fidelity.
        // Runs before the Offline early-return below because the summary commonly
        // shows AFTER the idle/offline screen, mid-grace.
        if (obs.flow == Flow.SessionEnded && region.session != null) {
            val endFields = obs.parsed as? ParsedFields.SessionEndedFields
            val newDeadline = obs.timestamp + policy.authoritativeGraceMs
            val existing = region.pendingDestructive
            val pend = if (existing?.kind == DestructiveKind.SESSION_END) {
                // Offline-grace already armed (idle/offline before summary) —
                // tighten to the short window, keep the original `since` (the
                // earliest destructive signal is the honest end time).
                existing.copy(
                    deadline = minOf(existing.deadline, newDeadline),
                    authoritative = true,
                    endFields = endFields ?: existing.endFields,
                )
            } else {
                PendingDestructive(
                    kind = DestructiveKind.SESSION_END,
                    since = obs.timestamp,
                    deadline = newDeadline,
                    authoritative = true,
                    endFields = endFields,
                )
            }
            return region.copy(pendingDestructive = pend)
        }
        if (region.mode == Mode.Offline) {
            // Clear the idle anchor and any TASK_RETIRE pending, but PRESERVE a
            // SESSION_END pending — that IS the graced-offline state.
            val keptPending = region.pendingDestructive
                ?.takeIf { it.kind == DestructiveKind.SESSION_END }
            return region.copy(idleEnteredAt = null, pendingDestructive = keptPending)
        }

        var r = region
        val prev = prevFlow.flow
        val next = nextFlow.flow

        // Update session fields from observations
        r = updateSessionFields(r, obs)

        // Accumulate delivery pay when entering PostTask
        if (prev != Flow.PostTask && next == Flow.PostTask) {
            val postFields = obs.parsed as? ParsedFields.PostTaskFields
            if (postFields != null && postFields.totalPay > 0) {
                r.session?.let { session ->
                    val accumulated = session.accumulatedDeliveryPay + postFields.totalPay
                    val best = maxOf(session.runningEarnings, accumulated)
                    r = r.copy(session = session.copy(
                        accumulatedDeliveryPay = accumulated,
                        runningEarnings = best,
                    ))
                }
            }
        }

        // Update ratings if we got a ratings observation
        r = updateRatings(r, obs)

        // Job lifecycle
        r = updateJobLifecycle(r, prev, next, prevFlow, nextFlow, obs)

        // Task lifecycle
        r = updateTaskLifecycle(r, prev, next, obs, policy)

        // Idle anchor: track when we started waiting for offers
        r = when {
            next == Flow.Idle && r.mode == Mode.Online && r.idleEnteredAt == null ->
                r.copy(idleEnteredAt = obs.timestamp)
            (next != Flow.Idle || r.mode != Mode.Online) && r.idleEnteredAt != null ->
                r.copy(idleEnteredAt = null)
            else -> r
        }

        return r
    }

    private fun updateSessionFields(region: PlatformRegion, obs: Observation.FlowObservation): PlatformRegion {
        val parsed = obs.parsed
        var r = region

        when (parsed) {
            is ParsedFields.IdleFields -> {
                if (parsed.zoneName != null) r = r.copy(zoneName = parsed.zoneName)
                if (parsed.sessionType != null) r = r.copy(sessionType = parsed.sessionType)
                r.session?.let { session ->
                    val pay = parsed.sessionPay
                    if (pay != null) {
                        r = r.copy(session = session.copy(runningEarnings = pay))
                    }
                }
            }
            is ParsedFields.PostTaskFields -> {
                val payHash = parsed.parsedPay?.hashCode()
                // Stamp the per-task announcement gate so EffectMap.diffPostTask
                // can detect "first time seeing PostTask for this taskId". The
                // completing task is the still-active one while its retire
                // grace is pending (#431 pt 2), falling back to the last
                // committed task. MUST be the same resolution diffPostTask
                // uses — the old recentTasks-only stamp lagged the commit by
                // one frame and double-fired the receipt bubble on the
                // expanded re-observation.
                val postTaskTaskId = r.activeTask?.taskId ?: r.recentTasks.lastOrNull()?.taskId
                r = r.copy(
                    lastPostTaskPayHash = payHash,
                    lastPostTaskFields = parsed,
                    lastAnnouncedPostTaskTaskId = postTaskTaskId ?: r.lastAnnouncedPostTaskTaskId,
                )
                r.session?.let { session ->
                    val earnings = parsed.sessionEarnings
                    if (earnings != null) {
                        r = r.copy(session = session.copy(runningEarnings = earnings))
                    }
                }
            }
            is ParsedFields.SessionEndedFields -> {
                r.session?.let { session ->
                    r = r.copy(session = session.copy(runningEarnings = parsed.totalEarnings))
                }
            }
            is ParsedFields.PausedFields -> {
                // Pause fields are handled by mode inference, not session
            }
            else -> { /* no session updates */ }
        }

        return r
    }

    private fun updateRatings(region: PlatformRegion, obs: Observation.FlowObservation): PlatformRegion {
        val parsed = obs.parsed as? ParsedFields.RatingsFields ?: return region
        return region.copy(
            ratings = RatingsSnapshot(
                capturedAt = obs.timestamp,
                acceptanceRate = parsed.acceptanceRate,
                completionRate = parsed.completionRate,
                onTimeRate = parsed.onTimeRate,
                customerRating = parsed.customerRating,
                deliveriesLast30Days = parsed.deliveriesLast30Days,
                lifetimeDeliveries = parsed.lifetimeDeliveries,
                originalItemsFoundRate = parsed.originalItemsFoundRate,
                totalItemsFoundRate = parsed.totalItemsFoundRate,
                substitutionIssuesRate = parsed.substitutionIssuesRate,
                itemsWithQualityIssuesRate = parsed.itemsWithQualityIssuesRate,
                itemsWrongOrMissingRate = parsed.itemsWrongOrMissingRate,
                lifetimeShoppingOrders = parsed.lifetimeShoppingOrders,
            ),
        )
    }

    private fun updateJobLifecycle(
        region: PlatformRegion,
        prevFlowVal: Flow,
        nextFlowVal: Flow,
        prevFlow: FlowRegion,
        nextFlow: FlowRegion,
        obs: Observation.FlowObservation,
    ): PlatformRegion {
        // Offer accepted → capture its economics onto the job. A first accept STARTS a new
        // Job; an add-on accepted while a job is already active APPENDS (so stacked pay,
        // time, and distance accumulate) rather than being silently dropped. Deduped by
        // offerHash so a re-entered OfferPresented for the same offer (screen oscillation)
        // does not double-count.
        if (prevFlowVal == Flow.OfferPresented && nextFlowVal.isTaskFlow()) {
            // Accept-adjacent (happy path): the offer is still on prevFlow. Read it straight off the
            // pending offer (the direct source); prefer the stash's acceptedAt when it matches this
            // offer — the accept-CLICK time, not this task frame (#526 D1a). Consumption clears the
            // stash inside the mint helpers.
            val pending = prevFlow.pendingOffer
            val stash = region.lastAcceptedOffer?.takeIf { !isStashExpired(it, obs) }
            val acceptedAt = stash?.takeIf { it.offerHash == pending?.offerHash }?.acceptedAt ?: obs.timestamp
            return consumeAcceptIntoJob(region, obs, acceptInputsFromPending(pending, acceptedAt))
        }

        // #526 D1c: the F3 race — an add-on accepted while a job is active, but a `waiting_for_offer`
        // teardown popped the pending offer BEFORE the first task frame, so the accept-adjacent
        // branch above never fired. Consume the stash as an add-on the moment the task flow resumes.
        // The offerHash dedup inside [consumeAcceptIntoJob]/[appendAddOn] makes a double-fold
        // impossible; the #596 T2 physically-complete check inside it keeps an independent offer over
        // a finished job from folding. Fires once (the stash clears on consumption).
        if (nextFlowVal.isTaskFlow() && region.activeJob != null && region.lastAcceptedOffer != null) {
            val stash = region.lastAcceptedOffer?.takeIf { !isStashExpired(it, obs) }
            if (stash != null) return consumeAcceptIntoJob(region, obs, acceptInputsFromStash(stash))
            return region.copy(lastAcceptedOffer = null)
        }

        // Job start: task flow without active job (recovery, mid-session restart, or the F3 race
        // where the accept edge was skipped entirely).
        if (nextFlowVal.isTaskFlow() && region.activeJob == null) {
            // #526 D1: a valid accept stash → mint the FULL offer-shaped job (economics + dropoff +
            // pickup placeholders + store hints), recovering the F3 teardown race. Else today's bare
            // fallback (no accept was ever seen — genuine recovery/restart).
            val stash = region.lastAcceptedOffer?.takeIf { !isStashExpired(it, obs) }
            if (stash != null) return consumeAcceptIntoJob(region, obs, acceptInputsFromStash(stash))
            val parsed = obs.parsed as? ParsedFields.TaskFields
            return region.copy(
                activeJob = Job(
                    jobId = mintId("job", region, obs),
                    offerStoreHint = listOfNotNull(parsed?.storeName),
                    parentOfferHash = null,
                    startedAt = obs.timestamp,
                ),
                mintCounter = region.mintCounter + 1,
                lastAcceptedOffer = null,
            )
        }

        // Post-task: keep job alive through PostTask for payout capture
        // Job ends when we leave PostTask for non-task flow
        if (prevFlowVal == Flow.PostTask && !nextFlowVal.isTaskFlow() && nextFlowVal != Flow.PostTask && nextFlowVal != Flow.OfferPresented) {
            return completeActiveJob(region)
        }

        return region
    }

    private fun updateTaskLifecycle(
        region: PlatformRegion,
        prevFlowVal: Flow,
        nextFlowVal: Flow,
        obs: Observation.FlowObservation,
        policy: TransitionPolicy,
    ): PlatformRegion {
        val parsed = obs.parsed

        // Task flow → create or update active task
        if (nextFlowVal.isTaskFlow()) {
            val taskPhase = nextFlowVal.toTaskPhase() ?: return region
            val taskSubFlow = nextFlowVal.toTaskSubFlow() ?: return region
            val taskFields = parsed as? ParsedFields.TaskFields

            val currentTask = region.activeTask
            val jobId = region.activeJob?.jobId ?: return region

            // Phase/subflow change → new task or update.
            //
            // Stacked-pickup transition: same PICKUP phase, but the platform has
            // routed us from a confirmed-arrived store back to navigation toward
            // a different store. Treat as a new task so the second pickup gets
            // its own taskId, its own odometer leg, and its own flow-card.
            //
            // Three signals combined for robustness: we must have already
            // arrived at the previous store; the new screen must be a
            // navigation/pre-arrival (subflow=NAVIGATION); and the parsed
            // storeName must be a different, non-Unknown name. Any single
            // signal alone is vulnerable to parser flakiness (notably the
            // pickup_arrival storeName parser, which picks the wrong
            // instructions_title on multi-node screens — see the field-test
            // 2026-05-17 Chili's capture).
            val isStackedPickupTransition = currentTask != null &&
                currentTask.phase == TaskPhase.PICKUP &&
                taskPhase == TaskPhase.PICKUP &&
                currentTask.arrivedAt != null &&
                taskSubFlow == TaskSubFlow.NAVIGATION &&
                taskFields?.storeName != null &&
                !taskFields.storeName.equals(UNKNOWN_STORE, ignoreCase = true) &&
                taskFields.storeName != currentTask.storeName

            // Stacked-dropoff transition: symmetric to the pickup case but keyed
            // on customerAddressHash — arrived at one customer, now navigating to
            // a different one. Lets back-to-back dropoffs mint distinctly even if
            // no post:task is observed between them.
            val isStackedDropoffTransition = currentTask != null &&
                currentTask.phase == TaskPhase.DROPOFF &&
                taskPhase == TaskPhase.DROPOFF &&
                currentTask.arrivedAt != null &&
                taskSubFlow == TaskSubFlow.NAVIGATION &&
                // #565: a stacked transition means "arrived at customer A, now navigating to a
                // DIFFERENT customer B" — which is only possible if the active task ALREADY HAD a
                // customer. A null→present customer is the FIRST resolution of a customer-TBD
                // placeholder dropoff (#503 slice 3), not a transition to a new drop. Without this
                // guard, the customer-bearing frame that finally resolves an active, customer-less
                // placeholder (a genuine handoff screen activated it customer-less first) was treated
                // as a stacked transition and fell through to a FRESH mint — orphaning the placeholder
                // as a dead customer-less husk and re-minting the card (06-21 Walgreens noon: task-11
                // placeholder → spurious task-13). A present prior customer keeps real stacks distinct.
                currentTask.customerNameHash != null &&
                taskFields?.customerAddressHash != null &&
                taskFields.customerAddressHash != currentTask.customerAddressHash &&
                // #498 task-path: only a genuinely DIFFERENT customer starts a new stacked dropoff —
                // gate on the stable customer NAME hash, not just the address. An unstable dropoff
                // address parse split one physical drop into two tasks on 06-17 (task-39/-40 carried
                // the same name hash f5b3497a but different address hashes). A present, changed name
                // is required; a null/unchanged name is the same customer, so update, don't re-mint.
                taskFields.customerNameHash != null &&
                taskFields.customerNameHash != currentTask.customerNameHash

            if (currentTask == null ||
                currentTask.phase != taskPhase ||
                isStackedPickupTransition ||
                isStackedDropoffTransition
            ) {
                // #503 slice 2/3b-2: returning to a prior subtask of this job (A→B→A, or back to a
                // store after an offer interlude that retired the task) RESUMES its identity instead
                // of re-minting. The single activeTask slot loses identity on every phase switch; the
                // Job's task lineage in recentTasks lets us restore it.
                //
                // Dropoff is re-matched on the STABLE customer-NAME hash, NOT the address (#498:
                // dropoff addresses drift between frames, so an address key split one physical drop
                // into two). This runs even under a stacked-dropoff transition, so returning to an
                // earlier stacked drop routes to it instead of minting a duplicate. Pickup re-matches
                // by store, but only when the platform did NOT signal a genuinely-new stacked pickup
                // (two distinct orders at the same store must stay distinct — the same-store add-on
                // "fold in, don't re-mint" of #499 is the !isStackedPickupTransition case).
                val resumable = when {
                    taskPhase == TaskPhase.DROPOFF && taskFields?.customerNameHash != null ->
                        region.recentTasks.lastOrNull {
                            it.jobId == jobId && it.phase == TaskPhase.DROPOFF &&
                                it.customerNameHash == taskFields.customerNameHash
                        }
                    taskPhase == TaskPhase.PICKUP && !isStackedPickupTransition ->
                        region.recentTasks.lastOrNull {
                            it.jobId == jobId && it.phase == TaskPhase.PICKUP &&
                                it.storeName != null && it.storeName == taskFields?.storeName
                        }
                    else -> null
                }

                if (resumable != null) {
                    val retireSince = region.pendingDestructive
                        ?.takeIf { it.kind == DestructiveKind.TASK_RETIRE }?.since
                    val displaced = currentTask?.copy(completedAt = retireSince ?: obs.timestamp)
                    val justArrived = taskSubFlow == TaskSubFlow.ARRIVED && resumable.arrivedAt == null
                    return region.copy(
                        activeTask = resumable.copy(
                            subPhase = taskSubFlow,
                            completedAt = null,
                            storeName = taskFields?.storeName ?: resumable.storeName,
                            storeAddress = taskFields?.storeAddress ?: resumable.storeAddress,
                            customerNameHash = taskFields?.customerNameHash ?: resumable.customerNameHash,
                            customerAddressHash = taskFields?.customerAddressHash ?: resumable.customerAddressHash,
                            deadlineMillis = taskFields?.deadline?.time ?: resumable.deadlineMillis,
                            activity = taskFields?.activity ?: resumable.activity,
                            itemsRemaining = taskFields?.itemsRemaining ?: resumable.itemsRemaining,
                            itemsShopped = taskFields?.itemsShopped ?: resumable.itemsShopped,
                            redCardTotal = taskFields?.redCardTotal ?: resumable.redCardTotal,
                            arrivedAt = if (justArrived) obs.timestamp else resumable.arrivedAt,
                        ),
                        recentTasks = (region.recentTasks.filterNot { it.taskId == resumable.taskId } +
                            listOfNotNull(displaced)).takeLast(MAX_RECENT_TASKS),
                        pendingDestructive = null,
                    )
                }

                // #503 slice 3: resolve onto a pre-created (offer-spawned, customer-TBD) dropoff
                // subtask of this job instead of minting a fresh dropoff — the dropoff screen
                // RESOLVES the customer onto the subtask the offer created at accept. Fixes the
                // premature "Customer" card / phantom drops (a dropoff that exists before its
                // customer is known).
                val expectedDropoff = if (taskPhase == TaskPhase.DROPOFF) {
                    val displacedIds = region.recentTasks.mapTo(HashSet()) { it.taskId }
                    region.activeJob?.tasks?.firstOrNull { pending ->
                        pending.phase == TaskPhase.DROPOFF &&
                            pending.customerNameHash == null &&
                            pending.completedAt == null &&
                            pending.taskId != currentTask?.taskId &&
                            pending.taskId !in displacedIds
                    }
                } else null

                if (expectedDropoff != null) {
                    val retireSince = region.pendingDestructive
                        ?.takeIf { it.kind == DestructiveKind.TASK_RETIRE }?.since
                    val displaced = currentTask?.copy(completedAt = retireSince ?: obs.timestamp)
                    val justArrived = taskSubFlow == TaskSubFlow.ARRIVED && expectedDropoff.arrivedAt == null
                    return region.copy(
                        activeTask = expectedDropoff.copy(
                            subPhase = taskSubFlow,
                            storeName = taskFields?.storeName ?: expectedDropoff.storeName,
                            storeAddress = taskFields?.storeAddress ?: expectedDropoff.storeAddress,
                            customerNameHash = taskFields?.customerNameHash,
                            customerAddressHash = taskFields?.customerAddressHash,
                            deadlineMillis = taskFields?.deadline?.time ?: expectedDropoff.deadlineMillis,
                            activity = taskFields?.activity,
                            itemsRemaining = taskFields?.itemsRemaining,
                            itemsShopped = taskFields?.itemsShopped,
                            redCardTotal = taskFields?.redCardTotal,
                            startedAt = obs.timestamp,
                            arrivedAt = if (justArrived) obs.timestamp else expectedDropoff.arrivedAt,
                        ),
                        recentTasks = (region.recentTasks + listOfNotNull(displaced)).takeLast(MAX_RECENT_TASKS),
                        pendingDestructive = null,
                    )
                }

                // #498 phantom-dropoff guard: a transition INTO a dropoff that carries no
                // customer identity at all (no name hash AND no address hash) is a transient
                // confirmation/geofence/arriving screen — dropoff_completed_confirm,
                // dropoff_geofence_warning, nav_arriving — whose flow is task:dropoff:* but
                // which parses no customer. It is NOT a distinct delivery. Minting a fresh
                // dropoff here produced the "the customer" phantom: an identity-less dropoff
                // that immediately completed (06-17 captures: task-9 on a single H-E-B order,
                // task-13, and task-38 on the Jim's stack — the only cn==null && ca==null
                // dropoffs in the whole session; every real dropoff carried a customer hash).
                // Keep the current task; the customer-bearing dropoff_navigation/pre_arrival
                // frame that follows transitions properly (it just resolves identity first).
                // Resume and resolve-onto-placeholder above are unaffected — both reuse an
                // existing task; this only suppresses the fall-through NEW mint.
                if (taskPhase == TaskPhase.DROPOFF &&
                    taskFields?.customerNameHash == null &&
                    taskFields?.customerAddressHash == null
                ) {
                    return region
                }

                // New task (different phase, no active task, or stacked-pickup transition).
                // The displaced task commits inline; if a TASK_RETIRE grace was
                // pending (receipt or idle already signalled its end, #431 pt 2)
                // the honest completion time is that signal's appearance, not
                // this new task's first frame.
                val retireSince = region.pendingDestructive
                    ?.takeIf { it.kind == DestructiveKind.TASK_RETIRE }?.since
                val completedTask = currentTask?.copy(completedAt = retireSince ?: obs.timestamp)
                val recentTasks = if (completedTask != null) {
                    (region.recentTasks + completedTask).takeLast(MAX_RECENT_TASKS)
                } else region.recentTasks

                return region.copy(
                    activeTask = Task(
                        taskId = mintId("task", region, obs),
                        jobId = jobId,
                        phase = taskPhase,
                        subPhase = taskSubFlow,
                        // #526: a newly-minted task takes its store from its OWN frame; only inherit
                        // the displaced task's store on a SAME-phase mint (e.g. a stacked-pickup
                        // parser flicker). A cross-phase mint (pickup→dropoff) must NOT inherit — the
                        // dropoff screen carries its own store (`Target (02426)`), and inheriting the
                        // pickup's store mislabelled a multi-store stack's drop (06-19 Target+Maple).
                        // A dropoff with no store frame yet stays null and fills in on its own later
                        // dropoff_pre_arrival frame via the same-phase update path below.
                        storeName = taskFields?.storeName
                            ?: currentTask?.storeName?.takeIf { currentTask.phase == taskPhase },
                        storeAddress = taskFields?.storeAddress
                            ?: currentTask?.storeAddress?.takeIf { currentTask.phase == taskPhase },
                        customerNameHash = taskFields?.customerNameHash,
                        customerAddressHash = taskFields?.customerAddressHash,
                        deadlineMillis = taskFields?.deadline?.time,
                        activity = taskFields?.activity,
                        itemsRemaining = taskFields?.itemsRemaining,
                        itemsShopped = taskFields?.itemsShopped,
                        redCardTotal = taskFields?.redCardTotal,
                        startedAt = obs.timestamp,
                        arrivedAt = if (taskSubFlow == TaskSubFlow.ARRIVED) obs.timestamp else null,
                    ),
                    recentTasks = recentTasks,
                    pendingDestructive = null,
                    mintCounter = region.mintCounter + 1,
                )
            }

            // Same phase — update fields
            val justArrived = taskSubFlow == TaskSubFlow.ARRIVED && currentTask.arrivedAt == null
            return region.copy(
                activeTask = currentTask.copy(
                    subPhase = taskSubFlow,
                    storeName = taskFields?.storeName ?: currentTask.storeName,
                    storeAddress = taskFields?.storeAddress ?: currentTask.storeAddress,
                    customerNameHash = taskFields?.customerNameHash ?: currentTask.customerNameHash,
                    customerAddressHash = taskFields?.customerAddressHash ?: currentTask.customerAddressHash,
                    deadlineMillis = taskFields?.deadline?.time ?: currentTask.deadlineMillis,
                    activity = taskFields?.activity ?: currentTask.activity,
                    itemsRemaining = taskFields?.itemsRemaining ?: currentTask.itemsRemaining,
                    itemsShopped = taskFields?.itemsShopped ?: currentTask.itemsShopped,
                    redCardTotal = taskFields?.redCardTotal ?: currentTask.redCardTotal,
                    arrivedAt = if (justArrived) obs.timestamp else currentTask.arrivedAt,
                ),
                pendingDestructive = null,
            )
        }

        // PostTask → the receipt is authoritative for "this task is done", but
        // it no longer completes the task on the spot (#431 pt 2): one
        // misrecognized receipt frame mid-dropoff used to retire the live task
        // irrecoverably. Arm (or tighten) a SHORT authoritative TASK_RETIRE
        // grace instead — a contradicting task-flow frame inside the window
        // cancels it (misrecognition flap), expiry commits via the GRACE_COMMIT
        // timer / lazy expiry, and a stacked next-task frame commits it inline
        // at mint (above). Replacing a pending SESSION_END here preserves the
        // pre-#431 contract that a receipt-with-active-task reads as "still
        // dashing" (single-slot pending — noted on the issue).
        val postTask = region.activeTask
        if (nextFlowVal == Flow.PostTask && postTask != null) {
            val newDeadline = obs.timestamp + policy.authoritativeGraceMs
            val existing = region.pendingDestructive
            val pend = if (existing?.kind == DestructiveKind.TASK_RETIRE) {
                // Already armed (idle-grace, or an earlier receipt frame) —
                // tighten to the short window; the earliest destructive signal
                // stays the honest completion time. The receipt is an authoritative
                // completion, so re-anchor the provenance to PostTask (#596): a drop
                // finished at a real receipt is closeable even if an earlier
                // offer-deliberation had armed the original retire.
                existing.copy(
                    deadline = minOf(existing.deadline, newDeadline),
                    authoritative = true,
                    armedFromFlow = Flow.PostTask,
                )
            } else {
                PendingDestructive(
                    kind = DestructiveKind.TASK_RETIRE,
                    since = obs.timestamp,
                    deadline = newDeadline,
                    authoritative = true,
                    armedFromFlow = Flow.PostTask,
                )
            }
            return region.copy(pendingDestructive = pend)
        }

        // Leaving a task flow to idle/offer while online → do NOT retire the task
        // immediately. A transient idle (an informational screen, or the idle map
        // flashing before the delivery summary) must not forget the active task.
        // Arm a grace deadline; returning to a task flow cancels it (above), and a
        // sustained idle past the window retires the task lazily in step().
        if (prevFlowVal.isTaskFlow() && !nextFlowVal.isTaskFlow() && nextFlowVal != Flow.PostTask) {
            if (region.activeTask != null && region.pendingDestructive == null) {
                return region.copy(
                    pendingDestructive = PendingDestructive(
                        kind = DestructiveKind.TASK_RETIRE,
                        since = obs.timestamp,
                        // Through the injected policy (#406): the static constant ignored overrides.
                        deadline = obs.timestamp + policy.gracePeriodMs,
                        // #596: record where the task was left FOR. A retire armed by the dasher
                        // deliberating on a mid-route add-on offer (`OfferPresented`) must NOT let
                        // T1/T2 close-out fire on the still-undelivered final drop; an idle/waiting
                        // arm (`Idle`) is a genuine receipt-skip and IS closeable.
                        armedFromFlow = nextFlowVal,
                    ),
                )
            }
        }

        return region
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    /** Commit a pending destructive transition — its deadline lapsed, or an
     *  authoritative signal confirmed it. */
    private fun commitDestructive(
        region: PlatformRegion,
        kind: DestructiveKind,
        timestamp: Long,
    ): PlatformRegion = when (kind) {
        DestructiveKind.SESSION_END -> endSession(region, timestamp)
        DestructiveKind.TASK_RETIRE -> retireActiveTask(region, timestamp)
    }

    /**
     * Retire the active task when its retire-grace deadline lazily expires
     * (a sustained idle/offer mid-task). Completes it into recentTasks. Unlike
     * [endSession], the session and job live on — only the task is closed.
     *
     * #596 T1: a retire that leaves the job *physically complete* (every dropoff
     * delivered, nothing outstanding) also closes the job — DoorDash routinely
     * skips the post-delivery receipt (the pre-#596 machine's only job-exit), so
     * without this the job never closes and later independent offers get absorbed
     * into it. Gated on provenance: a retire armed by deliberating on a mid-route
     * add-on offer ([Flow.OfferPresented]) does NOT close (that drop isn't
     * delivered — the accept is an add-on).
     */
    private fun retireActiveTask(region: PlatformRegion, timestamp: Long): PlatformRegion {
        val armedFromFlow = region.pendingDestructive
            ?.takeIf { it.kind == DestructiveKind.TASK_RETIRE }?.armedFromFlow
        val completed = region.activeTask?.copy(completedAt = timestamp)
            ?: return region.copy(pendingDestructive = null)
        val recentTasks = (region.recentTasks + completed).takeLast(MAX_RECENT_TASKS)
        val retired = region.copy(
            activeTask = null,
            recentTasks = recentTasks,
            pendingDestructive = null,
        )
        val job = retired.activeJob
        return if (job != null && armedFromFlow != Flow.OfferPresented &&
            isJobPhysicallyComplete(job, recentTasks, justRetired = completed)
        ) {
            completeActiveJob(retired)
        } else {
            retired
        }
    }

    private fun endSession(region: PlatformRegion, timestamp: Long): PlatformRegion {
        val completedTask = region.activeTask?.copy(completedAt = timestamp)
        val recentTasks = if (completedTask != null) {
            (region.recentTasks + completedTask).takeLast(MAX_RECENT_TASKS)
        } else region.recentTasks

        return region.copy(
            session = null,
            activeJob = null,
            activeTask = null,
            recentTasks = recentTasks,
            pendingDestructive = null,
            idleEnteredAt = null,
            lastPostTaskPayHash = null,
            lastPostTaskFields = null,
            // #526 D1: the session's over — a stashed accept must not leak into the next dash.
            lastAcceptedOffer = null,
        )
    }

    private fun completeActiveJob(region: PlatformRegion): PlatformRegion {
        val job = region.activeJob ?: return region
        return region.copy(
            activeJob = null,
            lastPostTaskPayHash = null,
            lastPostTaskFields = null,
        )
    }
}

/**
 * #596: is [job] *physically complete* — every dropoff delivered, nothing outstanding — so it may
 * close on its next exit signal even when DoorDash skips the post-delivery receipt (the only
 * job-exit the pre-#596 machine had)?
 *
 * Completion truth is computed from [recentTasks] + [justRetired], **never** [Job.tasks]: the
 * `Job.tasks` mirror re-runs after `stepCore`, so at the moment a retire/accept consults it, its
 * copy of the just-finished drop is stale (`completedAt` still null — amdt #6). The mirror is used
 * only for the *placeholder set* (which dropoffs the job owns — reliable, since offer-spawned
 * placeholders persist there until resolved+completed).
 *
 * Complete ⇔ every DROPOFF placeholder the job owns is accounted — its taskId appears in
 * [recentTasks] with `completedAt != null`, or it IS [justRetired] — with no unresolved
 * customer-TBD placeholder outstanding, and at least one dropoff actually finished. A zero-dropoff
 * job never qualifies (a healed pickup-only job, or a job whose drops haven't rendered yet).
 *
 * Accounted additionally requires ARRIVAL evidence (`arrivedAt != null`, #615 review): a
 * grace-stamped `completedAt` alone is not delivery proof — a drop retired EN ROUTE (a transient
 * idle flash → offer → accept inside the grace window, or a mid-route cancel) must never read as
 * delivered, close the job, and mint a false completion. Fail direction is safe: a missed ARRIVED
 * frame keeps the job open (the old, lesser bug — absorption), never fabricates a delivery.
 */
internal fun isJobPhysicallyComplete(
    job: Job,
    recentTasks: List<Task>,
    justRetired: Task?,
): Boolean {
    val completedDropoffIds = recentTasks
        .filter {
            it.jobId == job.jobId && it.phase == TaskPhase.DROPOFF &&
                it.completedAt != null && it.arrivedAt != null
        }
        .mapTo(HashSet()) { it.taskId }
    val retiredDropoffId = justRetired
        ?.takeIf {
            it.jobId == job.jobId && it.phase == TaskPhase.DROPOFF &&
                it.completedAt != null && it.arrivedAt != null
        }
        ?.taskId

    // Every dropoff the job owns must be accounted; any outstanding (incl. a customer-TBD
    // placeholder that never resolved) → not complete, so a later independent offer can't fold in.
    val allDropoffsAccounted = job.tasks
        .filter { it.phase == TaskPhase.DROPOFF }
        .all { it.taskId in completedDropoffIds || it.taskId == retiredDropoffId }
    if (!allDropoffsAccounted) return false

    // …and at least one dropoff actually finished (guards zero-dropoff jobs).
    val finished = completedDropoffIds.size +
        (if (retiredDropoffId != null && retiredDropoffId !in completedDropoffIds) 1 else 0)
    return finished >= 1
}

// =========================================================================
// FLOW EXTENSION HELPERS
// =========================================================================

internal fun Flow.isTaskFlow(): Boolean = this in setOf(
    Flow.TaskPickupNavigation,
    Flow.TaskPickupArrived,
    Flow.TaskDropoffNavigation,
    Flow.TaskDropoffArrived,
)

internal fun Flow.toTaskPhase(): TaskPhase? = when (this) {
    Flow.TaskPickupNavigation, Flow.TaskPickupArrived -> TaskPhase.PICKUP
    Flow.TaskDropoffNavigation, Flow.TaskDropoffArrived -> TaskPhase.DROPOFF
    else -> null
}

internal fun Flow.toTaskSubFlow(): TaskSubFlow? = when (this) {
    Flow.TaskPickupNavigation, Flow.TaskDropoffNavigation -> TaskSubFlow.NAVIGATION
    Flow.TaskPickupArrived, Flow.TaskDropoffArrived -> TaskSubFlow.ARRIVED
    else -> null
}

package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.model.ratings.RatingsSnapshot
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.AcceptedOfferEconomics
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.PendingModeResume
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.state.Platform
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
import timber.log.Timber

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
         * #438 B3 (was #526 D1): how long an accepted-pending-consumption offer stays consumable by
         * the mint. An accept is always followed by the task flow within a couple of minutes; a
         * generous window recovers the F3 teardown race while a stale survivor still can't leak into
         * an unrelated later job.
         */
        const val ACCEPT_GRACE_MS = 120_000L
    }

    /**
     * Deterministic entity-id mint (#344): derived only from replay-stable inputs
     * (platform, obs.timestamp, [PlatformRegion.mintCounter]) so crash-recovery
     * replay reproduces the live run's IDs. UUID.randomUUID broke effect
     * idempotency keys ("start_session:$sessionId") and diverged restored state
     * from already-persisted rows. Every mint MUST bump mintCounter in the same
     * copy() — use [offset] when minting more than one id per observation.
     */
    internal fun mintId(
        kind: String,
        region: PlatformRegion,
        obs: Observation,
        offset: Long = 0,
    ): String = "$kind-${region.platform.wire}-${obs.timestamp}-${region.mintCounter + offset}"


    fun step(
        prev: PlatformRegion,
        prevFlow: FlowRegion,
        nextFlow: FlowRegion,
        obs: Observation,
        policy: TransitionPolicy,
    ): PlatformRegion = stampLastActedFlow(
        // #438 B3: the offer lifecycle runs FIRST, on THIS platform's owned pendingOffers, so an
        // accept-latched offer is marked accepted-pending-consumption before stepCore's task edge
        // consumes it (the old armAcceptStash mirror, run AFTER stepCore, is retired).
        reconcileDropoffStore(reconcileJobTasks(stepCore(stepOffers(prev, obs), prevFlow, nextFlow, obs, policy))),
        obs,
    )

    /**
     * #438 item 5 (D3): record the last **non-null** own-platform flow this region stepped on
     * ([PlatformRegion.lastActedFlow]) so the per-region lifecycle edges diff against the flow THIS
     * platform acted on — not the shared global R0 flow. Stamped HERE in the [step] wrapper (not
     * inside [updateLifecycle]) so the SessionEnded / Offline early-returns can't skip it. A
     * non-FlowObservation (Timeout/UiInput/Loopback) or a flow-less FlowObservation (flow=null
     * clicks/notifications) leaves it unchanged — such a frame is not this platform acting on a
     * screen, and `nextFlow.flow` on it is whatever platform last owned R0.
     */
    private fun stampLastActedFlow(region: PlatformRegion, obs: Observation): PlatformRegion {
        val flow = (obs as? Observation.FlowObservation)?.flow ?: return region
        return if (region.lastActedFlow == flow) region else region.copy(lastActedFlow = flow)
    }


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
        val jobPickups = region.recentTasks
            .filter { it.jobId == active.jobId && it.phase == TaskPhase.PICKUP }

        // #526 D6 rule 1 — customer-hash join (priority): the dropoff carries the order's customer,
        // and so does the pickup (pickup_arrival hashes it, #526 D6a — the SAME stripped/trimmed
        // token, so the sha256s match). When this drop's customerNameHash matches EXACTLY ONE of the
        // job's pickup-lineage tasks, attribute that pickup's canonical store — the reliable fix for a
        // multi-store stack's null-store drop (F2), where the dropoff card parses no store and the
        // token-match fallback below has no candidate text. Exact-hash, replay-stable, PII-free
        // (hashes only). A mixed same-store stack keeps last-seen on the pickup, so an unjoined drop
        // (0 matches) or an ambiguous one (>1) falls through to rule 2 — never a wrong store.
        val dropHash = active.customerNameHash
        if (dropHash != null) {
            val matched = jobPickups.filter { it.customerNameHash == dropHash && it.storeName != null }
            if (matched.size == 1) {
                val resolved = matched.single().storeName!!
                return if (resolved == active.storeName) region
                else region.copy(activeTask = active.copy(storeName = resolved))
            }
            // #526 FIX5 (D6 observability): a multi-store stack whose drop carries a customer hash but
            // joins to ZERO pickup-lineage hashes is the signature of cross-surface hash-format drift
            // — the pickup hashes the raw customer_name ([trim,sha256]); the dropoff hashes
            // stripPrefixes'd text, and byte-identical rendering is an untested contract. Surface it
            // (only while the drop is still unresolved, to bound the volume). PII-safe: counts + a
            // 6-char hash prefix only, no store/customer text (Principle 7).
            if (matched.isEmpty() && active.storeName == null) {
                val distinctPickupStores = jobPickups.mapNotNull { it.storeName }
                    .distinctBy { it.lowercase(Locale.ROOT) }.size
                if (distinctPickupStores >= 2) {
                    Timber.tag("StateMachine").w(
                        "D6 join miss (#526): drop hash %s matched 0 of %d pickup-lineage customer " +
                            "hashes across %d stores — possible pickup/dropoff hash-format drift",
                        dropHash.take(6),
                        jobPickups.count { it.customerNameHash != null },
                        distinctPickupStores,
                    )
                }
            }
        }

        // Rule 2 (existing): store from the job's pickup lineage by name.
        val pickupStores = jobPickups
            .filter { it.storeName != null }
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
        //
        // #526 FIX9 (sticky lineage): retain EVERY task already on job.tasks that isn't in the
        // current lineage — not just still-open placeholders. On a huge session a displaced,
        // COMPLETED pickup evicted from recentTasks past MAX_RECENT_TASKS would otherwise vanish from
        // job.tasks mid-job, breaking the confirm sweep + resume. The lineage copy wins when present
        // (freshest); an evicted task keeps its last-known job.tasks copy. Replay-deterministic (pure
        // derivation from persisted state), and on a small session this equals the old open-only set
        // because every completed task is still in the lineage.
        val sticky = job.tasks.filter { it.taskId !in lineageIds }
        val jobTasks = sticky + lineage
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
        // #438 item 5 (D3): the lifecycle edges below diff THIS region's own acted flow, not the
        // shared global R0 flow. `region.lastActedFlow` is still the pre-step value here (the stamp
        // runs in the [step] wrapper, after stepCore). Fallback to the global prev flow for legacy
        // snapshots (lastActedFlow=null) keeps single-platform behavior byte-identical — the sole
        // region acts on every own frame, so its lastActedFlow tracks R0.flow. A flow-less own obs
        // (flow=null) is not a flow edge (next=prev), never a diff against the other platform's
        // nextFlow.flow.
        val prev = region.lastActedFlow ?: prevFlow.flow
        val next = obs.flow ?: prev

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
        r = updateJobLifecycle(r, prev, next, obs)

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
        obs: Observation.FlowObservation,
    ): PlatformRegion {
        // #438 B3: consume THIS region's own accepted-pending-consumption offer on a task flow — the
        // SINGLE mint source (richer than the old accept stash: the owned offer carries full fields +
        // evaluation). The offer was marked accepted-pending-consumption by [stepOffers] when the own
        // flow left offer-presentation with the accept latch set. This one path covers:
        //   - the happy path (offer→task in one step: the survivor is marked AND consumed same-frame);
        //   - the F3 teardown race (the survivor was marked on a prior leave-edge — a
        //     `waiting_for_offer` frame that popped presentation — and is consumed when the task flow
        //     finally arrives).
        // consumeAcceptIntoJob mints fresh / appends an add-on / #596-T2 closes+mints. The trigger is
        // structurally per-region now (the offer lives on this region), so the interim
        // offerBelongsToRegion cross-region guard is gone.
        var current = region
        val accepted = current.pendingOffers.lastOrNull { it.acceptedAt != null }
        if (nextFlowVal.isTaskFlow() && accepted != null) {
            if (!isOfferAcceptExpired(accepted, obs)) {
                val consumed = current.copy(pendingOffers = current.pendingOffers.filterNot { it === accepted })
                return consumeAcceptIntoJob(consumed, obs, acceptInputsFromPending(accepted, accepted.acceptedAt))
            }
            // A lapsed survivor is a corpse — clear it INLINE but do NOT early-return; the frame must
            // still process normally (a lost arrivedAt stamp would trip the #615 arrival gate and the
            // job would never close). Fall through with the corpse cleared.
            current = current.copy(pendingOffers = current.pendingOffers.filterNot { it === accepted })
        }

        // Job start: task flow without active job (recovery, mid-session restart, or an accept that
        // was never latched — genuine bare fallback with no economics).
        if (nextFlowVal.isTaskFlow() && current.activeJob == null) {
            val parsed = obs.parsed as? ParsedFields.TaskFields
            return current.copy(
                activeJob = Job(
                    jobId = mintId("job", current, obs),
                    offerStoreHint = listOfNotNull(parsed?.storeName),
                    parentOfferHash = null,
                    startedAt = obs.timestamp,
                ),
                mintCounter = current.mintCounter + 1,
            )
        }

        // Post-task: keep job alive through PostTask for payout capture
        // Job ends when we leave PostTask for non-task flow
        if (prevFlowVal == Flow.PostTask && !nextFlowVal.isTaskFlow() && nextFlowVal != Flow.PostTask && nextFlowVal != Flow.OfferPresented) {
            return completeActiveJob(current)
        }

        return current
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

                // #526 D3: resolve a pickup screen onto a pre-created (offer-spawned) PICKUP
                // placeholder of this job instead of minting — the symmetric counterpart to the
                // dropoff resolution below. Store hints are unreliable, so the priority is:
                //   1. exact case-insensitive hint match,
                //   2. D3a: StoreNameMatch.bestMatch (>=1 shared leading token — the same SSOT
                //      reconcileDropoffStore uses), so a noisy/null store doesn't blind-bind in
                //      offset order,
                //   3. next open placeholder in offset order.
                // "Open" = storeName not yet resolved, not completed, not the current/displaced task.
                // A wrong bind can be repaired later without re-minting via the swap guard (D4a below).
                val expectedPickup = if (taskPhase == TaskPhase.PICKUP) {
                    val displacedIds = region.recentTasks.mapTo(HashSet()) { it.taskId }
                    val openPickups = region.activeJob?.tasks.orEmpty().filter { p ->
                        p.phase == TaskPhase.PICKUP &&
                            p.storeName == null &&
                            p.completedAt == null &&
                            p.taskId != currentTask?.taskId &&
                            p.taskId !in displacedIds
                    }
                    val screenStore = taskFields?.storeName?.trim()?.takeIf { it.isNotEmpty() }
                    val hintMatch = screenStore?.let { s ->
                        openPickups.firstOrNull { it.expectedStoreHint?.equals(s, ignoreCase = true) == true }
                    }
                    val bestMatch = if (hintMatch == null && screenStore != null) {
                        val best = StoreNameMatch.bestMatch(openPickups.mapNotNull { it.expectedStoreHint }, screenStore)
                        best?.let { b -> openPickups.firstOrNull { it.expectedStoreHint == b } }
                    } else null
                    // #526 FIX3b: tier 3 (next-open) binds ONLY when there is exactly ONE open
                    // placeholder. With ≥2 open and no parsed/matched store, a blind first-open bind
                    // guesses the wrong order — fall through to a fresh mint (master behavior); a
                    // later frame carrying a real store can hint-match the right placeholder.
                    hintMatch ?: bestMatch ?: openPickups.singleOrNull()
                } else null

                if (expectedPickup != null) {
                    val retireSince = region.pendingDestructive
                        ?.takeIf { it.kind == DestructiveKind.TASK_RETIRE }?.since
                    val displaced = currentTask?.copy(completedAt = retireSince ?: obs.timestamp)
                    val justArrived = taskSubFlow == TaskSubFlow.ARRIVED && expectedPickup.arrivedAt == null
                    return region.copy(
                        activeTask = expectedPickup.copy(
                            subPhase = taskSubFlow,
                            storeName = taskFields?.storeName ?: expectedPickup.storeName,
                            storeAddress = taskFields?.storeAddress ?: expectedPickup.storeAddress,
                            customerNameHash = taskFields?.customerNameHash ?: expectedPickup.customerNameHash,
                            customerAddressHash = taskFields?.customerAddressHash ?: expectedPickup.customerAddressHash,
                            deadlineMillis = taskFields?.deadline?.time ?: expectedPickup.deadlineMillis,
                            activity = taskFields?.activity,
                            itemsRemaining = taskFields?.itemsRemaining,
                            itemsShopped = taskFields?.itemsShopped,
                            redCardTotal = taskFields?.redCardTotal,
                            startedAt = obs.timestamp,
                            arrivedAt = if (justArrived) obs.timestamp else expectedPickup.arrivedAt,
                        ),
                        recentTasks = (region.recentTasks + listOfNotNull(displaced)).takeLast(MAX_RECENT_TASKS),
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

            // Same phase — update fields.
            //
            // #526 D4a: swap-on-divergence guard. Before accumulating this frame, check whether the
            // active PICKUP's parsed store demonstrably belongs to a DIFFERENT open pickup placeholder
            // — its store bestMatches exactly ONE other open slot's hint (>=1 shared leading token)
            // while sharing 0 leading tokens with the active task's own hint. That means the
            // accumulation is sitting on the wrong order slot: move it onto the correctly-hinted slot
            // without re-minting (the swap primitive keeps both taskIds stable) and continue on the
            // now-correct slot. Conservative — a unique match with a clean 0-token divergence only;
            // ambiguous → no swap. Both tasks are OPEN (the active task by definition; the guard
            // requires the other slot open too — the D4 lifecycle contract).
            val (baseJob, baseTask) = maybeSwapMisboundPickup(region, currentTask, taskPhase, taskFields)
            val justArrived = taskSubFlow == TaskSubFlow.ARRIVED && baseTask.arrivedAt == null
            return region.copy(
                activeJob = baseJob,
                activeTask = baseTask.copy(
                    subPhase = taskSubFlow,
                    storeName = taskFields?.storeName ?: baseTask.storeName,
                    storeAddress = taskFields?.storeAddress ?: baseTask.storeAddress,
                    customerNameHash = taskFields?.customerNameHash ?: baseTask.customerNameHash,
                    customerAddressHash = taskFields?.customerAddressHash ?: baseTask.customerAddressHash,
                    deadlineMillis = taskFields?.deadline?.time ?: baseTask.deadlineMillis,
                    activity = taskFields?.activity ?: baseTask.activity,
                    itemsRemaining = taskFields?.itemsRemaining ?: baseTask.itemsRemaining,
                    itemsShopped = taskFields?.itemsShopped ?: baseTask.itemsShopped,
                    redCardTotal = taskFields?.redCardTotal ?: baseTask.redCardTotal,
                    arrivedAt = if (justArrived) obs.timestamp else baseTask.arrivedAt,
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
            // #438 B3: the session's over — pending/accepted offers must not leak into the next dash.
            pendingOffers = emptyList(),
        )
    }

    internal fun completeActiveJob(region: PlatformRegion): PlatformRegion {
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

package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.core.state.model.DecisionSink
import cloud.trotter.log.gig.core.state.model.OfferOutcomeDecision
import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.DeclineEvidence
import cloud.trotter.log.gig.domain.model.offer.OfferScope
import cloud.trotter.log.gig.domain.model.offer.newStores
import cloud.trotter.log.gig.domain.model.offer.ownedStoreKeys
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.HeldWorkFrame
import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.OfferIntent
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PendingOffer
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.PoolEntryVia
import cloud.trotter.log.gig.domain.state.PooledOffer
import cloud.trotter.log.gig.domain.state.StoreKeys
import cloud.trotter.log.gig.domain.state.UNKNOWN_STORE
import timber.log.Timber

/**
 * #1119 S2 — the offer pool. A Compose offer card emits no click for a human tap, so a card that leaves without a
 * committed decline or an observed accept click waits here for the whole shift. The first frame of new work that
 * names a store links the one pooled offer at that store; a tie links nothing and is saved for the dasher; what is
 * left at the shift end records `OFFER_NOT_TAKEN`. Every offer instance gets exactly one outcome, an offer links only
 * to work at its own store, and nothing links on a guess (no recency, no scope preference).
 *
 * Council round 6 adds two rules: a confirm-sheet sighting just before the leave DEMOTES the entry (kept, never a
 * candidate, a tie guard for 60 s, `OFFER_DECLINED` at the shift end) unless a card frame came back after it, and a
 * store-less first work frame is HELD for the frame that names the store instead of minting a bare job.
 *
 * `internal` extensions on [PlatformRegionStepper] (the [JobAcceptFlow] precedent). Outcomes are recorded as
 * [OfferOutcomeDecision]s; `OfferEffects` only emits them.
 */

private const val TAG = "StateMachine"

/**
 * The ONE place a presented offer leaves presentation. [region] still holds [offer] in `pendingOffers`; the result
 * does not, except an accept-latched offer leaving by a frame, which stays as today's accepted survivor.
 */
internal fun PlatformRegionStepper.leavePresentation(
    sink: DecisionSink,
    region: PlatformRegion,
    offer: PendingOffer,
    obs: Observation,
    via: PoolEntryVia,
    policy: TransitionPolicy,
): PlatformRegion {
    val rest = region.pendingOffers.filterNot { it === offer }
    val replaced = via == PoolEntryVia.REPLACED || via == PoolEntryVia.SUPERSEDED_BY_DIRECT
    val replaceNote = when (via) {
        PoolEntryVia.REPLACED -> "Replaced by new offer"
        PoolEntryVia.SUPERSEDED_BY_DIRECT -> "Superseded by direct offer"
        else -> null
    }
    fun decide(outcome: AppEventType, description: String?) = sink.offerOutcome(
        OfferOutcomeDecision(
            instanceKey = offer.instanceKey,
            offer = offer,
            outcome = outcome,
            decidedAt = obs.timestamp,
            occurredAt = obs.timestamp,
            sessionId = region.session?.sessionId,
            description = description,
            announce = true,
            replaced = replaced,
            supersededByDirect = via == PoolEntryVia.SUPERSEDED_BY_DIRECT,
        ),
    )
    return when {
        offer.declineCommittedAt != null -> {
            decide(
                AppEventType.OFFER_DECLINED,
                if (offer.lastClickIntent == OfferIntent.ACCEPT) {
                    "Accept clicked after decline was already committed — decline stands (#594)"
                } else replaceNote,
            )
            region.copy(pendingOffers = rest)
        }
        offer.isAcceptLatched() -> {
            decide(AppEventType.OFFER_ACCEPTED, replaceNote)
            if (via == PoolEntryVia.FRAME) {
                // The accepted survivor (#438 B3): consumed store-blind by the next task frame inside the accept grace.
                region.copy(
                    pendingOffers = region.pendingOffers.map {
                        if (it === offer) offer.copy(acceptedAt = offer.acceptClickAt ?: obs.timestamp) else it
                    },
                    lastAcceptResolvedAt = obs.timestamp,
                )
            } else {
                // A new presentation supersedes or revokes a stale accept (#526 FIX2b).
                region.copy(pendingOffers = rest)
            }
        }
        else -> {
            val sheet = sheetSightingCounts(offer, obs.timestamp, via, region, policy)
            // The card coming back after the last sighting is the dasher cancelling the sheet.
            val cardBack = sheet && (offer.lastCardFrameAt ?: Long.MIN_VALUE) > (offer.declineSheetSeenAt ?: Long.MAX_VALUE)
            val entry = PooledOffer(
                offer = offer.copy(targets = emptyMap()),
                leftAt = obs.timestamp,
                via = via,
                sessionId = region.session?.sessionId,
                candidateStoreKeys = candidateStoreKeys(offer, region.activeJob),
                demoted = sheet && !cardBack,
            )
            val pool = region.offerPool + entry
            Timber.tag(TAG).d(
                "#1119 offer %s pooled via %s (pool=%d)%s",
                offer.offerHash.take(8), via, pool.size,
                when {
                    entry.demoted -> " demoted by the decline sheet"
                    cardBack -> " card back after the decline sheet"
                    else -> ""
                },
            )
            region.copy(pendingOffers = rest, offerPool = pool)
        }
    }
}

/**
 * Council round 6: the confirm-decline sheet COUNTS against a leaving offer when its last sighting is at most
 * `declineSheetWindowMs` before the leave, the countdown was parsed and more than `countdownExpirySlackMs` remained,
 * and the leave is not the expiry timer. It DEMOTES the offer unless a card frame of this presentation arrived after
 * that sighting ([PendingOffer.lastCardFrameAt]: the card coming back is the dasher cancelling the sheet).
 */
private fun sheetSightingCounts(
    offer: PendingOffer,
    leftAt: Long,
    via: PoolEntryVia,
    region: PlatformRegion,
    policy: TransitionPolicy,
): Boolean {
    if (via == PoolEntryVia.EXPIRY_TIMER) return false
    val seen = offer.declineSheetSeenAt ?: return false
    val expiresAt = offer.countdownExpiresAt ?: return false
    if (leftAt - seen !in 0..policy.declineSheetWindowMs(region.platform)) return false
    return leftAt < expiresAt - policy.countdownExpirySlackMs(region.platform)
}

/**
 * The chain keys an offer can be matched on: every order's store for a STANDALONE card; for an ADD_ON card presented
 * over a live job only the stores new to that job, all of them when no job was live. Blank and unknown stores never
 * match.
 */
internal fun candidateStoreKeys(offer: PendingOffer, job: Job?): Set<String> {
    val parsed = offer.offerFields.parsedOffer
    val names = if (parsed.effectiveScope == OfferScope.ADD_ON && job != null) {
        newStores(ownedStoreKeys(job.tasks), parsed)
    } else {
        parsed.orders.map { it.storeName.trim() }
    }
    return names.filter { it.isNotBlank() && !it.equals(UNKNOWN_STORE, ignoreCase = true) }
        .map(StoreKeys::normalizedChain).toSet()
}

/** The store a work frame names, trimmed; null when it names none. */
private fun storeOf(obs: Observation.FlowObservation): String? =
    (obs.parsed as? ParsedFields.TaskFields)?.storeName?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals(UNKNOWN_STORE, ignoreCase = true) }

/** No ordering, no recency, no scope preference: every live (non-demoted) entry at the store. */
private fun match(pool: List<PooledOffer>, storeKey: String): List<PooledOffer> =
    pool.filter { !it.demoted && storeKey in it.candidateStoreKeys }

/**
 * The job lifecycle's pool edge, run by `updateJobLifecycle` after the accept-click survivor. Null means "not
 * applicable, carry on"; non-null decides the frame's job lifecycle (`updateTaskLifecycle` still runs after it).
 *
 * With a [HeldWorkFrame] standing, a non-task frame, a frame past the hold's time cap or the store-less frame that
 * reaches its frame cap releases it into today's bare mint, replayed from the held frame; the first store-bearing
 * task frame inside the hold runs the work edge from the held frame. With no hold, a store-less task frame with no
 * job and a live pool starts one.
 */
internal fun PlatformRegionStepper.poolWorkEdge(
    sink: DecisionSink,
    region: PlatformRegion,
    prevFlow: Flow,
    nextFlow: Flow,
    obs: Observation.FlowObservation,
    policy: TransitionPolicy,
): PlatformRegion? {
    val held = region.heldWorkFrame
    val store = storeOf(obs)
    if (held != null) {
        if (!nextFlow.isTaskFlow()) return releaseHeldBare(sink, region, held, policy, "non-task frame")
        if (obs.timestamp - held.at > policy.storelessHoldMs(region.platform)) {
            return releaseHeldBare(sink, region, held, policy, "time cap")
        }
        if (store == null) {
            val frames = held.frames + 1
            if (frames >= policy.storelessHoldFrames(region.platform)) {
                return releaseHeldBare(sink, region, held, policy, "frame cap")
            }
            return region.copy(heldWorkFrame = held.copy(frames = frames))
        }
        return linkAtWorkEdge(sink, region.copy(heldWorkFrame = null), obs, store, policy, held)
            ?: releaseHeldBare(sink, region, held, policy, "no candidate at the store")
    }
    if (!nextFlow.isTaskFlow() || region.offerPool.isEmpty()) return null
    if (store == null) {
        if (region.activeJob != null) return null
        Timber.tag(TAG).d("#1119 store-less work frame, %d pooled offer(s) wait", region.offerPool.size)
        val holds = region.offerPool.any { !it.demoted } && policy.storelessHoldFrames(region.platform) > 1
        if (!holds) return null
        Timber.tag(TAG).d("#1119 store-less work frame held for the store")
        return region.copy(
            heldWorkFrame = HeldWorkFrame(
                at = obs.timestamp,
                flow = nextFlow,
                prevFlow = prevFlow,
                ruleId = obs.ruleId,
                parsed = obs.parsed,
                deadline = obs.timestamp + policy.storelessHoldMs(region.platform),
            ),
        )
    }
    return linkAtWorkEdge(sink, region, obs, store, policy, held = null)
}

/**
 * The work edge at a store-bearing task frame (§ C4). [held] is the store-less frame this one releases, whose
 * timestamp is the edge's instant. Null unless an edge applies:
 * - **fresh work** (no live job, or the live job is finished, #596 T2): one candidate links; two or more (or one
 *   beside a same-store demoted entry that left within the tie guard) tie on a bare job and link nothing;
 * - **a stale job** (OQ2): a sole STANDALONE candidate presented over a non-task flow after the job started, at a
 *   store new to the job, links through the slice-1 stale close;
 * - anything else: null (today's absorption).
 */
private fun PlatformRegionStepper.linkAtWorkEdge(
    sink: DecisionSink,
    region: PlatformRegion,
    obs: Observation.FlowObservation,
    store: String,
    policy: TransitionPolicy,
    held: HeldWorkFrame?,
): PlatformRegion? {
    val key = StoreKeys.normalizedChain(store)
    val candidates = match(region.offerPool, key)
    if (candidates.isEmpty()) {
        Timber.tag(TAG).d("#1119 no pooled offer at this store (pool=%d)", region.offerPool.size)
        return null
    }
    val edgeAt = held?.at ?: obs.timestamp
    val guards = region.offerPool.filter {
        it.demoted && key in it.candidateStoreKeys && edgeAt - it.leftAt in 0..policy.demotionTieGuardMs(region.platform)
    }
    val job = region.activeJob
    if (job == null || startsNewJob(region)) {
        return if (candidates.size == 1 && guards.isEmpty()) {
            link(sink, region, obs, candidates.single(), edgeAt, if (held != null) "FRESH_AFTER_HOLD" else "FRESH")
        } else {
            tie(sink, region, obs, if (candidates.size == 1) candidates + guards else candidates, held, policy)
        }
    }
    val sole = candidates.singleOrNull()?.takeIf { guards.isEmpty() && isStaleJobEdge(it, job) } ?: return null
    return link(sink, region, obs, sole, edgeAt, "STALE")
}

/** OQ2: a sole STANDALONE candidate presented over a non-task flow after [job] started, at a store new to the job. */
private fun isStaleJobEdge(entry: PooledOffer, job: Job): Boolean {
    val offer = entry.offer
    if (offer.offerFields.parsedOffer.effectiveScope != OfferScope.STANDALONE) return false
    if (offer.presentedAt < job.startedAt || offer.returnFlow.isTaskFlow()) return false
    // ownedStoreKeys are already chain keys: never key them again.
    val owned = ownedStoreKeys(job.tasks).toSet()
    return entry.candidateStoreKeys.none { it in owned }
}

private fun PlatformRegionStepper.link(
    sink: DecisionSink,
    region: PlatformRegion,
    obs: Observation.FlowObservation,
    entry: PooledOffer,
    edgeAt: Long,
    edge: String,
): PlatformRegion {
    val acceptedAt = minOf(entry.leftAt, edgeAt)
    // Stamped before any T2 close, so the close-time re-price ask refuses an accept resolved over the receipt.
    val pooled = region.copy(offerPool = region.offerPool - entry, lastAcceptResolvedAt = obs.timestamp)
    val next = consumeAcceptIntoJob(sink, pooled, obs, acceptInputsFromPending(entry.offer, acceptedAt))
    val jobId = next.activeJob?.jobId
    sink.offerOutcome(
        OfferOutcomeDecision(
            instanceKey = entry.instanceKey,
            offer = entry.offer,
            outcome = AppEventType.OFFER_ACCEPTED,
            decidedAt = acceptedAt,
            occurredAt = obs.timestamp,
            sessionId = region.session?.sessionId ?: entry.sessionId,
            description = "Linked to work at its store (#1119)",
            linkedJobId = jobId,
            announce = true,
        ),
    )
    Timber.tag(TAG).i(
        "#1119 offer %s linked to job %s by store (left %ds before, pool=%d, edge=%s)",
        entry.offer.offerHash.take(8), jobId, (obs.timestamp - entry.leftAt) / 1000, next.offerPool.size, edge,
    )
    return next
}

private fun PlatformRegionStepper.tie(
    sink: DecisionSink,
    region: PlatformRegion,
    obs: Observation.FlowObservation,
    tied: List<PooledOffer>,
    held: HeldWorkFrame?,
    policy: TransitionPolicy,
): PlatformRegion {
    val closed = if (region.activeJob != null) closeCompletedJob(sink, region, obs) else region
    val minted = if (held != null) releaseHeldBare(sink, closed, held, policy, "tie") else mintBareJob(closed, obs)
    val jobId = minted.activeJob?.jobId ?: return minted
    val keys = tied.mapTo(HashSet()) { it.instanceKey }
    Timber.tag(TAG).w(
        "#1119 %d pooled offers tie at job %s (%s) — none linked (%d demoted)",
        tied.size, jobId, tied.joinToString(",") { it.offer.offerHash.take(8) }, tied.count { it.demoted },
    )
    return minted.copy(
        offerPool = minted.offerPool.map {
            if (it.instanceKey in keys) it.copy(ambiguousJobIds = it.ambiguousJobIds + jobId) else it
        },
    )
}

/**
 * Today's bare mint, replayed from the held frame: the job minted at it and its task created by it, so the frame
 * that ends the hold then steps on exactly the state a bare mint would have left.
 */
private fun PlatformRegionStepper.releaseHeldBare(
    sink: DecisionSink,
    region: PlatformRegion,
    held: HeldWorkFrame,
    policy: TransitionPolicy,
    reason: String,
): PlatformRegion {
    Timber.tag(TAG).d("#1119 store-less hold ends in a bare job (%s, %d frame(s))", reason, held.frames)
    val heldObs = Observation.Screen(
        timestamp = held.at,
        captureId = null,
        ruleId = held.ruleId,
        metadata = ReplayMetadata.EMPTY,
        flow = held.flow,
        modeHint = null,
        parsed = held.parsed,
    )
    val minted = mintBareJob(region.copy(heldWorkFrame = null), heldObs)
    return updateTaskLifecycle(sink, minted, held.prevFlow, held.flow, heldObs, policy)
}

/**
 * After the task lifecycle ran: a link that released a hold starts the job and its task at the held frame (one job,
 * one task, started where the work first appeared). [held] is the hold standing before the step.
 */
internal fun startLinkedWorkAtHeldFrame(held: HeldWorkFrame?, region: PlatformRegion): PlatformRegion {
    if (held == null || region.heldWorkFrame != null) return region
    val job = region.activeJob?.takeIf { it.acceptedOffers.isNotEmpty() && it.startedAt > held.at } ?: return region
    val task = region.activeTask?.takeIf { it.jobId == job.jobId }
    return region.copy(
        activeJob = job.copy(startedAt = held.at),
        activeTask = task?.copy(startedAt = minOf(task.startedAt, held.at)) ?: region.activeTask,
    )
}

/**
 * § C5: the shift end records one outcome for every offer still without one. A presented card: committed decline →
 * `OFFER_DECLINED`, latched accept → `OFFER_ACCEPTED`, else `OFFER_NOT_TAKEN`. A pool entry: `OFFER_NOT_TAKEN`, or
 * `OFFER_DECLINED` marked as inferred when it was demoted. Clears the pool and any hold; `endSession` clears the
 * presented offers.
 */
internal fun flushAtShiftEnd(sink: DecisionSink, region: PlatformRegion, at: Long): PlatformRegion {
    val sessionId = region.session?.sessionId
    val presented = region.pendingOffers.filter { it.acceptedAt == null }
    for (offer in presented) {
        val (outcome, description) = when {
            offer.declineCommittedAt != null -> AppEventType.OFFER_DECLINED to null
            offer.isAcceptLatched() -> AppEventType.OFFER_ACCEPTED to null
            else -> AppEventType.OFFER_NOT_TAKEN to "On screen at shift end (#1119)"
        }
        sink.offerOutcome(
            OfferOutcomeDecision(
                instanceKey = offer.instanceKey, offer = offer, outcome = outcome, decidedAt = at, occurredAt = at,
                sessionId = sessionId, description = description, announce = false,
            ),
        )
    }
    for (entry in region.offerPool) {
        val tied = entry.ambiguousJobIds.takeIf { it.isNotEmpty() }?.let { "; tied on ${it.size} job(s)" }.orEmpty()
        val description = if (entry.demoted) "Decline inferred from the confirm sheet (#1119)" else when (entry.via) {
            PoolEntryVia.FRAME -> "No work at its store by shift end (#1119)"
            PoolEntryVia.REPLACED -> "Replaced by new offer; no work at its store by shift end (#1119)"
            PoolEntryVia.SUPERSEDED_BY_DIRECT -> "Superseded by direct offer"
            PoolEntryVia.EXPIRY_TIMER -> "Expiry timer; no work at its store by shift end (#1119)"
        }
        sink.offerOutcome(
            OfferOutcomeDecision(
                instanceKey = entry.instanceKey,
                offer = entry.offer,
                outcome = if (entry.demoted) AppEventType.OFFER_DECLINED else AppEventType.OFFER_NOT_TAKEN,
                decidedAt = entry.leftAt,
                occurredAt = at,
                sessionId = entry.sessionId ?: sessionId,
                description = description + tied,
                ambiguousJobIds = entry.ambiguousJobIds,
                declineEvidence = if (entry.demoted) DeclineEvidence.SHEET_WINDOW else null,
                announce = false,
            ),
        )
    }
    if (presented.isNotEmpty() || region.offerPool.isNotEmpty()) {
        Timber.tag(TAG).i(
            "#1119 shift end: %d offer(s) not taken, %d tied, %d on screen, %d declined by the sheet",
            region.offerPool.count { !it.demoted }, region.offerPool.count { it.ambiguousJobIds.isNotEmpty() },
            presented.size, region.offerPool.count { it.demoted },
        )
    }
    return region.copy(offerPool = emptyList(), heldWorkFrame = null)
}

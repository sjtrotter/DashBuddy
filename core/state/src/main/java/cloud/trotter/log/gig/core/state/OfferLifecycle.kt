package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.core.state.model.DecisionSink
import cloud.trotter.log.gig.domain.model.offer.OfferKind
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.ObservationPayload
import cloud.trotter.log.gig.domain.pipeline.TimeoutType
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.OfferIntent
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.OfferSurface
import cloud.trotter.log.gig.domain.state.PendingOffer
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.PoolEntryVia
import timber.log.Timber

/**
 * #438 item 7 (B3) — the offer lifecycle on the platform-owned [PlatformRegion.pendingOffers],
 * extracted from the old [FlowRegionStepper] (which had owned offers on the shared global R0 slot).
 * `internal` extensions on [PlatformRegionStepper] (the [JobAcceptFlow] precedent — the stepper is at
 * the #237 size ceiling), driven by THIS platform's own observations only (only the matching region
 * steps, `StateMachine.stepPlatforms`).
 *
 * Runs BEFORE `stepCore` in the [PlatformRegionStepper.step] wrapper, so the accepted-pending-
 * consumption survivor it marks is in the list by the time the task edge (`updateJobLifecycle`)
 * consumes it. Today the list holds at most one presented offer (the pre-B3 single-offer replace
 * semantics per platform); the N>1 correlation contract (#251/vet M5) is defined here so #251's
 * capture only adds ruleset data, not `:core:state` logic.
 *
 * #1119 S2: every offer outcome is decided here (or in `OfferPool.kt`, the work edge and the shift end) and recorded
 * on [sink] as an `OfferOutcomeDecision`; `OfferEffects` only emits it. A presentation leaves through
 * [leavePresentation], the one exit: a committed decline is declined, an accept click is accepted, and anything else
 * waits in the offer pool for work at its store.
 */
internal fun PlatformRegionStepper.stepOffers(
    sink: DecisionSink,
    region: PlatformRegion,
    obs: Observation,
    policy: TransitionPolicy,
): PlatformRegion {
    // Accept-grace lazy expiry: an accepted-pending-consumption survivor older than the grace never
    // got minted (the F3-failure case) — drop the corpse so it can't ride the snapshot. The accept
    // was already logged at the edge, so NO further event fires (WARN only, Principle 7).
    val pruned = pruneExpiredSurvivors(region, obs, policy)

    return when (obs) {
        is Observation.FlowObservation -> when (obs.flow) {
            // A flow-less click carries the accept/decline intent; other flow-less frames are inert.
            null -> if (obs is Observation.Click) latchOfferClick(pruned, obs) else pruned
            Flow.OfferPresented -> pushOrReplaceOffer(sink, pruned, obs, policy)
            // A click that carries its own flow latches first: an observed tap outranks the pool (Astra #1104 r1 F4).
            else -> resolveOnLeave(sink, if (obs is Observation.Click) latchOfferClick(pruned, obs) else pruned, obs, policy)
        }
        is Observation.Loopback -> landOfferEval(pruned, obs)
        is Observation.Timeout ->
            if (obs.type == TimeoutType.OFFER_EXPIRY) expireOffer(sink, pruned, obs, policy) else pruned
        else -> pruned
    }
}

/**
 * True once an accepted-pending-consumption survivor has out-lived the accept grace. The grace is
 * per-platform (#762 D2, `[acceptGraceMs]` from `TransitionPolicy.acceptGraceMs(platform)`) — passed
 * in rather than read from a global const so a coarse platform (Uber) can span a realistic drive.
 */
internal fun PlatformRegionStepper.isOfferAcceptExpired(
    offer: PendingOffer,
    obs: Observation,
    acceptGraceMs: Long,
): Boolean {
    val acceptedAt = offer.acceptedAt ?: return false
    return obs.timestamp - acceptedAt > acceptGraceMs
}

private fun PlatformRegionStepper.pruneExpiredSurvivors(
    region: PlatformRegion,
    obs: Observation,
    policy: TransitionPolicy,
): PlatformRegion {
    val acceptGraceMs = policy.acceptGraceMs(region.platform)
    val expired = region.pendingOffers.filter { it.acceptedAt != null && isOfferAcceptExpired(it, obs, acceptGraceMs) }
    if (expired.isEmpty()) return region
    expired.forEach {
        Timber.tag("StateMachine").w(
            "Accepted offer %s lapsed unconsumed after the accept grace (#438 F3-failure) — no mint",
            it.offerHash,
        )
    }
    return region.copy(pendingOffers = region.pendingOffers - expired.toSet())
}

/**
 * Own `OfferPresented` frame: push a new offer, replace a different-*presentation* one, enrich the
 * same hash, or **enrich-as-variant** a churned re-render of the presentation already on screen
 * (#830). Mirrors the pre-B3 `FlowRegionStepper.stepOffer` scalar logic on the owned list, plus the
 * presentation-scoped identity that stops a live-re-quoting card's replace-storm. A genuine REPLACE
 * (different presentation) clears ALL accepted survivors: a different offer supersedes a stale
 * accept, and the same offer re-presenting revokes it (#526 FIX2b) — the pre-B3 `armAcceptStash`
 * supersession + revocation rules as lifecycle rules. #1119 S2: the replaced presentation leaves through
 * [leavePresentation] (pooled unless declined or accepted); every card frame stamps [PendingOffer.lastCardFrameAt].
 */
private fun PlatformRegionStepper.pushOrReplaceOffer(
    sink: DecisionSink,
    region: PlatformRegion,
    obs: Observation.FlowObservation,
    policy: TransitionPolicy,
): PlatformRegion {
    val offerFields = obs.parsed as? ParsedFields.OfferFields
    val newHash = offerFields?.parsedOffer?.offerHash
    val presented = region.presentedOffer()

    val newOffers = when {
        // #1104/#1114: the platform's confirm-decline sheet rendered over the presented offer. A
        // Compose control emits no click event for a human tap, so this FRAME is the only evidence
        // a decline is under way. Record the LAST sighting on the presented offer. #1119 S2: the
        // sighting decides no outcome. It is kept as evidence for the dasher's review, and at the
        // leave a recent sighting with time on the countdown and no card frame after it DEMOTES the
        // pooled offer (never linked, an inferred decline at the shift end; `OfferPool.kt`).
        // Rule-declared (`state.offerSurface`), P8-clean.
        (obs as? Observation.Screen)?.offerSurface == OfferSurface.DECLINE_CONFIRM -> {
            if (presented == null) return region
            region.pendingOffers.map {
                if (it === presented) presented.copy(declineSheetSeenAt = obs.timestamp) else it
            }
        }

        // No offer data → keep the list as-is.
        offerFields == null || newHash == null -> return region

        // Same hash → update enrichment; refresh action targets (#425) when re-bound (fresher
        // fingerprints aim better).
        presented != null && presented.offerHash == newHash ->
            region.pendingOffers.map {
                if (it === presented) presented.copy(
                    offerFields = offerFields,
                    targets = obs.targets.ifEmpty { presented.targets },
                    sourceRuleId = obs.ruleId ?: presented.sourceRuleId,
                    countdownExpiresAt = countdownExpiresAt(obs, offerFields) ?: presented.countdownExpiresAt,
                    lastCardFrameAt = obs.timestamp,
                ) else it
            }

        // Different hash but the SAME non-null presentation as the offer currently on screen
        // (#830) → ENRICH-AS-VARIANT, not replace. A live-re-quoting card (Uber) re-renders
        // pay/miles/minutes every few seconds; each re-render churns [offerHash] while
        // [ParsedOffer.presentationKey] (store+shape) stays identical for the SAME physical offer.
        // Update to the fresh variant (offerHash + offerFields + refreshed targets) and CLEAR the
        // evaluation (economics moved → re-eval), but KEEP `presentedAt` (the physical presentation
        // epoch — so the expiry TTL can't be extended by churn), KEEP all click latches
        // (acceptClickAt/declineCommittedAt/lastClickIntent — an accept tap on variant N is NOT
        // erased by variant N+1), KEEP `returnFlow`, and KEEP `firstEvalLandedAt` (speak-once). We
        // correlate ONLY against the currently-presented offer via [presentedOffer], so a resolved
        // / expired presentation can never be resurrected, and an accepted survivor (excluded from
        // [presentedOffer]) is never mistaken for a live variant.
        presented != null && isSamePresentation(presented, offerFields) ->
            region.pendingOffers.map {
                if (it === presented) presented.copy(
                    offerHash = newHash,
                    offerFields = offerFields,
                    targets = obs.targets.ifEmpty { presented.targets },
                    sourceRuleId = obs.ruleId ?: presented.sourceRuleId,
                    evaluation = null,
                    countdownExpiresAt = countdownExpiresAt(obs, offerFields) ?: presented.countdownExpiresAt,
                    lastCardFrameAt = obs.timestamp,
                ) else it
            }

        // New or replaced offer → push it; the old presented offer and EVERY accepted survivor
        // drop. A different-presentation survivor is superseded (a new offer on screen invalidates
        // a stale accept — the pre-B3 armAcceptStash supersession rule); a SAME-hash
        // re-presentation means the prior accept did NOT take server-side, so the survivor is
        // REVOKED (#526 FIX2b) — retaining it would let a later-declined re-present fold
        // phantom economics + a never-resolvable dropoff placeholder into the job
        // (adversarial-review HIGH-1 red probe). Re-accepting the fresh copy re-creates the
        // survivor cleanly, so no double-count either.
        else -> {
            val fresh = PendingOffer(
                offerHash = newHash,
                offerFields = offerFields,
                presentedAt = obs.timestamp,
                evaluation = null,
                // vet L1: the return flow is THIS region's own last acted flow (the shared R0 flow
                // would record the OTHER platform's screen for an overlay offer); Idle when the
                // region's first-ever observation is the offer. A replacement inherits the original.
                returnFlow = presented?.returnFlow ?: (region.lastActedFlow ?: Flow.Idle),
                targets = obs.targets,
                sourceRuleId = obs.ruleId,
                countdownExpiresAt = countdownExpiresAt(obs, offerFields),
                instanceId = "${region.platform.wire}:${obs.timestamp}",
                lastCardFrameAt = obs.timestamp,
            )
            // #1119 S2: the replaced presentation leaves first (pooled, declined or accepted); every accepted
            // survivor still drops below (supersession / revocation).
            val left = presented?.let {
                val via = if (it.offerKind == OfferKind.MATCH && offerFields.parsedOffer.offerKind == OfferKind.DIRECT) {
                    PoolEntryVia.SUPERSEDED_BY_DIRECT
                } else PoolEntryVia.REPLACED
                leavePresentation(sink, region, it, obs, via, policy)
            } ?: region
            // #1598: a fresh presentation is possible new work — the late-receipt guard's counter.
            return left.copy(pendingOffers = listOf(fresh), possibleWorkSeq = region.possibleWorkSeq + 1)
        }
    }
    return region.copy(pendingOffers = newOffers)
}

/**
 * Own flow leaving offer-presentation (vet H2). Each PRESENTED offer leaves through [leavePresentation]: a committed
 * decline (#594 FIX2b) is declined and removed, an accept-latched offer SURVIVES as an accepted-pending-consumption
 * entry (`acceptedAt` = the honest accept-click time) for the task edge to mint, and anything else enters the offer
 * pool (#1119 S2), where only work at its own store links it — the click-less inference from the destination flow is
 * retired. Existing survivors pass through untouched — the mint consumes them.
 */
private fun PlatformRegionStepper.resolveOnLeave(
    sink: DecisionSink,
    region: PlatformRegion,
    obs: Observation.FlowObservation,
    policy: TransitionPolicy,
): PlatformRegion = region.pendingOffers.filter { it.acceptedAt == null }
    .fold(region) { acc, offer -> leavePresentation(sink, acc, offer, obs, PoolEntryVia.FRAME, policy) }

/**
 * Is [incoming] a churned re-render of the [presented] offer's SAME physical presentation (#830)?
 *
 * True iff both carry a non-null [cloud.trotter.log.gig.domain.model.offer.ParsedOffer.presentationKey]
 * and they match. Fail-CLOSED: a null key on either side (sha256 failure, or a legacy/hand-built
 * offer that never derived one) is NOT a match, so identity degrades to today's replace-on-any-
 * hash-change — a false MERGE is structurally impossible, only a false SPLIT back to prior behavior.
 * Platform-agnostic: reads parsed data only, no [cloud.trotter.log.gig.domain.state.Platform] branch.
 */
private fun isSamePresentation(
    presented: PendingOffer,
    incoming: ParsedFields.OfferFields,
): Boolean {
    val presentedKey = presented.offerFields.parsedOffer.presentationKey ?: return false
    val incomingKey = incoming.parsedOffer.presentationKey ?: return false
    return presentedKey == incomingKey
}

/** #1104: when this frame's countdown reaches zero — null when the frame carries no countdown. */
private fun countdownExpiresAt(obs: Observation, offerFields: ParsedFields.OfferFields): Long? =
    offerFields.parsedOffer.initialCountdownSeconds?.takeIf { it >= 0 }?.let { obs.timestamp + it * 1000L }

/**
 * Record an accept/decline click on the current presented offer for outcome resolution. Mirrors the
 * pre-B3 `FlowRegionStepper.handleOfferClick` (#594 decline-commit latch). Only a presented offer
 * latches — a click after the offer left presentation, or against an accepted survivor, is inert.
 */
private fun PlatformRegionStepper.latchOfferClick(
    region: PlatformRegion,
    obs: Observation.Click,
): PlatformRegion {
    val presented = region.presentedOffer() ?: return region
    val fields = obs.parsed as? ParsedFields.ClickFields
    // A DECLINE-intent click is the confirm-sheet commit (the decline_offer click rule is
    // screen-scoped to offer_popup_confirm_decline) — latch it, first commit wins (#594).
    val declineCommittedAt =
        if (fields?.intent == OfferIntent.DECLINE) presented.declineCommittedAt ?: obs.timestamp
        else presented.declineCommittedAt
    // The honest accept moment — captured at the accept CLICK, becomes the minted job's acceptedAt.
    val acceptClickAt =
        if (fields?.intent == OfferIntent.ACCEPT) presented.acceptClickAt ?: obs.timestamp
        else presented.acceptClickAt
    val updated = presented.copy(
        lastClickIntent = fields?.intent ?: presented.lastClickIntent,
        declineCommittedAt = declineCommittedAt,
        acceptClickAt = acceptClickAt,
    )
    return region.copy(pendingOffers = region.pendingOffers.map { if (it === presented) updated else it })
}

/**
 * Land an async offer evaluation onto the matching presented offer, correlated BY offerHash within
 * the own list (a since-replaced offer must not inherit another's economics, #345 — the correlation
 * stays hash-EXACT; a #830 enrich-as-variant re-eval is covered by the fresh EvaluateOffer the
 * variant emits, never by a presentationKey-fallback landing). A null hash (legacy replayed stubs)
 * lands on the current presented offer, as before.
 *
 * Eval-race (review F4): an in-flight eval for variant N that lands AFTER variant N+1 exists misses
 * (hash mismatch) and is simply dropped — the enrich already emitted a fresh EvaluateOffer for
 * N+1, so N+1 gets its own landing. This cannot starve the read: starvation would require the churn
 * period to be shorter than the in-process eval-loopback latency (sub-millisecond compute) versus
 * the observed 3-10 s re-quote ticks, and the SAME exposure existed pre-#830 on the replace path
 * (every replacement dropped the outgoing offer's in-flight eval identically). SpeakOffer still
 * fires exactly once via [PendingOffer.firstEvalLandedAt] regardless of which variant lands first.
 *
 * Also stamps the speak-once marker [PendingOffer.firstEvalLandedAt] on the FIRST landing per
 * physical presentation (#830) — set only when still null, so a variant re-eval landing later never
 * moves it; [OfferEffects] reads the PREVIOUS state's marker to fire `SpeakOffer` exactly once.
 *
 * #1119 S2: an evaluation that lands after its card was pooled lands on the newest pool entry with that hash and no
 * evaluation yet, so a later link carries it. No effect follows (the card is gone) and no speak-once marker moves.
 */
private fun PlatformRegionStepper.landOfferEval(
    region: PlatformRegion,
    obs: Observation.Loopback,
): PlatformRegion {
    if (obs.effect != Observation.Loopback.EFFECT_OFFER_EVALUATED) return region
    val result = obs.payload as? ObservationPayload.EvaluationResult ?: return region
    val evaluation = result.evaluation ?: return region
    val evalHash = result.offerHash
    val target = region.pendingOffers.lastOrNull {
        it.acceptedAt == null && (evalHash == null || it.offerHash == evalHash)
    } ?: return landOnPool(region, evalHash, evaluation)
    return region.copy(pendingOffers = region.pendingOffers.map {
        if (it === target) target.copy(
            evaluation = evaluation,
            firstEvalLandedAt = target.firstEvalLandedAt ?: obs.timestamp,
        ) else it
    })
}

private fun landOnPool(
    region: PlatformRegion,
    evalHash: String?,
    evaluation: cloud.trotter.log.gig.domain.evaluation.OfferEvaluation,
): PlatformRegion {
    if (evalHash == null) return region
    val entry = region.offerPool.lastOrNull { it.offer.offerHash == evalHash && it.offer.evaluation == null }
        ?: return region
    return region.copy(offerPool = region.offerPool.map {
        if (it === entry) it.copy(offer = it.offer.copy(evaluation = evaluation)) else it
    })
}

/**
 * OFFER_EXPIRY timer fire (vet H1/M5): the presented offer the timer was armed for — by the payload's instance id,
 * by its `offerHash` only when that is null — leaves through [leavePresentation] (#1119 S2: pooled `via
 * EXPIRY_TIMER`, or declined when its decline was committed). NO-OP on an accept-latched or
 * accepted-pending-consumption offer — both TTLs land inside the accept grace, and expiring an accepted survivor
 * would destroy the mint. A fire matching nothing is a no-op.
 */
private fun PlatformRegionStepper.expireOffer(
    sink: DecisionSink,
    region: PlatformRegion,
    obs: Observation.Timeout,
    policy: TransitionPolicy,
): PlatformRegion {
    val payload = obs.payload as? ObservationPayload.OfferExpiry ?: return region
    val hash = payload.offerHash
    val target = region.pendingOffers.firstOrNull {
        if (payload.instanceId != null) it.instanceKey == payload.instanceId else it.offerHash == hash
    } ?: return region
    if (target.acceptedAt != null || target.isAcceptLatched()) {
        // Defended invariant (Principle 7 WARN; #692 review F1): a phantom timeout was suppressed on an
        // ACCEPTED offer — firing it would have pooled a live accept (#1119 S2; a false OFFER_TIMEOUT
        // before) and destroyed the mint (the #526 regression class). Hash only, no PII.
        Timber.tag("StateMachine").w(
            "OFFER_EXPIRY no-op (#438 B3): offer %s is accept-latched/accepted — phantom timeout suppressed",
            hash,
        )
        return region
    }
    return leavePresentation(sink, region, target, obs, PoolEntryVia.EXPIRY_TIMER, policy)
}

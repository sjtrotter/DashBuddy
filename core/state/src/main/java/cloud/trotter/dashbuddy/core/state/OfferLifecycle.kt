package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
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
 */
internal fun PlatformRegionStepper.stepOffers(
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
            Flow.OfferPresented -> pushOrReplaceOffer(pruned, obs)
            else -> resolveOnLeave(pruned, obs)
        }
        is Observation.Loopback -> landOfferEval(pruned, obs)
        is Observation.Timeout ->
            if (obs.type == TimeoutType.OFFER_EXPIRY) expireOffer(pruned, obs) else pruned
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
 * supersession + revocation rules as lifecycle rules.
 */
private fun PlatformRegionStepper.pushOrReplaceOffer(
    region: PlatformRegion,
    obs: Observation.FlowObservation,
): PlatformRegion {
    val offerFields = obs.parsed as? ParsedFields.OfferFields
    val newHash = offerFields?.parsedOffer?.offerHash
    val presented = region.presentedOffer()

    val newOffers = when {
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
            )
            listOf(fresh)
        }
    }
    return region.copy(pendingOffers = newOffers)
}

/**
 * Own flow leaving offer-presentation (vet H2). Each PRESENTED offer resolves: an ACCEPTED one
 * SURVIVES as an accepted-pending-consumption entry (`acceptedAt` = the honest accept-click time) so
 * the task edge can still mint it (incl. the F3 fix); a declined / timed-out one is removed. The
 * `OFFER_ACCEPTED`/`DECLINED`/`TIMEOUT` event fires from [OfferEffects] at THIS edge (not at
 * consumption). Existing survivors pass through untouched — the mint consumes them.
 *
 * "Accepted" ⇔ the accept latch is set OR the destination flow implies acceptance
 * ([destinationImpliesAccept]) — reaching a PHASED task screen IS the acceptance (the platform only
 * advances to pickup/dropoff after an accept), so an offer→task edge mints the full offer-shaped job
 * even when no accept CLICK was captured (the pre-B3 `prevFlow.pendingOffer` accept-adjacent read
 * did this unconditionally). The coarse `task:active` destination is guarded (#762 D2 finding 1 —
 * see the helper). A committed decline (#594 FIX2b) NEVER survives — that revocation must not fold
 * phantom economics into a job.
 */
private fun PlatformRegionStepper.resolveOnLeave(
    region: PlatformRegion,
    obs: Observation.FlowObservation,
): PlatformRegion {
    if (region.pendingOffers.none { it.acceptedAt == null }) return region
    val destination = obs.flow
    val newOffers = region.pendingOffers.mapNotNull { offer ->
        when {
            offer.acceptedAt != null -> offer // existing survivor — consumption is the mint's job
            offer.declineCommittedAt != null -> null // committed decline (#594 FIX2b) — never survives
            offer.isAcceptLatched() || destinationImpliesAccept(destination, offer) ->
                offer.copy(acceptedAt = offer.acceptClickAt ?: obs.timestamp)
            else -> null // declined / timed out → resolved away
        }
    }
    return region.copy(pendingOffers = newOffers)
}

/**
 * Is [incoming] a churned re-render of the [presented] offer's SAME physical presentation (#830)?
 *
 * True iff both carry a non-null [cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer.presentationKey]
 * and they match. Fail-CLOSED: a null key on either side (sha256 failure, or a legacy/hand-built
 * offer that never derived one) is NOT a match, so identity degrades to today's replace-on-any-
 * hash-change — a false MERGE is structurally impossible, only a false SPLIT back to prior behavior.
 * Platform-agnostic: reads parsed data only, no [cloud.trotter.dashbuddy.domain.state.Platform] branch.
 */
private fun isSamePresentation(
    presented: PendingOffer,
    incoming: ParsedFields.OfferFields,
): Boolean {
    val presentedKey = presented.offerFields.parsedOffer.presentationKey ?: return false
    val incomingKey = incoming.parsedOffer.presentationKey ?: return false
    return presentedKey == incomingKey
}

/**
 * Does leaving offer-presentation TO [destination] imply this offer was click-lessly ACCEPTED?
 *
 * A **phased** task flow (pickup/dropoff navigation/arrived) does, unconditionally — the platform
 * only advances to those screens after an accept. The coarse [Flow.TaskActive] is different (#762
 * D2, adversarial finding 1): on a coarse platform it is the AMBIENT screen (Uber's `on_job_view`
 * is up for the whole trip), so mid-trip it is also exactly what re-renders when a stacked offer's
 * overlay vanishes after a **decline or timeout** — and Uber ships no decline click rule, so no
 * decline latch can flag that case. Returning to the surface you were already on is not an accept:
 * a `task:active` destination implies acceptance only when the offer's own
 * [PendingOffer.returnFlow] was NOT already a task flow (e.g. `Idle` — a job appearing where there
 * was none IS the legitimate click-less accept). Without this guard, a declined/timed-out mid-trip
 * offer would be stamped accepted the moment `on_job_view` re-renders — a phantom `OFFER_ACCEPTED`
 * plus phantom add-on economics and never-resolvable placeholders (the job-61 absorption class),
 * unrescuable by `OFFER_EXPIRY` once `acceptedAt` is set.
 */
private fun destinationImpliesAccept(destination: Flow?, offer: PendingOffer): Boolean {
    if (destination?.isTaskFlow() != true) return false
    return destination != Flow.TaskActive || !offer.returnFlow.isTaskFlow()
}

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
 * Also stamps the speak-once marker [PendingOffer.firstEvalLandedAt] on the FIRST landing per
 * physical presentation (#830) — set only when still null, so a variant re-eval landing later never
 * moves it; [OfferEffects] reads the PREVIOUS state's marker to fire `SpeakOffer` exactly once.
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
    } ?: return region
    return region.copy(pendingOffers = region.pendingOffers.map {
        if (it === target) target.copy(
            evaluation = evaluation,
            firstEvalLandedAt = target.firstEvalLandedAt ?: obs.timestamp,
        ) else it
    })
}

/**
 * OFFER_EXPIRY timer fire (vet H1/M5): resolve the presented offer named by the timer's `offerHash`
 * payload as a TIMEOUT (remove it → [OfferEffects] emits OFFER_TIMEOUT). NO-OP on an accept-latched
 * or accepted-pending-consumption offer — both TTLs land inside the accept grace, and timing-out an
 * accepted survivor would log a false OFFER_TIMEOUT and destroy the mint.
 */
private fun PlatformRegionStepper.expireOffer(
    region: PlatformRegion,
    obs: Observation.Timeout,
): PlatformRegion {
    val hash = (obs.payload as? ObservationPayload.OfferExpiry)?.offerHash ?: return region
    val target = region.pendingOffers.firstOrNull { it.offerHash == hash } ?: return region
    if (target.acceptedAt != null || target.isAcceptLatched()) {
        // Defended invariant (Principle 7 WARN; #692 review F1): a phantom timeout was suppressed
        // on an ACCEPTED offer — firing it would have logged a false OFFER_TIMEOUT and destroyed
        // the mint (the #526 regression class). Hash only, no PII.
        Timber.tag("StateMachine").w(
            "OFFER_EXPIRY no-op (#438 B3): offer %s is accept-latched/accepted — phantom timeout suppressed",
            hash,
        )
        return region
    }
    return region.copy(pendingOffers = region.pendingOffers.filterNot {
        it.offerHash == hash && it.acceptedAt == null
    })
}

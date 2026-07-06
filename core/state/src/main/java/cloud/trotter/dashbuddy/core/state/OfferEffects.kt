package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferReceivedPayload
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import timber.log.Timber

/**
 * #438 item 7 (B3, vet L4) — the offer-lifecycle effect diffs, extracted from the retired
 * `EffectMap.diffFlowRegion` (which had diffed the shared global R0 offer slot). `internal`
 * extensions on [EffectMap] (mirroring the [OfferLifecycle] stepper move; [EffectMap] is past the
 * #237 ceiling). Diffs THIS platform's owned [PlatformRegion.pendingOffers] before/after a step.
 *
 * **Event payload shapes AND emission edges are both contract** (vet H2): the projector and the
 * replay oracles must see identical OFFER_* streams for single-platform sessions, so this mirrors
 * the pre-B3 scalar logic verbatim on the single PRESENTED offer of the owned list. The
 * accept-latched offer's `OFFER_ACCEPTED` fires HERE at the leave-of-presentation edge (the offer
 * gone from the presented set), NOT at the later mint that consumes the survivor — an accepted
 * survivor is invisible to this presented-offer diff, so its removal-by-consumption emits nothing.
 */
internal fun EffectMap.diffOfferLifecycle(
    prev: PlatformRegion,
    next: PlatformRegion,
    obs: Observation,
    sessionId: String?,
): List<AppEffect> = buildList {
    // The single PRESENTED offer (accepted-pending-consumption survivors excluded), so this diff is
    // the exact scalar prevOffer/nextOffer of the pre-B3 R0 slot, per platform.
    val prevOffer = prev.pendingOffers.lastOrNull { it.acceptedAt == null }
    val nextOffer = next.pendingOffers.lastOrNull { it.acceptedAt == null }
    val platform = next.platform

    // Offer presented (fresh receive). OFFER_RECEIVED logged with the full parsed offer; the rich
    // evaluation lands on the closing OFFER_ACCEPTED/DECLINED/TIMEOUT payload. The EvaluateOffer
    // platform is the OFFER's own provenance (#438 8a) so the eval loopback lands on the owning
    // region. Arms the OFFER_EXPIRY safety timer (vet H1).
    if (prevOffer == null && nextOffer != null) {
        val offer = nextOffer.offerFields
        val receivedPayload = OfferReceivedPayload(
            offerHash = nextOffer.offerHash,
            parsedOffer = offer.parsedOffer,
            presentedAt = nextOffer.presentedAt,
            platform = platform.name,
            returnFlow = nextOffer.returnFlow,
        )
        add(logEffect(sessionId, AppEventType.OFFER_RECEIVED, obs.timestamp, receivedPayload))
        add(AppEffect.EvaluateOffer(offer.parsedOffer, nextOffer.offerHash, nextOffer.platform))
        addAll(armOfferExpiry(nextOffer, platform, obs))
    }

    // Offer replaced (different hash). Resolution log for the old offer stays here (the new offer's
    // OFFER_RECEIVED is intentionally NOT re-emitted — the rule-declared screenshot/log dedup by
    // offerHash owns that, matching pre-B3).
    if (prevOffer != null && nextOffer != null && prevOffer.offerHash != nextOffer.offerHash) {
        val outcome = resolveOfferOutcome(obs, prevOffer)
        add(logEffect(sessionId, outcome, obs.timestamp, offerPayload(prevOffer, outcome, obs.timestamp, "Replaced by new offer")))
        // #601: surface the replaced offer's disposition, suffixed so it reads as the OLD offer's.
        add(AppEffect.UpdateBubble("${outcomeCardText(outcome)} (offer replaced)", persona = ChatPersona.Dispatcher))
        // #457: dismiss the OLD offer's heads-up now so a tap can't resolve against the NEW offer.
        add(AppEffect.CancelOfferNotification(prevOffer.offerHash))
        val offer = nextOffer.offerFields
        add(AppEffect.EvaluateOffer(offer.parsedOffer, nextOffer.offerHash, nextOffer.platform))
        // Re-arm expiry for the new offer (the old offer's timer is superseded by the (type,platform)
        // key — a single re-arm; the old fire would no-op on the new hash regardless).
        addAll(armOfferExpiry(nextOffer, platform, obs))
    }

    // Evaluation landed (async loopback) → the heads-up notification + spoken read, both off the
    // evaluation. Keyed on eval arriving in state (same offer, null → non-null).
    val landedEval = nextOffer?.evaluation
    if (prevOffer != null && nextOffer != null && landedEval != null &&
        prevOffer.offerHash == nextOffer.offerHash && prevOffer.evaluation == null
    ) {
        val parsedOffer = nextOffer.offerFields.parsedOffer
        val expiresAt = parsedOffer.initialCountdownSeconds?.let { nextOffer.presentedAt + it * 1000L }
        val offerCard = FlowCardSnapshot.Offer.from(
            parsedOffer = parsedOffer,
            evaluation = landedEval,
            offerHash = nextOffer.offerHash,
            phaseStartedAt = nextOffer.presentedAt,
            expiresAt = expiresAt,
            countdownSeconds = parsedOffer.initialCountdownSeconds,
        )
        add(AppEffect.PostOfferNotification(landedEval, offerCard, nextOffer.offerHash, nextOffer.platform))
        add(AppEffect.SpeakOffer(landedEval))
    }

    // Offer resolved (accepted/declined/timeout) — the presented offer left presentation this step
    // (consumed-into-a-mint accept, an accept becoming a survivor, a decline, or a timeout). The
    // OFFER_ACCEPTED fires HERE off resolveOfferOutcome's accept-latch read; the later survivor
    // consumption is invisible to this presented-offer diff.
    if (prevOffer != null && nextOffer == null) {
        val outcome = resolveOfferOutcome(obs, prevOffer)
        // #594: latch forced DECLINED but the last literal click was ACCEPT → the "Review offer"→
        // Accept race after the decline already committed.
        val raceDescription = if (
            outcome == AppEventType.OFFER_DECLINED &&
            prevOffer.declineCommittedAt != null &&
            prevOffer.lastClickIntent == OfferIntent.ACCEPT
        ) {
            "Accept clicked after decline was already committed — decline stands (#594)"
        } else {
            null
        }
        add(AppEffect.CancelOfferNotification(prevOffer.offerHash))
        add(logEffect(sessionId, outcome, obs.timestamp, offerPayload(prevOffer, outcome, obs.timestamp, raceDescription)))
        // #601: the single place the chat states what actually happened, off the SAME logged outcome.
        add(AppEffect.UpdateBubble(outcomeCardText(outcome), persona = ChatPersona.Dispatcher))
        addAll(cancelOfferExpiry(platform))
    }

    // Click feedback (ACK) — instant "Accepting…/Declining…" for a tap on the presented offer,
    // scoped to the OBSERVING platform (a click steps only obs.platform's region). #601: an ACK,
    // not an outcome claim — the committed card fires from the resolution block above.
    if (obs.platform == platform && obs is Observation.Click && prevOffer != null && nextOffer != null) {
        val fields = obs.parsed as? cloud.trotter.dashbuddy.domain.state.ParsedFields.ClickFields
        val declineCommitted = (nextOffer.declineCommittedAt ?: prevOffer.declineCommittedAt)
        when (fields?.intent) {
            OfferIntent.ACCEPT ->
                if (declineCommitted != null) {
                    Timber.w(
                        "Accept click ignored (#594): decline already committed at %d — decline stands",
                        declineCommitted,
                    )
                    add(
                        AppEffect.UpdateBubble(
                            "Decline already submitted — Accept won't take",
                            persona = ChatPersona.Dispatcher,
                        )
                    )
                } else {
                    add(AppEffect.UpdateBubble("Accepting…", persona = ChatPersona.Dispatcher))
                }
            OfferIntent.DECLINE -> add(
                AppEffect.UpdateBubble("Declining…", persona = ChatPersona.Dispatcher)
            )
        }
    }
}

/**
 * Arm the [TimeoutType.OFFER_EXPIRY] safety timer for a presented offer (vet H1) — the
 * GRACE_COMMIT mechanism (an effect, never a reducer arm). Deadline `presentedAt +
 * countdown*1000`, else a 120s de-facto TTL (no rule parses a countdown today, so this can never
 * fire early). The payload carries the offerHash so the fire resolves BY hash (vet M5).
 */
private fun EffectMap.armOfferExpiry(
    offer: PendingOffer,
    platform: Platform,
    obs: Observation,
): List<AppEffect> {
    val countdown = offer.offerFields.parsedOffer.initialCountdownSeconds
    val deadline = offer.presentedAt + (countdown?.times(1000L) ?: EffectMap.OFFER_EXPIRY_DEFAULT_MS)
    return listOf(
        AppEffect.ScheduleTimeout(
            durationMs = (deadline - obs.timestamp).coerceAtLeast(1L),
            type = TimeoutType.OFFER_EXPIRY,
            platform = platform,
            payload = ObservationPayload.OfferExpiry(offer.offerHash),
        ),
    )
}

private fun EffectMap.cancelOfferExpiry(platform: Platform): List<AppEffect> =
    listOf(AppEffect.CancelTimeout(TimeoutType.OFFER_EXPIRY, platform))

/**
 * HUD-initiated accept/decline (bubble buttons / own-notification actions) → perform the app-owned
 * action, aimed by the target the offer rule bound (#425). #438 B3 (vet M4): resolves the offer by
 * the tap's OWN carried identity — `(platform, offerHash)` against that platform's owned
 * `pendingOffers`. The pre-B3 global-flow `OfferPresented` gate is GONE (it dropped the headline
 * multiplatform case — acting on a buried Uber overlay offer from its heads-up while DoorDash owns
 * the screen). Found → act; not found → WARN + abort to manual. A UiInput never changes flow.
 */
internal fun EffectMap.diffOfferAction(obs: Observation, next: cloud.trotter.dashbuddy.domain.state.AppState): List<AppEffect> {
    if (obs !is Observation.UiInput) return emptyList()
    val action = when (obs.action) {
        OfferIntent.ACCEPT -> cloud.trotter.dashbuddy.domain.action.RuleAction.ACCEPT_OFFER
        OfferIntent.DECLINE -> cloud.trotter.dashbuddy.domain.action.RuleAction.DECLINE_OFFER
        else -> return emptyList()
    }
    val platform = obs.platform.takeIf { it != Platform.Unknown }
    // Resolve the acted offer within its OWN platform's list, by carried offerHash. The
    // presented-offer fallback applies ONLY to a hash-less legacy dispatch: a tap that CARRIED a
    // hash which no longer matches is a stale banner racing a replacement — it must WARN + abort
    // to manual (spec M4; adversarial-review MED-1), never act on the offer that replaced it.
    val region = platform?.let { next.regions.platforms[it] }
    val offer = region?.let { r ->
        if (obs.offerHash != null) r.pendingOffers.firstOrNull { it.offerHash == obs.offerHash }
        else r.presentedOffer()
    }
    val target = offer?.targets?.get(action.targetBindName)
    return when {
        platform == null || offer == null -> {
            Timber.w(
                "Offer action %s dropped (#438 B3): no offer for carried identity " +
                    "(platform=%s, offerHash=%s) — aborting to manual",
                action.wire, platform?.wire, obs.offerHash,
            )
            emptyList()
        }
        target == null -> {
            Timber.w(
                "No '%s' target bound for %s — %s unavailable, leaving it to the user (#457)",
                action.targetBindName, platform.wire, action.wire,
            )
            emptyList()
        }
        else -> {
            Timber.i("Offer action %s firing on %s (#457)", action.wire, platform.wire)
            listOf(
                AppEffect.PerformRuleAction(
                    action, platform, target, offer.sourceRuleId,
                    trigger = cloud.trotter.dashbuddy.domain.action.ActionTrigger.USER,
                ),
            )
        }
    }
}

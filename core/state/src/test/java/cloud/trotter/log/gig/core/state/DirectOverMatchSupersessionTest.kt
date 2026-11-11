package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.evaluation.OfferAction
import cloud.trotter.log.gig.domain.evaluation.OfferEvaluation
import cloud.trotter.log.gig.domain.evaluation.OfferQuality
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.OfferPayload
import cloud.trotter.log.gig.domain.model.offer.OfferKind
import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.model.order.OrderType
import cloud.trotter.log.gig.domain.model.order.ParsedOrder
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.core.state.model.DecisionSink
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.FlowRegion
import cloud.trotter.log.gig.domain.state.PoolEntryVia
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PendingOffer
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #881 — a DIRECT offer landing over a MATCH display is an EXPECTED supersession.
 *
 * Uber presents Trip-Radar trips as focused cards that look like ordinary offers with a "Match"
 * CTA instead of "Accept", and the platform will send a real (direct) offer straight over the top
 * of one. Before this, that arrived as the generic #830 replace: an `OFFER_TIMEOUT("Replaced by
 * new offer")` plus a "(offer replaced)" chat card, i.e. the app narrating an anomaly for
 * something the platform does by design, while the dasher is mid-read of the card that is being
 * upgraded.
 *
 * The contract this pins:
 *  - SAME event type and SAME emission edge (the projector/replay oracles must not see a new
 *    OFFER_* shape) — only the payload `description` and the chat card change;
 *  - the quiet path is keyed on the KINDS, never on [Platform] (P8): any ruleset that parses
 *    `offerKind` gets it, and a null kind on either side keeps the pre-#881 behaviour verbatim;
 *  - every other replace shape (match→match, direct→direct, direct→match, unknown kinds) still
 *    emits the loud "Replaced by new offer" + bubble.
 *
 * Mutation-meaningful: dropping the kind test makes the quiet assertions fail; widening it to
 * "any kind change" makes the direct→match case fail.
 *
 * #1119 S2: a replaced offer no longer resolves at the replace — it waits in the offer pool, its leave recorded as
 * `SUPERSEDED_BY_DIRECT` or `REPLACED`, and the description lands on its shift-end outcome. Neither path posts a chat
 * line at the leave; a superseded offer that had a committed decline still logs it, quietly.
 */
class DirectOverMatchSupersessionTest {

    private val effectMap = EffectMap()
    private val platform = Platform.Uber

    private fun parsedOffer(hash: String, key: String?, kind: OfferKind?) = ParsedOffer(
        offerHash = hash,
        presentationKey = key,
        offerKind = kind,
        payAmount = 6.44,
        distanceMiles = 8.5,
        timeToCompleteMinutes = 26L,
        orders = listOf(
            ParsedOrder(
                orderIndex = 0, orderType = OrderType.PICKUP, storeName = "Sonic",
                itemCount = 1, isItemCountEstimated = false, badges = emptySet(),
            ),
        ),
    )

    private fun pending(hash: String, key: String?, kind: OfferKind?, presentedAt: Long = 1_000L) =
        PendingOffer(
            offerHash = hash,
            offerFields = ParsedFields.OfferFields(parsedOffer = parsedOffer(hash, key, kind)),
            presentedAt = presentedAt,
            evaluation = evalOf(),
            returnFlow = Flow.Idle,
            sourceRuleId = "uber.screen.offer",
        )

    private fun evalOf() = OfferEvaluation(
        action = OfferAction.ACCEPT, score = 1.0, qualityLevel = OfferQuality.GOOD,
        payAmount = 6.44, fuelCostEstimate = 0.5, netPayAmount = 5.94, distanceMiles = 8.5,
        dollarsPerMile = 0.7, dollarsPerHour = 14.0, estimatedTimeMinutes = 26.0,
        itemCount = 1.0, merchantName = "Sonic",
    )

    private fun region(offer: PendingOffer) = PlatformRegion(
        platform = platform,
        mode = Mode.Online,
        session = Session("sess-1", startedAt = 100L),
        pendingOffers = listOf(offer),
    )

    private fun obs(t: Long, to: OfferKind?) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "uber.screen.offer",
        metadata = ReplayMetadata.EMPTY, flow = Flow.OfferPresented, modeHint = Mode.Online,
        parsed = ParsedFields.OfferFields(parsedOffer = parsedOffer("H2", "P2", to)),
    )

    private class Replaced(val region: PlatformRegion, val effects: List<AppEffect>)

    /** Step one replace: presented offer [from] is superseded by [to]; the stepper decides, the diff emits. */
    private fun replaceStep(from: OfferKind?, to: OfferKind?, prior: (PendingOffer) -> PendingOffer = { it }): Replaced {
        val prev = region(prior(pending("H1", "P1", from, presentedAt = 1_000L)))
        val step = PlatformRegionStepper().stepWithDecisions(
            prev, FlowRegion(flow = Flow.OfferPresented), FlowRegion(flow = Flow.OfferPresented), obs(2_000L, to), TransitionPolicy(),
        )
        return Replaced(step.region, effectMap.diffOfferLifecycle(prev, step.region, obs(2_000L, to), "sess-1", step.decisions))
    }

    private fun replaceEffects(from: OfferKind?, to: OfferKind?): List<AppEffect> = replaceStep(from, to).effects

    /** The replaced offer's shift-end description (it waits in the pool until then). */
    private fun descriptionOf(from: OfferKind?, to: OfferKind?): String? {
        val sink = DecisionSink()
        flushAtShiftEnd(sink, replaceStep(from, to).region, 9_000L)
        return sink.build().offerOutcomes.single { it.offer.offerHash == "H1" }.description
    }

    private fun viaOf(from: OfferKind?, to: OfferKind?): PoolEntryVia =
        replaceStep(from, to).region.offerPool.single().via

    private fun hasReplacedBubble(fx: List<AppEffect>): Boolean =
        fx.any { it is AppEffect.UpdateBubble && it.text.contains("replaced by a new offer") }

    private fun hasOutcome(fx: List<AppEffect>): Boolean =
        fx.any { it is AppEffect.LogEvent && it.event.payload is OfferPayload }

    @Test
    fun `direct over match resolves quietly - same event, softer description, no replaced bubble`() {
        val fx = replaceEffects(from = OfferKind.MATCH, to = OfferKind.DIRECT)

        assertFalse("#1119 S2: the match waits in the pool, no outcome at the replace", hasOutcome(fx))
        assertEquals(PoolEntryVia.SUPERSEDED_BY_DIRECT, viaOf(OfferKind.MATCH, OfferKind.DIRECT))
        assertEquals(
            "the expected direct-preempts-match description, not the anomalous replace copy",
            "Superseded by direct offer",
            descriptionOf(OfferKind.MATCH, OfferKind.DIRECT),
        )
        assertFalse("no '(offer replaced)' chat noise for an expected supersession", hasReplacedBubble(fx))
        // Everything else about the replace edge is untouched.
        assertEquals(
            "the superseded hash's heads-up is still cancelled",
            listOf("H1"),
            fx.filterIsInstance<AppEffect.CancelOfferNotification>().map { it.offerHash },
        )
        assertTrue("the direct offer is still evaluated", fx.any { it is AppEffect.EvaluateOffer })
        assertTrue("its expiry timer is still armed", fx.any { it is AppEffect.ScheduleTimeout })
    }

    @Test
    fun `match over match is a plain replace (a radar card genuinely replaced)`() {
        assertEquals(PoolEntryVia.REPLACED, viaOf(OfferKind.MATCH, OfferKind.MATCH))
        assertEquals("Replaced by new offer; no work at its store by shift end (#1119)", descriptionOf(OfferKind.MATCH, OfferKind.MATCH))
    }

    @Test
    fun `direct over direct is a plain replace (the pre-881 DoorDash-shaped path)`() {
        assertEquals(PoolEntryVia.REPLACED, viaOf(OfferKind.DIRECT, OfferKind.DIRECT))
    }

    @Test
    fun `match over direct is NOT quiet - only the direct-preempts-match direction is expected`() {
        assertEquals(PoolEntryVia.REPLACED, viaOf(OfferKind.DIRECT, OfferKind.MATCH))
    }

    @Test
    fun `a platform with no offerKind concept is a plain replace`() {
        assertEquals(PoolEntryVia.REPLACED, viaOf(null, null))
    }

    @Test
    fun `a half-known pair stays a plain replace (fail toward the pre-881 narration)`() {
        assertEquals(PoolEntryVia.REPLACED, viaOf(OfferKind.MATCH, null))
        assertEquals(PoolEntryVia.REPLACED, viaOf(null, OfferKind.DIRECT))
    }

    @Test
    fun `a committed decline superseded by a direct offer logs quietly, and a plain replace announces it`() {
        val declined: (PendingOffer) -> PendingOffer = { it.copy(lastClickIntent = "decline_offer", declineCommittedAt = 1_500L) }
        val quiet = replaceStep(OfferKind.MATCH, OfferKind.DIRECT, declined).effects
        val out = quiet.filterIsInstance<AppEffect.LogEvent>().single { it.event.type == AppEventType.OFFER_DECLINED }
        assertEquals("Superseded by direct offer", (out.event.payload as OfferPayload).description)
        assertFalse(quiet.any { it is AppEffect.UpdateBubble && it.text.startsWith("Offer declined") })
        val loud = replaceStep(OfferKind.MATCH, OfferKind.MATCH, declined).effects
        assertTrue(hasReplacedBubble(loud))
    }
}

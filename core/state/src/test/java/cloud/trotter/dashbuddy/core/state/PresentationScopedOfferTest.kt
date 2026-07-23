package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.evaluation.OfferQuality
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #830 — presentation-scoped offer identity at the state layer. Two seams:
 *   - [OfferLifecycle.pushOrReplaceOffer] three-way (same hash / enrich-as-variant / replace) and
 *     the speak-once marker set by `landOfferEval`;
 *   - [OfferEffects.diffOfferLifecycle] gating OFFER_TIMEOUT/heads-up-cancel on a REAL replacement
 *     (different presentationKey) and `SpeakOffer` on the once-per-presentation marker.
 *
 * Mutation-meaningful: severing the presentationKey comparison collapses an enrich into a replace
 * (the "no OFFER_TIMEOUT" / "presentedAt survives" assertions fail); severing the speak-once gate
 * fires SpeakOffer on every variant (the speak-once assertion fails).
 */
class PresentationScopedOfferTest {

    private val stepper = PlatformRegionStepper()
    private val policy = TransitionPolicy()
    private val effectMap = EffectMap()

    private val PLATFORM = Platform.Uber

    private fun region(vararg offers: PendingOffer) = PlatformRegion(
        platform = PLATFORM,
        mode = Mode.Online,
        session = Session("sess-1", startedAt = 100L),
        pendingOffers = offers.toList(),
    )

    private fun parsedOffer(hash: String, key: String?, store: String = "Sonic") = ParsedOffer(
        offerHash = hash,
        presentationKey = key,
        payAmount = 6.44,
        distanceMiles = 8.5,
        timeToCompleteMinutes = 26L,
        orders = listOf(
            ParsedOrder(
                orderIndex = 0, orderType = OrderType.PICKUP, storeName = store,
                itemCount = 1, isItemCountEstimated = false, badges = emptySet(),
            ),
        ),
    )

    private fun pending(
        hash: String,
        key: String?,
        presentedAt: Long = 1_000L,
        evaluation: OfferEvaluation? = null,
        acceptClickAt: Long? = null,
        firstEvalLandedAt: Long? = null,
    ) = PendingOffer(
        offerHash = hash,
        offerFields = ParsedFields.OfferFields(parsedOffer = parsedOffer(hash, key)),
        presentedAt = presentedAt,
        evaluation = evaluation,
        returnFlow = Flow.Idle,
        acceptClickAt = acceptClickAt,
        firstEvalLandedAt = firstEvalLandedAt,
        sourceRuleId = "uber.screen.offer",
    )

    private fun offerObs(t: Long, hash: String, key: String?, store: String = "Sonic") = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "uber.screen.offer",
        metadata = ReplayMetadata.EMPTY, flow = Flow.OfferPresented, modeHint = Mode.Online,
        parsed = ParsedFields.OfferFields(parsedOffer = parsedOffer(hash, key, store)),
    )

    private fun evalOf(pay: Double = 6.44) = OfferEvaluation(
        action = OfferAction.ACCEPT, score = 1.0, qualityLevel = OfferQuality.GOOD,
        payAmount = pay, fuelCostEstimate = 0.5, netPayAmount = pay - 0.5, distanceMiles = 8.5,
        dollarsPerMile = 0.7, dollarsPerHour = 14.0, estimatedTimeMinutes = 26.0,
        itemCount = 1.0, merchantName = "Sonic",
    )

    /** Drive a sequence through the stepper (deriving prev/next flow like StateMachine does). */
    private fun drive(start: PlatformRegion, vararg obs: Observation): PlatformRegion {
        var r = start
        var prevFlow = FlowRegion()
        for (o in obs) {
            val f = (o as? Observation.FlowObservation)?.flow
            val nextFlow = if (f != null) prevFlow.copy(flow = f, activePlatform = r.platform) else prevFlow
            r = stepper.step(r, prevFlow, nextFlow, o, policy)
            prevFlow = nextFlow
        }
        return r
    }

    // =============================================================================================
    // pushOrReplaceOffer — three-way
    // =============================================================================================

    @Test
    fun `same hash re-presentation enriches in place (unchanged pre-830 path)`() {
        val start = region(pending("H1", "P", presentedAt = 1_000L))
        val r = drive(start, offerObs(1_500L, "H1", "P"))
        val presented = r.presentedOffer()!!
        assertEquals("H1", presented.offerHash)
        assertEquals("presentedAt preserved", 1_000L, presented.presentedAt)
        assertEquals("still exactly one pending", 1, r.pendingOffers.size)
    }

    @Test
    fun `different hash but SAME presentationKey enriches as a variant, keeping the presentation`() {
        val start = region(
            pending("H1", "P", presentedAt = 1_000L, evaluation = evalOf(), acceptClickAt = 1_050L, firstEvalLandedAt = 1_100L),
        )
        val r = drive(start, offerObs(2_000L, "H2", "P"))
        val presented = r.presentedOffer()!!
        assertEquals("hash updated to the new variant", "H2", presented.offerHash)
        assertEquals("presentedAt KEPT (physical presentation epoch)", 1_000L, presented.presentedAt)
        assertEquals("accept latch KEPT", 1_050L, presented.acceptClickAt)
        assertEquals("speak-once marker KEPT", 1_100L, presented.firstEvalLandedAt)
        assertNull("evaluation CLEARED (numbers moved → re-eval)", presented.evaluation)
        assertEquals("no new pending minted", 1, r.pendingOffers.size)
    }

    @Test
    fun `different presentationKey REPLACES (a genuinely new offer)`() {
        val start = region(pending("H1", "P1", presentedAt = 1_000L, acceptClickAt = 1_050L))
        val r = drive(start, offerObs(2_000L, "H2", "P2", store = "Whataburger"))
        val presented = r.presentedOffer()!!
        assertEquals("replaced with the new offer", "H2", presented.offerHash)
        assertEquals("fresh presentedAt (a new physical offer)", 2_000L, presented.presentedAt)
        assertNull("the old offer's latch is gone (fresh PendingOffer)", presented.acceptClickAt)
    }

    @Test
    fun `a null presentationKey on the incoming offer fails closed to REPLACE`() {
        // Fail-closed (#362): a null key must never MERGE — degrade to replace.
        val start = region(pending("H1", "P", presentedAt = 1_000L, acceptClickAt = 1_050L))
        val r = drive(start, offerObs(2_000L, "H2", null))
        val presented = r.presentedOffer()!!
        assertEquals("H2", presented.offerHash)
        assertEquals("replaced, not enriched", 2_000L, presented.presentedAt)
        assertNull(presented.acceptClickAt)
    }

    @Test
    fun `a null presentationKey on the PRESENTED offer fails closed to REPLACE`() {
        val start = region(pending("H1", null, presentedAt = 1_000L, acceptClickAt = 1_050L))
        val r = drive(start, offerObs(2_000L, "H2", "P"))
        val presented = r.presentedOffer()!!
        assertEquals("replaced, not enriched", 2_000L, presented.presentedAt)
        assertNull(presented.acceptClickAt)
    }

    // =============================================================================================
    // landOfferEval — speak-once marker
    // =============================================================================================

    @Test
    fun `landOfferEval stamps firstEvalLandedAt on the first landing and never moves it`() {
        val start = region(pending("H1", "P", presentedAt = 1_000L))
        val loop = { t: Long, hash: String ->
            Observation.Loopback(
                timestamp = t, effect = Observation.Loopback.EFFECT_OFFER_EVALUATED,
                targetPlatform = PLATFORM,
                payload = ObservationPayload.EvaluationResult(OfferAction.ACCEPT.name, hash, evalOf()),
            )
        }
        val afterFirst = drive(start, loop(1_200L, "H1"))
        assertEquals("marker set to the first landing time", 1_200L, afterFirst.presentedOffer()!!.firstEvalLandedAt)
        // A later landing on the same presentation must NOT move the marker.
        val afterSecond = drive(afterFirst, loop(1_400L, "H1"))
        assertEquals("marker unchanged on a later landing", 1_200L, afterSecond.presentedOffer()!!.firstEvalLandedAt)
    }

    // =============================================================================================
    // diffOfferLifecycle — effect gating
    // =============================================================================================

    private fun diff(prev: PlatformRegion, next: PlatformRegion, t: Long): List<AppEffect> =
        effectMap.diffOfferLifecycle(prev, next, offerObs(t, "x", "x"), "sess-1")

    @Test
    fun `enrich-as-variant emits EvaluateOffer + re-arm + stale-hash cancel but NO OFFER_TIMEOUT or 'offer replaced' bubble`() {
        val prev = region(pending("H1", "P", presentedAt = 1_000L, evaluation = evalOf(), firstEvalLandedAt = 1_100L))
        val next = region(pending("H2", "P", presentedAt = 1_000L, firstEvalLandedAt = 1_100L))
        val fx = diff(prev, next, 2_000L)

        assertFalse(
            "no OFFER_TIMEOUT for an offer that never left presentation",
            fx.any { it is AppEffect.LogEvent && it.event.type == AppEventType.OFFER_TIMEOUT },
        )
        assertFalse(
            "no 'offer replaced' bubble",
            fx.any { it is AppEffect.UpdateBubble && it.text.contains("offer replaced") },
        )
        // The OLD hash's heads-up is dismissed (per-hash notification id → stale banner otherwise
        // strands + a tap on it aborts to manual). The new variant re-posts on its eval-land.
        assertEquals(
            "the stale OLD-hash banner is cancelled (not the new one)",
            listOf("H1"),
            fx.filterIsInstance<AppEffect.CancelOfferNotification>().map { it.offerHash },
        )
        assertTrue("re-evaluates the new variant", fx.any { it is AppEffect.EvaluateOffer })
        val arm = fx.filterIsInstance<AppEffect.ScheduleTimeout>()
            .single { it.payload is ObservationPayload.OfferExpiry }
        assertEquals("re-armed with the new variant's hash", "H2", (arm.payload as ObservationPayload.OfferExpiry).offerHash)
        // Deadline anchored on the ORIGINAL presentedAt (1000 + 120000), not extended by the churn.
        assertEquals("TTL not extended", 1_000L + 120_000L - 2_000L, arm.durationMs)
    }

    @Test
    fun `a genuine replacement (different presentationKey) still emits OFFER_TIMEOUT + cancel + bubble`() {
        val prev = region(pending("H1", "P1", presentedAt = 1_000L, evaluation = evalOf()))
        val next = region(pending("H2", "P2", presentedAt = 2_000L))
        val fx = diff(prev, next, 2_000L)

        assertTrue(
            "the replaced offer resolves OFFER_TIMEOUT",
            fx.any { it is AppEffect.LogEvent && it.event.type == AppEventType.OFFER_TIMEOUT },
        )
        assertTrue("its heads-up is cancelled", fx.any { it is AppEffect.CancelOfferNotification })
        assertTrue(
            "the 'offer replaced' bubble fires",
            fx.any { it is AppEffect.UpdateBubble && it.text.contains("offer replaced") },
        )
        assertTrue("the new offer is evaluated", fx.any { it is AppEffect.EvaluateOffer })
    }

    @Test
    fun `SpeakOffer fires on the FIRST eval landing (marker was null)`() {
        val prev = region(pending("H1", "P", presentedAt = 1_000L, firstEvalLandedAt = null))
        val next = region(pending("H1", "P", presentedAt = 1_000L, evaluation = evalOf(), firstEvalLandedAt = 1_200L))
        val fx = diff(prev, next, 1_200L)
        assertTrue("first landing is spoken", fx.any { it is AppEffect.SpeakOffer })
        assertTrue("and posts the heads-up", fx.any { it is AppEffect.PostOfferNotification })
    }

    @Test
    fun `SpeakOffer does NOT fire when the presentation was already spoken (variant re-eval)`() {
        // The marker was already set (a prior variant spoke) → a re-eval lands but must not re-speak.
        val prev = region(pending("H2", "P", presentedAt = 1_000L, firstEvalLandedAt = 1_200L))
        val next = region(pending("H2", "P", presentedAt = 1_000L, evaluation = evalOf(6.42), firstEvalLandedAt = 1_200L))
        val fx = diff(prev, next, 2_100L)
        assertFalse("no re-speak on a variant re-eval", fx.any { it is AppEffect.SpeakOffer })
        assertTrue("but the heads-up still live-updates", fx.any { it is AppEffect.PostOfferNotification })
    }

    @Test
    fun `the enrich-as-variant timer key is OFFER_EXPIRY scoped to the platform`() {
        val prev = region(pending("H1", "P", presentedAt = 1_000L, evaluation = evalOf(), firstEvalLandedAt = 1_100L))
        val next = region(pending("H2", "P", presentedAt = 1_000L, firstEvalLandedAt = 1_100L))
        val arm = diff(prev, next, 2_000L).filterIsInstance<AppEffect.ScheduleTimeout>()
            .single { it.payload is ObservationPayload.OfferExpiry }
        assertEquals(TimeoutType.OFFER_EXPIRY, arm.type)
        assertEquals(PLATFORM, arm.platform)
    }
}

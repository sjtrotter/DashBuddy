package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.core.state.AppEffect
import cloud.trotter.dashbuddy.core.state.CrossPlatformRegionStepper
import cloud.trotter.dashbuddy.core.state.EffectMap
import cloud.trotter.dashbuddy.core.state.FlowRegionStepper
import cloud.trotter.dashbuddy.core.state.PlatformRegionStepper
import cloud.trotter.dashbuddy.core.state.StateMachine
import cloud.trotter.dashbuddy.core.state.TransitionPolicy
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.evaluation.EvaluationConfig
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluator
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.offer.OfferQuoteBasis
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.OfferSurface
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorDashAddonStateTest {
    private val machine = StateMachine(FlowRegionStepper(), PlatformRegionStepper(), CrossPlatformRegionStepper(), TransitionPolicy(), EffectMap())
    private val evaluator = OfferEvaluator()
    private val config = EvaluationConfig()
    private fun initial() = AppState(regions = Regions(platforms = mapOf(Platform.DoorDash to PlatformRegion(
        platform = Platform.DoorDash, mode = Mode.Online, session = Session("dash", startedAt = 100L),
    ))))
    private fun screen(t: Long, offer: ParsedOffer) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "doordash.screen.offer_popup", metadata = ReplayMetadata.EMPTY,
        flow = Flow.OfferPresented, modeHint = Mode.Online, parsed = ParsedFields.OfferFields(parsedOffer = offer),
    )
    private fun pickup(t: Long) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "doordash.screen.pickup_navigation", metadata = ReplayMetadata.EMPTY,
        flow = Flow.TaskPickupNavigation, modeHint = Mode.Online,
        parsed = ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION, storeName = "H-E-B"),
    )
    private fun evaluationRequest(effects: List<AppEffect>): AppEffect.EvaluateOffer {
        val requests = effects.filterIsInstance<AppEffect.EvaluateOffer>()
        assertEquals(1, requests.size)
        return requests.single()
    }
    private fun eval(t: Long, request: AppEffect.EvaluateOffer): Observation.Loopback {
        val evaluation = evaluator.evaluate(request.parsedOffer, config)
        return Observation.Loopback(
            timestamp = t, effect = Observation.Loopback.EFFECT_OFFER_EVALUATED, targetPlatform = request.platform,
            payload = ObservationPayload.EvaluationResult(evaluation.action.name, request.offerHash, evaluation),
        )
    }
    private fun liveJob(offer: ParsedOffer): AppState {
        val original = offer.copy(offerHash = "original", quoteBasis = OfferQuoteBasis.TOTAL, timeToCompleteMinutes = 30L)
        val presented = machine.step(initial(), screen(1_000L, original))
        val request = evaluationRequest(presented.effects)
        val state = machine.step(presented.newState, eval(2_000L, request)).newState
        return machine.step(state, pickup(3_000L)).newState
    }

    @Test
    fun `recognized increment speaks once across evaluation and countdown then sheet or timeout without phantom accept`() {
        val offer = AddonCardTree.parse()
        for (sheet in listOf(true, false)) {
            var state = liveJob(offer)
            val original = state.regions.platforms.getValue(Platform.DoorDash).activeJob!!
            val effects = mutableListOf<AppEffect>()
            fun step(obs: Observation) {
                val transition = machine.step(state, obs)
                state = transition.newState
                effects += transition.effects
            }
            step(screen(10_000L, offer))
            assertEquals(55_000L, state.regions.platforms.getValue(Platform.DoorDash).pendingOffers.single().countdownExpiresAt)
            val request = evaluationRequest(effects)
            assertEquals(offer, request.parsedOffer)
            assertEquals(offer.offerHash, request.offerHash)
            assertEquals(Platform.DoorDash, request.platform)
            step(eval(10_100L, request))
            val rerender = AddonCardTree.parse(AddonCardTree.card(countdown = "0:44"))
            step(screen(12_000L, rerender))
            assertEquals(56_000L, state.regions.platforms.getValue(Platform.DoorDash).pendingOffers.single().countdownExpiresAt)
            if (sheet) step(Observation.Screen(
                timestamp = 13_000L, captureId = null, ruleId = "doordash.screen.offer_popup_confirm_decline",
                metadata = ReplayMetadata.EMPTY, flow = Flow.OfferPresented, modeHint = Mode.Online,
                parsed = ParsedFields.None, offerSurface = OfferSurface.DECLINE_CONFIRM,
            ))
            step(pickup(14_000L))
            val events = effects.filterIsInstance<AppEffect.LogEvent>().map { it.event.type }
            assertEquals(1, events.count { it == AppEventType.OFFER_RECEIVED })
            assertEquals(1, effects.filterIsInstance<AppEffect.EvaluateOffer>().size)
            val spoken = effects.filterIsInstance<AppEffect.SpeakOffer>()
            assertEquals(1, spoken.size)
            assertEquals(OfferQuoteBasis.INCREMENTAL, spoken.single().evaluation.quoteBasis)
            assertFalse(spoken.single().evaluation.hasDistanceMetrics)
            assertFalse(events.contains(AppEventType.OFFER_ACCEPTED))
            assertEquals(1, events.count { it == if (sheet) AppEventType.OFFER_DECLINED else AppEventType.OFFER_TIMEOUT })
            val region = state.regions.platforms.getValue(Platform.DoorDash)
            assertTrue(region.pendingOffers.isEmpty())
            assertEquals(original.acceptedOffers, region.activeJob!!.acceptedOffers)
            val notifications = effects.filterIsInstance<AppEffect.PostOfferNotification>()
            assertEquals(2, notifications.size)
            assertEquals(listOf(false, true), notifications.map { it.refreshOnly })
            notifications.forEach {
                assertAddonCard(it.offer)
                assertFalse(it.evaluation.hasDistanceMetrics)
            }
        }
    }

    @Test
    fun `explicit accept retains increment once but never parsed delta time or partial blended rates`() {
        val offer = AddonCardTree.parse()
        for (withEvaluation in listOf(true, false)) {
            var state = liveJob(offer)
            val before = state.regions.platforms.getValue(Platform.DoorDash).activeJob!!
            val presented = machine.step(state, screen(10_000L, offer))
            state = presented.newState
            val request = evaluationRequest(presented.effects)
            if (withEvaluation) state = machine.step(state, eval(10_100L, request)).newState
            val click = Observation.Click(
                timestamp = 11_000L, captureId = null, ruleId = "doordash.click.accept_offer", metadata = ReplayMetadata.EMPTY,
                flow = null, modeHint = Mode.Online, parsed = ParsedFields.ClickFields(intent = OfferIntent.ACCEPT),
            )
            state = machine.step(state, click).newState
            state = machine.step(state, pickup(12_000L)).newState
            state = machine.step(state, pickup(13_000L)).newState
            val job = state.regions.platforms.getValue(Platform.DoorDash).activeJob!!
            assertEquals(before.jobId, job.jobId)
            assertEquals(2, job.acceptedOffers.size)
            val accepted = job.acceptedOffers.last()
            assertEquals(OfferQuoteBasis.INCREMENTAL, accepted.quoteBasis)
            assertEquals(10.5, accepted.payAmount!!, 0.0)
            assertEquals(1.5, accepted.distanceMiles!!, 0.0)
            assertNull(accepted.netPay)
            assertNull(accepted.estMinutes)
            assertNull(accepted.handlingMinutes)
            assertNull(accepted.pricedShopItemsPerMinute)
            assertNull(accepted.pricedBasePickupMinutes)
            assertNull(job.blendedNetPay)
            assertNull(job.blendedEstMinutes)
            assertNull(job.liveEstMinutes)
            assertEquals(before.totalPayAmount + 10.5, job.totalPayAmount, 0.0)
        }
    }

    @Test
    fun `incremental quote cannot infer acceptance even with no live return task`() {
        val offer = AddonCardTree.parse()
        val presented = machine.step(initial(), screen(10_000L, offer)).newState
        val transition = machine.step(presented, pickup(12_000L))
        assertFalse(transition.effects.filterIsInstance<AppEffect.LogEvent>().any { it.event.type == AppEventType.OFFER_ACCEPTED })
        assertTrue(transition.newState.regions.platforms.getValue(Platform.DoorDash).activeJob?.acceptedOffers.isNullOrEmpty())
    }

    private fun assertAddonCard(card: FlowCardSnapshot.Offer) {
        assertEquals(OfferQuoteBasis.INCREMENTAL, card.quoteBasis)
        assertEquals(1L, card.incrementalMinutes)
        assertEquals(10.5, card.payAmount!!, 0.0)
        assertEquals(1.5, card.distanceMiles!!, 0.0)
        assertNull(card.netPayAmount)
        assertNull(card.dollarsPerHour)
        assertNull(card.dollarsPerMile)
        assertNull(card.evaluationScore)
    }
}

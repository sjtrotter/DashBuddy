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
            assertEquals(before.tasks.count { it.phase == TaskPhase.DROPOFF } + 1, job.tasks.count { it.phase == TaskPhase.DROPOFF })
            assertEquals(before.tasks.count { it.phase == TaskPhase.PICKUP }, job.tasks.count { it.phase == TaskPhase.PICKUP })
            assertEquals(2, job.acceptedOffers.size)
            val accepted = job.acceptedOffers.last()
            assertEquals(OfferQuoteBasis.INCREMENTAL, accepted.quoteBasis)
            assertEquals(10.5, accepted.payAmount!!, 0.0)
            assertEquals(1.5, accepted.distanceMiles!!, 0.0)
            assertNull(accepted.netPay)
            assertNull(accepted.estMinutes)
            assertNull(accepted.handlingMinutes)
            assertNull(accepted.quotedItemCount)
            assertNull(accepted.offerUnitCount)
            assertNull(accepted.pricedShopItemsPerMinute)
            assertNull(accepted.pricedBasePickupMinutes)
            assertNull(job.blendedNetPay)
            assertNull(job.blendedEstMinutes)
            assertNull(job.liveEstMinutes)
            assertEquals(before.totalPayAmount + 10.5, job.totalPayAmount, 0.0)
        }
    }

    @Test
    fun `incremental accept never carries the units count`() {
        val offer = AddonCardTree.parse().copy(itemCountIsUnits = true)
        var state = liveJob(offer)
        val presented = machine.step(state, screen(10_000L, offer))
        state = machine.step(presented.newState, eval(10_100L, evaluationRequest(presented.effects))).newState
        state = machine.step(state, Observation.Click(
            timestamp = 11_000L, captureId = null, ruleId = "doordash.click.accept_offer", metadata = ReplayMetadata.EMPTY,
            flow = null, modeHint = Mode.Online, parsed = ParsedFields.ClickFields(intent = OfferIntent.ACCEPT),
        )).newState
        state = machine.step(state, pickup(12_000L)).newState
        state = machine.step(state, pickup(13_000L)).newState
        val job = state.regions.platforms.getValue(Platform.DoorDash).activeJob!!
        assertEquals(2, job.acceptedOffers.size)
        assertNull(job.acceptedOffers.last().offerUnitCount)
        assertNull(job.acceptedOffers.last().quotedItemCount)
    }

    @Test
    fun `explicit accept of a different-store add-on mints one pickup and one dropoff`() {
        var state = liveJob(AddonCardTree.parse())
        val before = state.regions.platforms.getValue(Platform.DoorDash).activeJob!!
        // the card lists the OWNED store too (fielded 2026-10-09); the dedup must see H-E-B as owned,
        // so new pickups = [Petsmart] and drops = 2 − 1 = 1
        val offer = AddonCardTree.parse(AddonCardTree.card(
            route = "+2 stops (2.9 mi) • +12 min", merchant = "H-E-B", secondMerchant = "Petsmart",
        ))
        val presented = machine.step(state, screen(10_000L, offer))
        state = machine.step(presented.newState, eval(10_100L, evaluationRequest(presented.effects))).newState
        state = machine.step(state, Observation.Click(
            timestamp = 11_000L, captureId = null, ruleId = "doordash.click.accept_offer", metadata = ReplayMetadata.EMPTY,
            flow = null, modeHint = Mode.Online, parsed = ParsedFields.ClickFields(intent = OfferIntent.ACCEPT),
        )).newState
        state = machine.step(state, pickup(12_000L)).newState
        state = machine.step(state, pickup(13_000L)).newState
        val job = state.regions.platforms.getValue(Platform.DoorDash).activeJob!!
        assertEquals(before.jobId, job.jobId)
        assertEquals(2, job.acceptedOffers.size)
        assertEquals(before.tasks.count { it.phase == TaskPhase.DROPOFF } + 1, job.tasks.count { it.phase == TaskPhase.DROPOFF })
        assertEquals(before.tasks.count { it.phase == TaskPhase.PICKUP } + 1, job.tasks.count { it.phase == TaskPhase.PICKUP })
        assertNull(job.acceptedOffers.last().quotedItemCount)
    }

    @Test
    fun `whole-route card over a job with no pickup tasks still mints the new order's dropoff`() {
        var state = liveJob(AddonCardTree.parse())
        val region = state.regions.platforms.getValue(Platform.DoorDash)
        val activeJob = region.activeJob!!
        state = state.copy(regions = state.regions.copy(platforms = state.regions.platforms + (
            Platform.DoorDash to region.copy(activeJob = activeJob.copy(
                tasks = activeJob.tasks.filter { it.phase == TaskPhase.DROPOFF },
            ))
        )))
        val before = state.regions.platforms.getValue(Platform.DoorDash).activeJob!!
        val offer = AddonCardTree.parse(AddonCardTree.card(
            route = "+2 stops (2.9 mi) • +12 min", merchant = "H-E-B", secondMerchant = "Petsmart",
        ))
        val presented = machine.step(state, screen(10_000L, offer))
        state = machine.step(presented.newState, eval(10_100L, evaluationRequest(presented.effects))).newState
        state = machine.step(state, Observation.Click(
            timestamp = 11_000L, captureId = null, ruleId = "doordash.click.accept_offer", metadata = ReplayMetadata.EMPTY,
            flow = null, modeHint = Mode.Online, parsed = ParsedFields.ClickFields(intent = OfferIntent.ACCEPT),
        )).newState
        state = machine.step(state, pickup(12_000L)).newState
        state = machine.step(state, pickup(13_000L)).newState
        val job = state.regions.platforms.getValue(Platform.DoorDash).activeJob!!
        assertEquals(before.jobId, job.jobId)
        assertEquals(2, job.acceptedOffers.size)
        assertEquals(before.tasks.count { it.phase == TaskPhase.DROPOFF } + 1, job.tasks.count { it.phase == TaskPhase.DROPOFF })
        // Pickup over-mint on a pickup-less job is pre-existing #526 D2 behaviour, out of scope.
    }

    @Test
    fun `an implausible stop count mints one dropoff`() {
        for (stopCount in listOf(99, 0)) {
            var state = liveJob(AddonCardTree.parse())
            val before = state.regions.platforms.getValue(Platform.DoorDash).activeJob!!
            val offer = AddonCardTree.parse(AddonCardTree.card(route = "+2 stops (2.9 mi) • +12 min")).copy(stopCount = stopCount)
            val presented = machine.step(state, screen(10_000L, offer))
            state = machine.step(presented.newState, eval(10_100L, evaluationRequest(presented.effects))).newState
            state = machine.step(state, Observation.Click(
                timestamp = 11_000L, captureId = null, ruleId = "doordash.click.accept_offer", metadata = ReplayMetadata.EMPTY,
                flow = null, modeHint = Mode.Online, parsed = ParsedFields.ClickFields(intent = OfferIntent.ACCEPT),
            )).newState
            state = machine.step(state, pickup(12_000L)).newState
            state = machine.step(state, pickup(13_000L)).newState
            val job = state.regions.platforms.getValue(Platform.DoorDash).activeJob!!
            assertEquals(before.jobId, job.jobId)
            assertEquals(2, job.acceptedOffers.size)
            assertEquals(before.tasks.count { it.phase == TaskPhase.DROPOFF } + 1, job.tasks.count { it.phase == TaskPhase.DROPOFF })
            assertEquals(before.tasks.count { it.phase == TaskPhase.PICKUP }, job.tasks.count { it.phase == TaskPhase.PICKUP })
        }
    }

    @Test
    fun `unparsed stop count mints one dropoff`() {
        var state = liveJob(AddonCardTree.parse())
        val before = state.regions.platforms.getValue(Platform.DoorDash).activeJob!!
        val offer = AddonCardTree.parse(AddonCardTree.card(route = "+2 stops (2.9 mi) • +12 min")).copy(stopCount = null)
        val presented = machine.step(state, screen(10_000L, offer))
        state = machine.step(presented.newState, eval(10_100L, evaluationRequest(presented.effects))).newState
        state = machine.step(state, Observation.Click(
            timestamp = 11_000L, captureId = null, ruleId = "doordash.click.accept_offer", metadata = ReplayMetadata.EMPTY,
            flow = null, modeHint = Mode.Online, parsed = ParsedFields.ClickFields(intent = OfferIntent.ACCEPT),
        )).newState
        state = machine.step(state, pickup(12_000L)).newState
        state = machine.step(state, pickup(13_000L)).newState
        val job = state.regions.platforms.getValue(Platform.DoorDash).activeJob!!
        assertEquals(before.jobId, job.jobId)
        assertEquals(2, job.acceptedOffers.size)
        assertEquals(before.tasks.count { it.phase == TaskPhase.DROPOFF } + 1, job.tasks.count { it.phase == TaskPhase.DROPOFF })
        assertEquals(before.tasks.count { it.phase == TaskPhase.PICKUP }, job.tasks.count { it.phase == TaskPhase.PICKUP })
    }

    @Test
    fun `incremental quote cannot infer acceptance even with no live return task`() {
        val offer = AddonCardTree.parse()
        val presented = machine.step(initial(), screen(10_000L, offer)).newState
        val transition = machine.step(presented, pickup(12_000L))
        assertFalse(transition.effects.filterIsInstance<AppEffect.LogEvent>().any { it.event.type == AppEventType.OFFER_ACCEPTED })
        assertTrue(transition.newState.regions.platforms.getValue(Platform.DoorDash).activeJob?.acceptedOffers.isNullOrEmpty())
    }

    @Test
    fun `confirmed same-store pickup cannot absorb a total-basis add-on`() {
        assertSameStoreTotalAccept(confirmed = true)
    }

    @Test
    fun `open same-store pickup still folds in a total-basis add-on`() {
        assertSameStoreTotalAccept(confirmed = false)
    }

    private fun assertSameStoreTotalAccept(confirmed: Boolean) {
        val offer = AddonCardTree.parse().copy(
            offerHash = "same-store-second", quoteBasis = OfferQuoteBasis.TOTAL,
            orders = AddonCardTree.parse().orders.take(1),
        )
        var state = liveJob(offer)
        state = machine.step(state, pickup(4_000L).copy(
            flow = Flow.TaskPickupArrived,
            parsed = ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.ARRIVED, storeName = "H-E-B"),
        )).newState
        if (confirmed) state = confirmPickup(state)
        val before = state.regions.platforms.getValue(Platform.DoorDash).activeJob!!
        assertEquals(if (confirmed) 5_000L else null, before.tasks.single { it.phase == TaskPhase.PICKUP }.confirmedAt)
        state = machine.step(state, screen(10_000L, offer)).newState
        state = machine.step(state, Observation.Click(
            timestamp = 11_000L, captureId = null, ruleId = "doordash.click.accept_offer", metadata = ReplayMetadata.EMPTY,
            flow = null, modeHint = null, parsed = ParsedFields.ClickFields(intent = OfferIntent.ACCEPT),
        )).newState
        // Consume at the phase-less task edge to isolate add-on placeholder minting from resume.
        state = machine.step(state, Observation.Screen(
            timestamp = 12_000L, captureId = null, ruleId = "doordash.screen.task_active", metadata = ReplayMetadata.EMPTY,
            flow = Flow.TaskActive, modeHint = Mode.Online, parsed = ParsedFields.None,
        )).newState
        val job = state.regions.platforms.getValue(Platform.DoorDash).activeJob!!
        assertEquals(before.jobId, job.jobId)
        assertEquals(2, job.acceptedOffers.size)
        val added = job.tasks.filter { task -> before.tasks.none { it.taskId == task.taskId } }
        val pickups = added.filter { it.phase == TaskPhase.PICKUP }
        assertEquals(if (confirmed) 1 else 0, pickups.size)
        if (confirmed) {
            assertEquals("H-E-B", pickups.single().expectedStoreHint)
            assertNull(pickups.single().arrivedAt)
            assertNull(pickups.single().completedAt)
        }
        assertEquals(1, added.count { it.phase == TaskPhase.DROPOFF })
    }

    private fun confirmPickup(state: AppState): AppState = machine.step(state, Observation.Screen(
        timestamp = 5_000L, captureId = null, ruleId = "doordash.screen.dropoff_navigation", metadata = ReplayMetadata.EMPTY,
        flow = Flow.TaskDropoffNavigation, modeHint = Mode.Online,
        parsed = ParsedFields.TaskFields(phase = TaskPhase.DROPOFF, subFlow = TaskSubFlow.NAVIGATION,
            customerNameHash = "owned-customer", customerAddressHash = "owned-address"),
    )).newState

    @Test
    fun `incremental three stops after confirmed A adds two drops and both revisit A and new B pickups`() {
        val original = AddonCardTree.parse().let { it.copy(orders = it.orders.take(1)) }
        var state = liveJob(original)
        state = machine.step(state, pickup(4_000L).copy(
            flow = Flow.TaskPickupArrived,
            parsed = ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.ARRIVED, storeName = "H-E-B"),
        )).newState
        state = confirmPickup(state)
        val before = state.regions.platforms.getValue(Platform.DoorDash).activeJob!!
        assertEquals(5_000L, before.tasks.single { it.phase == TaskPhase.PICKUP }.confirmedAt)
        val offer = AddonCardTree.parse(AddonCardTree.card(
            route = "+3 stops (2.9 mi) • +12 min", merchant = "H-E-B", secondMerchant = "Petsmart",
        ))
        assertEquals(OfferQuoteBasis.INCREMENTAL, offer.quoteBasis)
        state = machine.step(state, screen(10_000L, offer)).newState
        state = machine.step(state, Observation.Click(
            timestamp = 11_000L, captureId = null, ruleId = "doordash.click.accept_offer", metadata = ReplayMetadata.EMPTY,
            flow = null, modeHint = null, parsed = ParsedFields.ClickFields(intent = OfferIntent.ACCEPT),
        )).newState
        state = machine.step(state, Observation.Screen(
            timestamp = 12_000L, captureId = null, ruleId = "doordash.screen.task_active", metadata = ReplayMetadata.EMPTY,
            flow = Flow.TaskActive, modeHint = Mode.Online, parsed = ParsedFields.None,
        )).newState
        val job = state.regions.platforms.getValue(Platform.DoorDash).activeJob!!
        assertEquals(before.jobId, job.jobId)
        val added = job.tasks.filter { task -> before.tasks.none { it.taskId == task.taskId } }
        assertEquals(2, added.count { it.phase == TaskPhase.DROPOFF })
        val pickups = added.filter { it.phase == TaskPhase.PICKUP }
        assertEquals(2, pickups.size)
        assertEquals(setOf("H-E-B", "Petsmart"), pickups.map { it.expectedStoreHint }.toSet())
        pickups.forEach { assertNull(it.confirmedAt); assertNull(it.arrivedAt) }
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

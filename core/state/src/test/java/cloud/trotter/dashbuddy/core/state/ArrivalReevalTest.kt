package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.evaluation.ArrivalCorrection
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.evaluation.EvaluationConfig
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluator
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import cloud.trotter.dashbuddy.domain.state.AcceptedOfferEconomics
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PickupActivity
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import org.junit.Assert.*
import org.junit.Test

class ArrivalReevalTest {
    private val stepper = PlatformRegionStepper()
    private val flowStepper = FlowRegionStepper()
    private val policy = TransitionPolicy()
    private val effects = EffectMap()
    private val accepted = AcceptedOfferEconomics(
        "offer", netPay = 30.0, estMinutes = 100.0, handlingMinutes = 80.0, isShop = true, acceptedAt = 200L,
    )
    private val task = Task(
        "pickup", "job", TaskPhase.PICKUP, storeName = "Store", activity = PickupActivity.SHOPPING,
        startedAt = 300L, arrivedAt = 400L, subPhase = TaskSubFlow.ARRIVED,
    )
    private val flow = FlowRegion(flow = Flow.TaskPickupArrived, activePlatform = Platform.DoorDash)
    private fun region() = PlatformRegion(
        platform = Platform.DoorDash, mode = Mode.Online,
        session = Session("session", startedAt = 100L),
        activeTask = task,
        activeJob = Job("job", listOf("Store"), "offer", listOf(accepted), listOf(task), startedAt = 200L),
        lastActedFlow = Flow.TaskPickupArrived,
    )
    private fun obs(remaining: Int? = 28, shopped: Int? = 2, timestamp: Long = 1_000L) = Observation.Screen(
        timestamp = timestamp, captureId = null, ruleId = "doordash.screen.pickup_shopping",
        metadata = ReplayMetadata.EMPTY, flow = Flow.TaskPickupArrived, modeHint = Mode.Online,
        parsed = ParsedFields.TaskFields(
            phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.ARRIVED,
            activity = PickupActivity.SHOPPING, storeName = "Store",
            itemsRemaining = remaining, itemsShopped = shopped,
        ),
    )
    private fun step(prev: PlatformRegion, observation: Observation): PlatformRegion {
        val nextFlow = if (observation is Observation.FlowObservation) flowStepper.step(flow, observation) else flow
        return stepper.step(prev, flow, nextFlow, observation, policy)
    }
    private fun diff(prev: PlatformRegion, next: PlatformRegion, observation: Observation): List<AppEffect> {
        fun state(region: PlatformRegion) = AppState(regions = Regions(flow = flow, platforms = mapOf(region.platform to region)))
        return effects.diff(state(prev), state(next), observation)
    }
    private val estimate = ArrivalCorrection.compute(accepted, task.taskId, 30, UserEconomy(), 1_100L)!!
    private fun loopback(jobId: String = "job") = Observation.Loopback(
        timestamp = 1_100L, effect = Observation.Loopback.EFFECT_ARRIVAL_ESTIMATED,
        targetPlatform = Platform.DoorDash, payload = ObservationPayload.ArrivalEstimated(jobId, estimate),
    )

    @Test fun `first coherent frame requests once and subsequent frames never re-arm`() {
        val before = region()
        val observation = obs()
        val after = step(before, observation)
        assertEquals(observation.timestamp, after.activeJob!!.arrivalEstimateRequestedAt)
        val request = diff(before, after, observation).filterIsInstance<AppEffect.EvaluateArrival>().single()
        assertEquals(Platform.DoorDash, request.platform)
        assertEquals("job", request.jobId)
        assertEquals("pickup", request.taskId)
        assertEquals(accepted, request.accepted)
        assertEquals(30, request.observedItems)
        val second = obs(20, 15, 1_050L)
        val again = step(after, second)
        assertEquals(observation.timestamp, again.activeJob!!.arrivalEstimateRequestedAt)
        assertTrue(diff(after, again, second).filterIsInstance<AppEffect.EvaluateArrival>().isEmpty())
    }

    @Test fun `partial frames never combine accumulated counts into a request`() {
        val first = obs(30, null)
        val before = region()
        val partial = step(before, first)
        val second = obs(null, 2, 1_050L)
        val after = step(partial, second)
        assertNull(after.activeJob!!.arrivalEstimateRequestedAt)
        assertTrue(diff(before, partial, first).filterIsInstance<AppEffect.EvaluateArrival>().isEmpty())
        assertTrue(diff(partial, after, second).filterIsInstance<AppEffect.EvaluateArrival>().isEmpty())
        val coherent = obs(28, 2, 1_060L)
        assertEquals(1, diff(after, step(after, coherent), coherent).filterIsInstance<AppEffect.EvaluateArrival>().size)
    }

    @Test fun `stacked accepts and unresolved second pickups refuse revision`() {
        val base = region()
        val job = base.activeJob!!
        for (stack in listOf(
            job.copy(acceptedOffers = listOf(accepted, accepted.copy(offerHash = "addon"))),
            job.copy(tasks = listOf(task, task.copy(taskId = "second", activity = null, storeName = null))),
        )) {
            val before = base.copy(activeJob = stack)
            val after = step(before, obs())
            assertNull(after.activeJob!!.arrivalEstimateRequestedAt)
            assertTrue(diff(before, after, obs()).filterIsInstance<AppEffect.EvaluateArrival>().isEmpty())
        }
    }

    @Test fun `first activation resume and same phase share the request edge`() {
        val base = region()
        val placeholder = task.copy(activity = null, storeName = null, arrivedAt = null)
        for (before in listOf(
            base.copy(activeTask = placeholder, activeJob = base.activeJob!!.copy(tasks = listOf(placeholder))),
            base.copy(activeTask = null, activeJob = base.activeJob!!.copy(tasks = listOf(placeholder))),
            base.copy(activeTask = null, recentTasks = listOf(task)),
        )) {
            val after = step(before, obs())
            assertEquals(1, diff(before, after, obs()).filterIsInstance<AppEffect.EvaluateArrival>().size)
        }
    }

    @Test fun `landing changes only live minutes emits one advisory and no analytics`() {
        val requested = step(region(), obs())
        val landed = step(requested, loopback())
        assertEquals(estimate, landed.activeJob!!.arrivalEstimate)
        assertEquals(requested.activeJob!!.acceptedOffers, landed.activeJob!!.acceptedOffers)
        assertEquals(100.0, landed.activeJob!!.blendedEstMinutes!!, 0.0)
        assertEquals(estimate.correctedEstMinutes, landed.activeJob!!.liveEstMinutes!!, 0.0)
        val emitted = diff(requested, landed, loopback())
        val advisory = emitted.filterIsInstance<AppEffect.UpdateBubble>().single()
        assertEquals(ChatPersona.Dispatcher, advisory.persona)
        assertEquals("session", advisory.sessionId)
        assertTrue(advisory.text.startsWith("Store lists 30 items: this job now runs ≈ "))
        assertTrue(advisory.text.endsWith("/hr. Unassigning may affect your completion rate."))
        assertFalse(advisory.text.contains("offer said"))
        assertTrue(emitted.filterIsInstance<AppEffect.LogEvent>().isEmpty())
        assertTrue(diff(landed, step(landed, loopback()), loopback()).filterIsInstance<AppEffect.UpdateBubble>().isEmpty())
    }

    @Test fun `advisory omits unknown hourly and includes known quoted count`() {
        val before = step(region(), obs())
        val arrival = estimate.copy(quotedItems = 64, correctedDollarsPerHour = null)
        val after = before.copy(activeJob = before.activeJob!!.copy(arrivalEstimate = arrival))
        assertEquals(
            "Store lists 30 items (offer said 64). Unassigning may affect your completion rate.",
            effects.diffTask(before, after, loopback()).filterIsInstance<AppEffect.UpdateBubble>().single().text,
        )
    }

    @Test fun `stale unsolicited and closed-job loopbacks are inert`() {
        val requested = step(region(), obs())
        assertEquals(requested, step(requested, loopback("other-job")))
        assertEquals(region(), step(region(), loopback()))
        val closed = requested.copy(activeJob = null, activeTask = null)
        assertEquals(closed, step(closed, loopback()))
    }

    @Test fun `add-on clears both anchors and makes late loopback inert`() {
        val requested = step(region(), obs())
        val landed = step(requested, loopback())
        val addon = AcceptInputs("addon", accepted.copy(offerHash = "addon"), listOf("Store"), 1)
        for (before in listOf(requested, landed)) {
            val after = stepper.consumeAcceptIntoJob(before, obs(timestamp = 1_200L), addon)
            assertEquals(2, after.activeJob!!.acceptedOffers.size)
            assertNull(after.activeJob!!.arrivalEstimate)
            assertNull(after.activeJob!!.arrivalEstimateRequestedAt)
            assertEquals(200.0, after.activeJob!!.liveEstMinutes!!, 0.0)
            assertEquals(after, landArrivalEstimate(after, loopback()))
            assertTrue(diff(after, step(after, obs(timestamp = 1_300L)), obs(timestamp = 1_300L))
                .filterIsInstance<AppEffect.EvaluateArrival>().isEmpty())
        }
    }
    @Test fun `accept captures handling provenance and shopping classification`() {
        val parsed = ParsedOffer(
            offerHash = "units-offer", payAmount = 30.0, distanceMiles = 8.0,
            itemCount = 64, itemCountIsUnits = true,
            orders = listOf(ParsedOrder(
                orderIndex = 0, orderType = OrderType.SHOP_FOR_ITEMS, storeName = "Store",
                itemCount = 64, isItemCountEstimated = false, badges = emptySet(),
            )),
        )
        val evaluation = OfferEvaluator().evaluate(parsed, EvaluationConfig())
        val pending = PendingOffer(
            parsed.offerHash, ParsedFields.OfferFields(parsedOffer = parsed), presentedAt = 100L,
            evaluation = evaluation, returnFlow = Flow.Idle,
        )
        val economics = stepper.acceptInputsFromPending(pending, 200L).economics
        assertEquals(evaluation.handlingMinutes, economics.handlingMinutes)
        assertEquals(evaluation.nonShopLegs, economics.nonShopLegs)
        assertEquals(evaluation.estimatedTimeMinutes, economics.estMinutes!!, 0.0)
        assertEquals(64, economics.offerUnitCount)
        assertTrue(economics.isShop)
        val withoutEvaluation = stepper.acceptInputsFromPending(pending.copy(evaluation = null), 200L).economics
        assertNull(withoutEvaluation.handlingMinutes)
        assertTrue(withoutEvaluation.isShop)
    }

}

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
        quotedItemCount = 64,
        pricedShopItemsPerMinute = UserEconomy().effectiveShopItemsPerMinute,
        pricedBasePickupMinutes = UserEconomy().basePickupMinutes,
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
    private fun loopback(jobId: String = "job", requestedAt: Long? = 1_000L) = Observation.Loopback(
        timestamp = 1_100L, effect = Observation.Loopback.EFFECT_ARRIVAL_ESTIMATED,
        targetPlatform = Platform.DoorDash, payload = ObservationPayload.ArrivalEstimated(jobId, estimate, requestedAt),
    )

    @Test fun `a result for a SUPERSEDED request is inert - the fresh latch owns the answer (Astra r2)`() {
        // Recovery cleared the first request; a fresh frame re-latched at 5_000. A replayed result for the OLD
        // request (1_000) arrives first and must not land; the result for the fresh request does.
        val fresh = region().copy(activeJob = region().activeJob!!.copy(arrivalEstimateRequestedAt = 5_000L, arrivalEstimateObservedItems = 35))
        val stale = step(fresh, loopback(requestedAt = 1_000L))
        assertNull(stale.activeJob!!.arrivalEstimate)
        val landed = step(fresh, loopback(requestedAt = 5_000L))
        assertEquals(estimate, landed.activeJob!!.arrivalEstimate)
        assertNull("no requestedAt at all (pre-fix payload) never lands", step(fresh, loopback(requestedAt = null)).activeJob!!.arrivalEstimate)
    }

    @Test fun `first coherent frame requests once and subsequent frames never re-arm`() {
        val before = region()
        val observation = obs()
        val after = step(before, observation)
        assertEquals(observation.timestamp, after.activeJob!!.arrivalEstimateRequestedAt)
        assertEquals(30, after.activeJob!!.arrivalEstimateObservedItems)
        val request = diff(before, after, observation).filterIsInstance<AppEffect.EvaluateArrival>().single()
        assertEquals(Platform.DoorDash, request.platform)
        assertEquals("job", request.jobId)
        assertEquals("pickup", request.taskId)
        assertEquals(accepted, request.accepted)
        assertEquals(30, request.observedItems)
        val second = obs(20, 15, 1_050L)
        val again = step(after, second)
        assertEquals(observation.timestamp, again.activeJob!!.arrivalEstimateRequestedAt)
        assertEquals(30, again.activeJob!!.arrivalEstimateObservedItems)
        assertTrue(diff(after, again, second).filterIsInstance<AppEffect.EvaluateArrival>().isEmpty())
    }

    @Test fun `the request edge reads the observed count from STATE`() {
        val before = region()
        val after = before.copy(activeJob = before.activeJob!!.copy(
            arrivalEstimateRequestedAt = 1_000L,
            arrivalEstimateObservedItems = 47,
        ))
        val request = diff(before, after, loopback()).filterIsInstance<AppEffect.EvaluateArrival>().single()
        assertEquals(47, request.observedItems)
        assertEquals(accepted, request.accepted)
        assertEquals(task.taskId, request.taskId)
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
        assertTrue(advisory.text.startsWith("Store lists 30 items (offer said 64): this job now runs ≈ "))
        assertTrue(advisory.text.endsWith("/hr. Unassigning may affect your completion rate."))
        assertEquals(accepted.quotedItemCount, landed.activeJob!!.arrivalEstimate!!.quotedItems)
        assertTrue(emitted.filterIsInstance<AppEffect.LogEvent>().isEmpty())
        assertTrue(diff(landed, step(landed, loopback()), loopback()).filterIsInstance<AppEffect.UpdateBubble>().isEmpty())
    }

    @Test fun `no advisory when the store lists exactly the quoted count (#1291)`() {
        val requested = step(region(), obs(remaining = 62, shopped = 2))
        val same = ArrivalCorrection.compute(accepted, task.taskId, 64, UserEconomy(), 1_100L)!!
        val result = loopback().copy(payload = ObservationPayload.ArrivalEstimated("job", same, requestedAt = 1_000L))
        val landed = step(requested, result)
        assertEquals(64, same.quotedItems)
        assertEquals("the estimate still lands (display-only state)", same, landed.activeJob!!.arrivalEstimate)
        assertTrue("an unchanged count says nothing new", diff(requested, landed, result).filterIsInstance<AppEffect.UpdateBubble>().isEmpty())
    }

    @Test fun `the advisory still speaks when the offer quoted no count (#1291)`() {
        val unquoted = accepted.copy(quotedItemCount = null)
        val base = region().let { it.copy(activeJob = it.activeJob!!.copy(acceptedOffers = listOf(unquoted))) }
        val requested = step(base, obs())
        val arrival = ArrivalCorrection.compute(unquoted, task.taskId, 30, UserEconomy(), 1_100L)!!
        val result = loopback().copy(payload = ObservationPayload.ArrivalEstimated("job", arrival, requestedAt = 1_000L))
        val landed = step(requested, result)
        assertNull(arrival.quotedItems)
        val advisory = diff(requested, landed, result).filterIsInstance<AppEffect.UpdateBubble>().single()
        assertTrue("no quote to compare, so no '(offer said …)'", advisory.text.startsWith("Store lists 30 items: this job now runs ≈ "))
    }

    @Test fun `no advisory is emitted when the landed estimate has no hourly`() {
        val noNetPay = accepted.copy(netPay = null)
        val base = region().let { it.copy(activeJob = it.activeJob!!.copy(acceptedOffers = listOf(noNetPay))) }
        val before = step(base, obs())
        val arrival = ArrivalCorrection.compute(noNetPay, task.taskId, 30, UserEconomy(), 1_100L)!!
        val result = loopback().copy(payload = ObservationPayload.ArrivalEstimated("job", arrival, requestedAt = 1_000L))
        val after = step(before, result)
        assertNull(arrival.correctedDollarsPerHour)
        assertEquals(noNetPay.quotedItemCount, arrival.quotedItems)
        assertEquals(arrival, after.activeJob!!.activeArrivalEstimate)
        assertEquals(arrival.correctedEstMinutes, after.activeJob!!.liveEstMinutes!!, 0.0)
        assertTrue(diff(before, after, result).filterIsInstance<AppEffect.UpdateBubble>().isEmpty())
    }

    @Test fun `a second pickup activating after the landing stops the estimate being served`() {
        val landed = step(step(region(), obs()), loopback())
        val job = landed.activeJob!!
        assertEquals(estimate, job.activeArrivalEstimate)
        val secondPickup = task.copy(taskId = "second-pickup", storeName = "Second Store")
        val stacked = job.copy(tasks = job.tasks + secondPickup)
        assertNull(stacked.activeArrivalEstimate)
        assertEquals(stacked.blendedEstMinutes, stacked.liveEstMinutes)
        assertEquals(estimate, stacked.arrivalEstimate)
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
            assertNull(after.activeJob!!.arrivalEstimateObservedItems)
            assertEquals(200.0, after.activeJob!!.liveEstMinutes!!, 0.0)
            assertEquals(after, landArrivalEstimate(after, loopback()))
            assertTrue(diff(after, step(after, obs(timestamp = 1_300L)), obs(timestamp = 1_300L))
                .filterIsInstance<AppEffect.EvaluateArrival>().isEmpty())
        }
    }
    @Test fun `a replacement job on the same frame emits its OWN request (Astra r1 P2)`() {
        // Job A latched at 1000; a close-and-mint step replaces it with job B whose own request latches at 2000.
        val a = region().copy(activeJob = region().activeJob!!.copy(
            arrivalEstimateRequestedAt = 1_000L, arrivalEstimateObservedItems = 30,
        ))
        val jobB = region().activeJob!!.copy(
            jobId = "job-b", arrivalEstimateRequestedAt = 2_000L, arrivalEstimateObservedItems = 35,
        )
        val b = region().copy(activeJob = jobB, activeTask = task.copy(jobId = "job-b"))
        val requests = diff(a, b, obs(timestamp = 2_000L)).filterIsInstance<AppEffect.EvaluateArrival>()
        assertEquals(1, requests.size)
        assertEquals("job-b", requests.single().jobId)
        assertEquals(35, requests.single().observedItems)
    }

    @Test fun `a frame carrying the pair but no shopping activity does not consume the latch (Astra r1 P2)`() {
        val prev = region() // the task's ACCUMULATED activity is SHOPPING
        val frame = obs().let { it.copy(parsed = (it.parsed as ParsedFields.TaskFields).copy(activity = null)) }
        val next = step(prev, frame)
        assertNull(next.activeJob!!.arrivalEstimateRequestedAt)
        // the next frame WITH shopping activity still latches
        assertEquals(1_000L, step(next, obs()).activeJob!!.arrivalEstimateRequestedAt)
    }

    @Test fun `recovery hygiene drops an UNANSWERED request and keeps a landed estimate (Astra r1 P2)`() {
        fun state(region: PlatformRegion) = AppState(timestamp = 1_500L, regions = Regions(flow = flow, platforms = mapOf(region.platform to region)))
        val pending = step(region(), obs())
        val cleaned = state(pending).recoveryHygiene(nowMs = 9_000L).regions.platforms.getValue(Platform.DoorDash)
        assertNull("the next coherent frame must be able to re-ask", cleaned.activeJob!!.arrivalEstimateRequestedAt)
        assertNull(cleaned.activeJob!!.arrivalEstimateObservedItems)
        val reasked = step(cleaned, obs(timestamp = 9_100L))
        assertEquals(1, diff(cleaned, reasked, obs(timestamp = 9_100L)).filterIsInstance<AppEffect.EvaluateArrival>().size)
        val landed = step(pending, loopback())
        val kept = state(landed).recoveryHygiene(nowMs = 9_000L).regions.platforms.getValue(Platform.DoorDash)
        assertEquals(1_000L, kept.activeJob!!.arrivalEstimateRequestedAt)
        assertEquals(30, kept.activeJob!!.arrivalEstimateObservedItems)
        assertEquals(estimate, kept.activeJob!!.arrivalEstimate)
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
        assertEquals(evaluation.pricedShopItemsPerMinute, economics.pricedShopItemsPerMinute)
        assertEquals(evaluation.pricedBasePickupMinutes, economics.pricedBasePickupMinutes)
        assertEquals(evaluation.estimatedTimeMinutes, economics.estMinutes!!, 0.0)
        assertEquals(64, economics.offerUnitCount)
        assertEquals(64, economics.quotedItemCount)
        assertTrue(economics.isShop)
        val withoutEvaluation = stepper.acceptInputsFromPending(pending.copy(evaluation = null), 200L).economics
        assertNull(withoutEvaluation.handlingMinutes)
        assertNull(withoutEvaluation.quotedItemCount)
        assertNull(withoutEvaluation.pricedShopItemsPerMinute)
        assertNull(withoutEvaluation.pricedBasePickupMinutes)
        assertTrue(withoutEvaluation.isShop)
    }

}

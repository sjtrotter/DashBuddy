package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.core.state.model.RegionDecisions
import cloud.trotter.log.gig.core.state.model.StepDecisions
import cloud.trotter.log.gig.core.state.model.Transition
import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryPayload
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryReceiptRepricePayload
import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.model.pay.ParsedPay
import cloud.trotter.log.gig.domain.model.pay.ParsedPayItem
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.ObservationPayload
import cloud.trotter.log.gig.domain.pipeline.TimeoutType
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.ClosedJobReceipt
import cloud.trotter.log.gig.domain.state.DestructiveKind
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.FlowRegion
import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.JobReceiptAnchors
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.OfferIntent
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PendingDestructive
import cloud.trotter.log.gig.domain.state.PendingOffer
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.ReceiptCoverage
import cloud.trotter.log.gig.domain.state.Regions
import cloud.trotter.log.gig.domain.state.Session
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1423: the step owns decisions; effects consume them and snapshots never retain them. */
class StepDecisionsTest {
    private val platform = Platform.DoorDash
    private val other = Platform.Instacart
    private val machine = StateMachine(
        FlowRegionStepper(), PlatformRegionStepper(), CrossPlatformRegionStepper(),
        TransitionPolicy(), EffectMap(),
    )
    private val drop = Task(
        taskId = "d1", jobId = "J1", phase = TaskPhase.DROPOFF,
        storeName = "Store", customerNameHash = "customer-1", startedAt = 100L, arrivedAt = 500L,
    )
    private val retire = PendingDestructive(
        DestructiveKind.TASK_RETIRE, since = 1_000L, deadline = 11_000L,
        armedFromFlow = Flow.Idle, wakeId = 1L,
    )
    private val receipt = ParsedPay(appPayComponents = listOf(ParsedPayItem("Base Pay", 12.0)), customerTips = emptyList())

    private fun region() = PlatformRegion(
        platform = platform, mode = Mode.Online, session = Session("S1", startedAt = 0L),
        activeJob = Job("J1", offerStoreHint = listOf("Store"), parentOfferHash = null,
            startedAt = 100L, tasks = listOf(drop)),
        activeTask = drop, lastActedFlow = Flow.Idle, pendingDestructive = retire, wakeSeq = 1L,
    )

    private fun state(region: PlatformRegion) = AppState(regions = Regions(
        flow = FlowRegion(flow = region.lastActedFlow ?: Flow.Idle, activePlatform = platform),
        platforms = mapOf(platform to region, other to PlatformRegion(other)),
    ))

    private fun screen(
        flow: Flow, at: Long, parsed: ParsedFields = ParsedFields.None, mode: Mode = Mode.Online,
    ) = Observation.Screen(
        timestamp = at, captureId = "capture-$at", ruleId = "${platform.wire}.screen.synthetic",
        metadata = ReplayMetadata.EMPTY, flow = flow, modeHint = mode, parsed = parsed,
    )

    private fun wake(pending: PendingDestructive) = Observation.Timeout(
        timestamp = pending.deadline, type = TimeoutType.GRACE_COMMIT, targetPlatform = platform,
        payload = ObservationPayload.GraceWake(pending.wakeId),
    )

    private fun Transition.region() = newState.regions.platforms.getValue(platform)
    private fun Transition.decided() = decisions.of(platform)
    private fun Transition.deliveries() = effects.filterIsInstance<AppEffect.LogEvent>()
        .filter { it.event.type == AppEventType.DELIVERY_COMPLETED }
        .map { it.event.payload as DeliveryPayload }

    private fun assertRetired(transition: Transition) {
        assertEquals(drop.taskId, transition.decided().retiredTaskId)
        assertEquals(listOf(drop.taskId), transition.deliveries().map { it.taskId })
        assertEquals(RegionDecisions.NONE, transition.decisions.of(other))
    }

    private fun absorbed() = region().copy(
        mode = Mode.Offline, lastActedFlow = Flow.SessionEnded, wakeSeq = 2L,
        pendingDestructive = PendingDestructive(
            DestructiveKind.SESSION_END, since = 2_000L, deadline = 11_000L,
            authoritative = true, absorbedRetireSince = retire.since,
            absorbedRetireDeadline = retire.deadline, wakeId = 2L,
        ),
    )

    @Test
    fun `reprice is returned and emitted but never persisted or carried into another step`() {
        val closed = region().copy(
            activeJob = null, activeTask = null, pendingDestructive = null,
            recentTasks = listOf(drop.copy(completedAt = 1_000L)), lastActedFlow = Flow.PostTask,
            lastAnnouncedPostTaskTaskId = drop.taskId,
            lastClosedJobReceipt = ClosedJobReceipt(jobId = drop.jobId, receiptSeenAt = 1_000L),
            jobReceiptAnchors = JobReceiptAnchors(jobId = drop.jobId, firstEnteredAt = 1_000L),
        )
        val before = state(closed)
        val obs = screen(Flow.PostTask, 2_000L,
            ParsedFields.PostTaskFields(totalPay = receipt.total, parsedPay = receipt, isExpanded = true))
        val transition = machine.step(before, obs)
        val decision = requireNotNull(transition.decided().receiptReprice)
        assertEquals(drop.jobId, decision.jobId)
        assertEquals(receipt, decision.parsedPay)
        assertEquals(mapOf(drop.taskId to 12.0), decision.shares)
        assertEquals(obs.timestamp, decision.decidedAt)
        assertEquals(obs.captureId, decision.sourceCaptureId)
        assertEquals(1, decision.revision)
        val emitted = transition.effects.filterIsInstance<AppEffect.LogEvent>()
            .single { it.event.type == AppEventType.DELIVERY_RECEIPT_REPRICE }
        assertEquals(decision.decidedAt, emitted.event.occurredAt)
        val payload = emitted.event.payload as DeliveryReceiptRepricePayload
        assertEquals(decision.parsedPay, payload.parsedPay)
        assertEquals(decision.shares.getValue(drop.taskId), payload.dropRealizedPay, 0.0)
        assertEquals("log:DELIVERY_RECEIPT_REPRICE:d1:J1:r1", emitted.effectKey)
        assertEquals(receipt, transition.region().lastClosedJobReceipt!!.lastDecidedPay)
        // The durable marker remains, but no re-price carrier exists in the serialized region.
        val serialized = StateJson.encodeToString(PlatformRegion.serializer(), transition.region())
        assertFalse(serialized.contains("pendingReceiptReprice"))
        assertEquals(transition.region(), StateJson.decodeFromString<PlatformRegion>(serialized))
        assertEquals(RegionDecisions.NONE, transition.decisions.of(other))
        assertSame(before.regions.platforms[other], transition.newState.regions.platforms[other])
        val next = machine.step(transition.newState, screen(Flow.Idle, 3_000L))
        assertEquals(StepDecisions.NONE, next.decisions)
        assertTrue(next.effects.filterIsInstance<AppEffect.LogEvent>()
            .none { it.event.type == AppEventType.DELIVERY_RECEIPT_REPRICE })
        // Journal replay reconstructs both decisions and the ordered effect payloads.
        assertEquals(transition, machine.step(before, obs))
    }

    @Test
    fun `same-step expansion replaces the cached close-time reprice`() {
        val cached = ParsedFields.PostTaskFields(totalPay = receipt.total, parsedPay = receipt, isExpanded = true)
        val before = region().copy(
            lastActedFlow = Flow.PostTask, lastAnnouncedPostTaskTaskId = drop.taskId,
            jobReceiptAnchors = JobReceiptAnchors(jobId = drop.jobId, firstEnteredAt = 1_000L),
            lastPostTaskFields = cached, lastPostTaskCoverage = ReceiptCoverage(setOf(drop.taskId)),
        )
        val fresher = ParsedPay(appPayComponents = listOf(ParsedPayItem("Base Pay", 15.0)), customerTips = emptyList())
        val result = machine.step(state(before), screen(Flow.PostTask, retire.deadline + 1,
            ParsedFields.PostTaskFields(totalPay = fresher.total, parsedPay = fresher, isExpanded = true)))
        val decision = requireNotNull(result.decided().receiptReprice)
        assertEquals(2, decision.revision)
        assertEquals(fresher, decision.parsedPay)
        assertEquals(drop.taskId, result.decided().retiredTaskId)
        val logs = result.effects.filterIsInstance<AppEffect.LogEvent>()
        val reprice = logs.single { it.event.type == AppEventType.DELIVERY_RECEIPT_REPRICE }
        assertEquals("log:DELIVERY_RECEIPT_REPRICE:d1:J1:r2", reprice.effectKey)
        assertEquals(fresher, (reprice.event.payload as DeliveryReceiptRepricePayload).parsedPay)
        assertTrue(logs.indexOfFirst { it.event.type == AppEventType.DELIVERY_COMPLETED } < logs.indexOf(reprice))
    }

    @Test
    fun `T1 retire commit returns the retired task and the next step is empty`() {
        val result = machine.step(state(region()), wake(retire))
        assertRetired(result)
        assertNull(result.region().activeJob)
        assertEquals(retire.since, result.region().recentTasks.single().completedAt)
        assertEquals(StepDecisions.NONE,
            machine.step(result.newState, screen(Flow.Idle, retire.deadline + 1)).decisions)
    }

    @Test
    fun `T2 accept commits the retire before its deadline`() {
        val offer = ParsedOffer(offerHash = "new-offer", payAmount = 7.0)
        val before = region().copy(pendingOffers = listOf(PendingOffer(
            offerHash = offer.offerHash, offerFields = ParsedFields.OfferFields(parsedOffer = offer),
            presentedAt = 1_500L, returnFlow = Flow.Idle, lastClickIntent = OfferIntent.ACCEPT,
            acceptClickAt = 1_800L, acceptedAt = 1_800L,
        )))
        val result = machine.step(state(before), screen(Flow.TaskActive, 2_000L))
        assertRetired(result)
        assertNotNull(result.region().activeJob)
        assertNotEquals(drop.jobId, result.region().activeJob!!.jobId)
        assertEquals(retire.since, result.region().recentTasks.single { it.taskId == drop.taskId }.completedAt)
    }

    @Test
    fun `honored teardown returns the retire and preserves the honor history`() {
        val before = absorbed()
        val result = machine.step(state(before), wake(before.pendingDestructive!!))
        assertRetired(result)
        assertNull(result.region().session)
        val completed = result.region().recentTasks.single { it.taskId == drop.taskId }
        assertEquals(retire.since, completed.completedAt)
        assertEquals(retire.since, completed.honoredRetireAt)
    }

    @Test
    fun `immature teardown returns no retired task`() {
        val result = machine.step(state(absorbed()), screen(Flow.Idle, 3_000L,
            ParsedFields.IdleFields(startingSession = true), mode = Mode.Offline))
        assertNull(result.region().session)
        assertNull(result.decided().retiredTaskId)
        assertNull(result.region().recentTasks.single().honoredRetireAt)
        assertTrue(result.deliveries().isEmpty())
    }

    @Test
    fun `unassign disowns an absorbed retire both inside the window and on its commit frame`() {
        for (at in listOf(3_000L, 11_001L)) {
            val result = machine.step(state(absorbed()), screen(Flow.TaskUnassigned, at))
            assertNull(result.decided().retiredTaskId)
            assertTrue(result.deliveries().isEmpty())
            val task = (result.region().recentTasks + listOfNotNull(result.region().activeTask))
                .single { it.taskId == drop.taskId }
            assertEquals(at, task.unassignedAt)
            assertNull(task.honoredRetireAt)
            result.region().pendingDestructive?.let { pending ->
                val ended = machine.step(result.newState, wake(pending))
                assertNull(ended.decided().retiredTaskId)
                assertTrue(ended.deliveries().isEmpty())
            }
        }
    }

    @Test
    fun `unassign supersedes an overdue T1 retire without a retired-task decision`() {
        val result = machine.step(state(region()), screen(Flow.TaskUnassigned, retire.deadline + 1))
        assertNull(result.decided().retiredTaskId)
        assertTrue(result.deliveries().isEmpty())
        val task = result.region().recentTasks.single { it.taskId == drop.taskId }
        assertEquals(retire.deadline + 1, task.unassignedAt)
        assertNull(task.completedAt)
    }

    @Test
    fun `unknown platform observation returns no decisions`() {
        val result = machine.step(state(region()), Observation.Timeout(
            timestamp = retire.deadline + 1, type = TimeoutType.GRACE_COMMIT,
            targetPlatform = Platform.Unknown,
        ))
        assertEquals(StepDecisions.NONE, result.decisions)
        assertEquals(drop, result.region().activeTask)
    }
}

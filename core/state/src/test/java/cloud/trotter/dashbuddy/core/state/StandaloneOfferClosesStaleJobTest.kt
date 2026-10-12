package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.TaskUnassignedPayload
import cloud.trotter.dashbuddy.domain.model.offer.OfferQuoteBasis
import cloud.trotter.dashbuddy.domain.model.offer.OfferScope
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AcceptedOfferEconomics
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1119 slice 1: scope controls stale-job abandonment independently of quote economics. */
class StandaloneOfferClosesStaleJobTest {
    private val platform = Platform.DoorDash
    private val stepper = PlatformRegionStepper()
    private val machine = StateMachine(
        FlowRegionStepper(), stepper, CrossPlatformRegionStepper(), TransitionPolicy(), EffectMap(),
    )
    private val pickup = Task("old-pickup", "old-job", TaskPhase.PICKUP,
        storeName = "Old Store", startedAt = 100L, arrivedAt = 150L, confirmedAt = 200L, completedAt = 200L)
    private val dropoff = Task("old-dropoff", "old-job", TaskPhase.DROPOFF, startedAt = 100L)
    private val offer = ParsedOffer(offerHash = "new-offer", payAmount = 7.0,
        distanceMiles = 3.0, timeToCompleteMinutes = 12L)
    private val frame = Observation.Screen(
        timestamp = 1_000L, captureId = null, ruleId = "${platform.wire}.screen.synthetic",
        metadata = ReplayMetadata.EMPTY, flow = Flow.TaskPickupNavigation, modeHint = Mode.Online,
        parsed = ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION,
            storeName = "New Store"),
    )

    private fun pending(parsed: ParsedOffer = offer) = PendingOffer(
        offerHash = parsed.offerHash, offerFields = ParsedFields.OfferFields(parsedOffer = parsed),
        presentedAt = 800L, returnFlow = Flow.TaskDropoffNavigation,
        lastClickIntent = OfferIntent.ACCEPT, acceptClickAt = 900L, acceptedAt = 900L,
    )

    private fun initial(
        parsed: ParsedOffer = offer,
        delivered: Boolean = false,
        destructive: PendingDestructive? = null,
    ): AppState {
        val drop = if (delivered) dropoff.copy(
            customerNameHash = "old-customer", arrivedAt = 300L, completedAt = 400L,
        ) else dropoff
        return AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.Idle, activePlatform = platform),
            platforms = mapOf(platform to PlatformRegion(
                platform = platform, mode = Mode.Online, session = Session("dash", startedAt = 0L),
                activeJob = Job("old-job", offerStoreHint = listOf("Old Store"), parentOfferHash = "old-offer",
                    startedAt = 100L, tasks = listOf(pickup, drop),
                    acceptedOffers = listOf(AcceptedOfferEconomics(
                        offerHash = "old-offer", payAmount = 20.0, distanceMiles = 9.0, acceptedAt = 100L,
                    ))),
                recentTasks = listOf(pickup) + if (delivered) listOf(drop) else emptyList(),
                pendingOffers = listOf(pending(parsed)), pendingDestructive = destructive,
            )),
        ))
    }

    private fun region(state: AppState) = state.regions.platforms.getValue(platform)

    private fun unassignedEffects(effects: List<AppEffect>) = effects.filterIsInstance<AppEffect.LogEvent>()
        .filter { it.event.type == AppEventType.TASK_UNASSIGNED }

    /**
     * [viaStep]: the region came out of `StateMachine.step`, where `closeReceiptRepriceWindow` clears the
     * closed-job receipt marker of a same-step close-then-mint (#1033, exactly as the #596 T2 path). A direct
     * `consumeAcceptIntoJob` call stops before that hook, so the marker still names the abandoned job.
     */
    private fun assertFresh(after: PlatformRegion, viaStep: Boolean = true) {
        val fresh = requireNotNull(after.activeJob)
        assertNotEquals("old-job", fresh.jobId)
        if (viaStep) assertNull(after.lastClosedJobReceipt) else assertEquals("old-job", after.lastClosedJobReceipt?.jobId)
        assertEquals("new-offer", fresh.parentOfferHash)
        assertEquals(listOf("new-offer"), fresh.acceptedOffers.map { it.offerHash })
        assertEquals(listOf(7.0), fresh.acceptedOffers.map { it.payAmount })
        assertEquals(listOf(3.0), fresh.acceptedOffers.map { it.distanceMiles })
        assertEquals(listOf(12.0), fresh.acceptedOffers.map { it.estMinutes })
        assertTrue(fresh.tasks.none { it.jobId == "old-job" })
    }

    private fun assertAbandoned(after: PlatformRegion, viaStep: Boolean = true) {
        assertFresh(after, viaStep)
        val abandoned = after.recentTasks.single { it.taskId == dropoff.taskId }
        assertEquals(frame.timestamp, abandoned.unassignedAt)
        assertNull(abandoned.completedAt)
        assertNull(abandoned.arrivedAt)
        assertEquals(pickup, after.recentTasks.single { it.taskId == pickup.taskId })
    }

    @Test
    fun `standalone offer over a live job abandons it and mints fresh`() {
        val result = machine.step(initial(), frame)
        assertAbandoned(region(result.newState))
        val unassigned = unassignedEffects(result.effects)
        assertEquals(1, unassigned.size)
        assertEquals(dropoff.taskId, (unassigned.single().event.payload as TaskUnassignedPayload).taskId)
    }

    @Test
    fun `add-on offer over a live job appends`() {
        val parsed = offer.copy(offerScope = OfferScope.ADD_ON, quoteBasis = OfferQuoteBasis.INCREMENTAL, stopCount = 1)
        val result = machine.step(initial(parsed), frame)
        val after = region(result.newState)
        val job = requireNotNull(after.activeJob)
        assertEquals("old-job", job.jobId)
        assertEquals(2, job.acceptedOffers.size)
        assertEquals(listOf("old-offer", "new-offer"), job.acceptedOffers.map { it.offerHash })
        assertNull(after.lastClosedJobReceipt)
        assertTrue(unassignedEffects(result.effects).isEmpty())
    }

    @Test
    fun `unparsed accept over a live job keeps the append path`() {
        val before = region(initial()).copy(pendingOffers = emptyList())
        val inputs = stepper.acceptInputsFromPending(null, acceptedAt = 900L)
        assertNull(inputs.offerScope)
        val after = stepper.consumeAcceptIntoJob(before, frame, inputs)
        assertEquals("old-job", after.activeJob?.jobId)
        assertEquals(2, after.activeJob?.acceptedOffers?.size)
        assertNull(after.lastClosedJobReceipt)
        assertEquals(before.recentTasks, after.recentTasks)
        assertTrue(requireNotNull(after.activeJob).tasks.all { it.unassignedAt == null })
    }

    @Test
    fun `standalone offer over a physically complete job still takes the #596 close+mint path`() {
        val before = initial(delivered = true)
        val result = machine.step(before, frame)
        val after = region(result.newState)
        assertFresh(after)
        assertEquals(region(before).recentTasks, after.recentTasks)
        assertTrue((after.recentTasks + requireNotNull(after.activeJob).tasks).all { it.unassignedAt == null })
        assertTrue(unassignedEffects(result.effects).isEmpty())
    }

    @Test
    fun `pending TASK_RETIRE over a not-complete job is cleared by the standalone close`() {
        val destructive = PendingDestructive(
            kind = DestructiveKind.TASK_RETIRE, since = 800L, deadline = 10_800L,
            armedFromFlow = Flow.OfferPresented,
        )
        val before = initial(destructive = destructive)
        // #1119 slice 1: pin the consumer's clearing before task-flow processing can cancel the grace.
        val consumed = stepper.consumeAcceptIntoJob(region(before).copy(pendingOffers = emptyList()),
            frame, stepper.acceptInputsFromPending(pending(), acceptedAt = 900L))
        assertNull(consumed.pendingDestructive)
        assertAbandoned(consumed, viaStep = false)
        val result = machine.step(before, frame)
        assertNull(region(result.newState).pendingDestructive)
        assertAbandoned(region(result.newState))
        assertEquals(1, unassignedEffects(result.effects).size)
    }

    @Test
    fun `click-less acceptance follows scope independently of quote basis`() {
        for (scope in OfferScope.entries) {
            for (basis in OfferQuoteBasis.entries) {
                val parsed = offer.copy(offerScope = scope, quoteBasis = basis)
                val presented = machine.step(AppState(), frame.copy(
                    timestamp = 800L, flow = Flow.OfferPresented,
                    parsed = ParsedFields.OfferFields(parsedOffer = parsed),
                ))
                val result = machine.step(presented.newState, frame)
                val accepted = requireNotNull(region(result.newState).activeJob).acceptedOffers
                assertEquals("$scope / $basis", if (scope == OfferScope.STANDALONE) 1 else 0, accepted.size)
            }
        }
    }

    @Test
    fun `standalone close preserves other destructive pending kinds`() {
        for (kind in listOf(DestructiveKind.SESSION_END, DestructiveKind.JOB_ABANDON)) {
            val destructive = PendingDestructive(kind, since = 800L, deadline = 10_800L)
            val before = region(initial(destructive = destructive)).copy(pendingOffers = emptyList())
            val after = stepper.consumeAcceptIntoJob(before, frame,
                stepper.acceptInputsFromPending(pending(), acceptedAt = 900L))
            assertEquals(destructive, after.pendingDestructive)
            assertAbandoned(after, viaStep = false)
        }
    }
}

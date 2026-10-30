package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryPayload
import cloud.trotter.log.gig.domain.model.event.payload.TaskUnassignedPayload
import cloud.trotter.log.gig.domain.model.offer.OfferQuoteBasis
import cloud.trotter.log.gig.domain.model.offer.OfferScope
import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.AcceptedOfferEconomics
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.DestructiveKind
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.FlowRegion
import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.OfferIntent
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PendingDestructive
import cloud.trotter.log.gig.domain.state.PendingOffer
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Regions
import cloud.trotter.log.gig.domain.state.Session
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskPhase
import cloud.trotter.log.gig.domain.state.TaskSubFlow
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
    private val offer = ParsedOffer(offerHash = "new-offer", offerScope = OfferScope.STANDALONE, payAmount = 7.0,
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

    private fun withRegion(state: AppState, region: PlatformRegion) = state.copy(
        regions = state.regions.copy(platforms = mapOf(platform to region)),
    )

    private fun completedEffects(effects: List<AppEffect>, taskId: String) =
        effects.filterIsInstance<AppEffect.LogEvent>().filter {
            it.event.type == AppEventType.DELIVERY_COMPLETED &&
                (it.event.payload as? DeliveryPayload)?.taskId == taskId
        }

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
    fun `standalone accept completes the active arrived drop once and clears old active lineage`() {
        for (arrivalWitness in listOf(true, false)) {
            val before = initial()
            val old = region(before)
            val active = dropoff.copy(customerNameHash = "old-customer",
                arrivedAt = if (arrivalWitness) 300L else null,
                completionStepsAt = if (arrivalWitness) null else 350L)
            // The active copy must beat both the stale recent copy and the job placeholder.
            val seeded = withRegion(before, old.copy(activeTask = active,
                recentTasks = listOf(dropoff) + old.recentTasks))
            val result = machine.step(seeded, frame)
            val after = region(result.newState)
            assertFresh(after)
            val copies = (after.recentTasks + listOfNotNull(after.activeTask)).filter { it.taskId == active.taskId }
            assertEquals(1, copies.size)
            val completed = copies.single()
            assertEquals(completed, after.recentTasks.last { it.jobId == active.jobId })
            assertEquals(frame.timestamp, completed.completedAt)
            assertEquals(frame.timestamp, completed.staleCloseCompletedAt)
            assertNull(completed.unassignedAt)
            assertEquals(active.arrivedAt, completed.arrivedAt)
            assertEquals(active.completionStepsAt, completed.completionStepsAt)
            assertTrue(after.activeTask == null || after.activeTask?.jobId == after.activeJob?.jobId)
            assertEquals(1, completedEffects(result.effects, active.taskId).size)
            assertEquals(listOf(completed), mintingDropoffTasks(region(seeded), after, active.jobId,
                retirePending = false, emittedThisStep = emptySet()))
            assertTrue(unassignedEffects(result.effects).isEmpty())
        }
    }

    @Test
    fun `standalone accept unassigns an active un-arrived drop once and clears old active lineage`() {
        val before = initial()
        val old = region(before)
        val active = dropoff.copy(customerNameHash = "old-customer")
        val result = machine.step(withRegion(before, old.copy(activeTask = active)), frame)
        val after = region(result.newState)
        assertAbandoned(after)
        val copies = (after.recentTasks + listOfNotNull(after.activeTask)).filter { it.taskId == active.taskId }
        assertEquals(1, copies.size)
        assertEquals(frame.timestamp, copies.single().unassignedAt)
        assertNull(copies.single().staleCloseCompletedAt)
        assertTrue(after.activeTask == null || after.activeTask?.jobId == after.activeJob?.jobId)
        val unassigned = unassignedEffects(result.effects)
        assertEquals(1, unassigned.size)
        assertEquals(active.taskId, (unassigned.single().event.payload as TaskUnassignedPayload).taskId)
        assertTrue(completedEffects(result.effects, active.taskId).isEmpty())
    }

    @Test
    fun `standalone accept preserves an unassignment over an oldest displacement stamp at the cap`() {
        val before = initial()
        val old = region(before)
        val displaced = dropoff.copy(taskId = "old-displaced", customerNameHash = "displaced-customer",
            completedAt = 400L, arrivedAt = null, completionStepsAt = null)
        val recent = listOf(displaced) + (1..18).map {
            Task("older-$it", "other-job", TaskPhase.PICKUP, startedAt = 1L, completedAt = 2L)
        } + pickup
        assertEquals(20, recent.size)
        val job = requireNotNull(old.activeJob)
        // Keep the outstanding dropoff placeholder: closing it adds the record that forces the cap.
        val crowded = old.copy(recentTasks = recent, activeJob = job.copy(tasks = job.tasks + displaced))
        val result = machine.step(withRegion(before, crowded), frame)
        val after = region(result.newState)
        assertFresh(after)
        assertEquals(PlatformRegionStepper.MAX_RECENT_TASKS, after.recentTasks.size)
        assertTrue(completedEffects(result.effects, displaced.taskId).isEmpty())
        assertEquals(1, unassignedEffects(result.effects).count {
            (it.event.payload as TaskUnassignedPayload).taskId == displaced.taskId
        })
        assertEquals(frame.timestamp, after.recentTasks.single { it.taskId == displaced.taskId }.unassignedAt)
        assertTrue(mintingDropoffTasks(crowded, after, job.jobId,
            retirePending = false, emittedThisStep = emptySet()).isEmpty())
    }

    @Test
    fun `standalone accept still mints a delivered drop evicted by same-step activation cap`() {
        val before = initial()
        val old = region(before)
        val delivered = dropoff.copy(taskId = "old-delivered", customerNameHash = "delivered-customer",
            arrivedAt = 300L, completedAt = 400L)
        val recent = listOf(delivered) + (1..18).map {
            Task("older-$it", "other-job", TaskPhase.PICKUP, startedAt = 1L, completedAt = 2L)
        } + pickup
        assertEquals(20, recent.size)
        val job = requireNotNull(old.activeJob)
        val crowded = old.copy(recentTasks = recent, activeJob = job.copy(tasks = job.tasks + delivered))
        val result = machine.step(withRegion(before, crowded), frame)
        val after = region(result.newState)
        assertFresh(after)
        assertEquals(PlatformRegionStepper.MAX_RECENT_TASKS, after.recentTasks.size)
        assertTrue(after.recentTasks.none { it.taskId == delivered.taskId })
        assertEquals(1, completedEffects(result.effects, delivered.taskId).size)
        assertEquals(listOf(delivered.taskId), mintingDropoffTasks(crowded, after, job.jobId,
            retirePending = false, emittedThisStep = emptySet()).map { it.taskId })
    }

    @Test
    fun `a stale-close completion outlives the cap when the touched set itself exceeds it`() {
        // Astra r3: 21 touched tasks (an arrived active drop, 19 unconfirmed pickups, one placeholder) over a
        // 20-cap. The completion must be the last tail copy so the same-step activation cap evicts an
        // unassigned pickup, never the paid completion.
        val before = initial()
        val old = region(before)
        val arrived = dropoff.copy(taskId = "old-arrived", customerNameHash = "arrived-customer", arrivedAt = 300L)
        val openPickups = (1..19).map {
            Task("open-pickup-$it", "old-job", TaskPhase.PICKUP, storeName = "Old Store", startedAt = 1L)
        }
        val job = requireNotNull(old.activeJob)
        val crowded = old.copy(
            recentTasks = openPickups, activeTask = arrived,
            activeJob = job.copy(tasks = job.tasks + openPickups + arrived),
        )
        val result = machine.step(withRegion(before, crowded), frame)
        val after = region(result.newState)
        assertFresh(after)
        assertEquals(PlatformRegionStepper.MAX_RECENT_TASKS, after.recentTasks.size)
        val kept = after.recentTasks.single { it.taskId == arrived.taskId }
        assertEquals(frame.timestamp, kept.staleCloseCompletedAt)
        assertEquals(1, completedEffects(result.effects, arrived.taskId).size)
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
    fun `re-accepting an owned offer leaves an open job region unchanged`() {
        for (scope in OfferScope.entries) {
            val parsed = offer.copy(offerHash = "old-offer", offerScope = scope)
            val before = region(initial(parsed)).copy(pendingOffers = emptyList())
            val after = stepper.consumeAcceptIntoJob(before, frame,
                stepper.acceptInputsFromPending(pending(parsed), acceptedAt = 900L))
            assertEquals(before, after)
        }
    }

    @Test
    fun `re-accepting an owned offer over a physically complete job closes and mints fresh`() {
        val parsed = offer.copy(offerHash = "old-offer")
        val before = initial(parsed, delivered = true)
        val result = machine.step(before, frame)
        val after = region(result.newState)
        val fresh = requireNotNull(after.activeJob)
        assertNotEquals("old-job", fresh.jobId)
        assertEquals(parsed.offerHash, fresh.parentOfferHash)
        assertEquals(listOf(parsed.offerHash), fresh.acceptedOffers.map { it.offerHash })
        assertEquals(region(before).recentTasks, after.recentTasks)
        assertTrue(fresh.tasks.all { it.jobId == fresh.jobId })
        assertTrue(unassignedEffects(result.effects).isEmpty())
        assertEquals(1, completedEffects(result.effects, dropoff.taskId).size)
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
        val reachablePairs = listOf(
            OfferScope.STANDALONE to OfferQuoteBasis.TOTAL,
            OfferScope.ADD_ON to OfferQuoteBasis.TOTAL,
            OfferScope.ADD_ON to OfferQuoteBasis.INCREMENTAL,
        )
        for ((scope, basis) in reachablePairs) {
            val parsed = offer.copy(offerScope = scope, quoteBasis = basis)
            val presented = machine.step(AppState(), frame.copy(
                timestamp = 800L, flow = Flow.OfferPresented,
                parsed = ParsedFields.OfferFields(parsedOffer = parsed),
            ))
            val result = machine.step(presented.newState, frame)
            val accepted = requireNotNull(region(result.newState).activeJob).acceptedOffers
            assertEquals("$scope / $basis", if (parsed.effectiveScope == OfferScope.STANDALONE) 1 else 0, accepted.size)
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

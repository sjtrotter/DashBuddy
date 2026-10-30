package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.core.state.model.Transition
import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryPayload
import cloud.trotter.log.gig.domain.model.event.payload.TaskUnassignedPayload
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.ObservationPayload
import cloud.trotter.log.gig.domain.pipeline.TimeoutType
import cloud.trotter.log.gig.domain.settings.GraceConfig
import cloud.trotter.log.gig.domain.state.DestructiveKind
import cloud.trotter.log.gig.domain.state.PendingDestructive
import cloud.trotter.log.gig.domain.state.AcceptedOfferEconomics
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.FlowRegion
import cloud.trotter.log.gig.domain.state.IdleSurface
import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Regions
import cloud.trotter.log.gig.domain.state.Session
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskPhase
import cloud.trotter.log.gig.domain.state.TaskSubFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1312: marked idle arms a destructive grace; surviving task evidence restores absorption. */
class UnassignIdleClosesJobTest {
    private val platform = Platform.DoorDash
    private val stepper = PlatformRegionStepper()
    private val machine = StateMachine(
        FlowRegionStepper(), stepper, CrossPlatformRegionStepper(), TransitionPolicy(), EffectMap(),
    )
    private val pickup = Task("pickup-a", "job", TaskPhase.PICKUP,
        storeName = "Store A", startedAt = 100L, arrivedAt = 150L)
    private val sibling = Task("pickup-b", "job", TaskPhase.PICKUP,
        expectedStoreHint = "Store B", startedAt = 100L)
    private val tasks = listOf(pickup, sibling) + listOf("drop-a", "drop-b").map {
        Task(it, "job", TaskPhase.DROPOFF, startedAt = 100L)
    }

    private fun initial(customerHash: String? = null): AppState {
        val active = pickup.copy(customerNameHash = customerHash)
        return AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskPickupArrived, activePlatform = platform),
            platforms = mapOf(platform to PlatformRegion(
                platform = platform, mode = Mode.Online, session = Session("dash", startedAt = 0L),
                activeJob = Job("job", offerStoreHint = listOf("Store A", "Store B"),
                    parentOfferHash = "first", startedAt = 100L,
                    acceptedOffers = listOf(AcceptedOfferEconomics(offerHash = "first", acceptedAt = 100L)),
                    tasks = tasks.map { if (it.taskId == active.taskId) active else it }),
                activeTask = active,
            )),
        ))
    }

    private fun region(state: AppState) = state.regions.platforms.getValue(platform)

    private fun frame(at: Long, flow: Flow?, parsed: ParsedFields = ParsedFields.None) = Observation.Screen(
        timestamp = at, captureId = null, ruleId = "${platform.wire}.screen.synthetic",
        metadata = ReplayMetadata.EMPTY, flow = flow, modeHint = Mode.Online, parsed = parsed,
        idleSurface = if (flow == Flow.Idle) IdleSurface.NO_ORDERS else null,
    )

    private fun unassign(state: AppState = initial()) =
        machine.step(state, frame(200L, Flow.TaskUnassigned)).also { result ->
            val after = region(result.newState)
            assertEquals(Flow.TaskUnassigned, result.newState.regions.flow.flow)
            assertEquals("job", after.activeJob?.jobId)
            assertNull(after.activeTask)
            assertEquals(200L, after.recentTasks.single { it.taskId == pickup.taskId }.unassignedAt)
            assertEquals(2, after.activeJob!!.tasks.count { it.phase == TaskPhase.DROPOFF })
        }

    private fun wake(state: AppState, at: Long = region(state).pendingDestructive!!.deadline) =
        Observation.Timeout(timestamp = at, type = TimeoutType.GRACE_COMMIT, targetPlatform = platform,
            payload = ObservationPayload.GraceWake(region(state).pendingDestructive!!.wakeId))

    private fun armAndCommit(state: AppState): Transition {
        val armed = machine.step(state, frame(5_300L, Flow.Idle))
        assertEquals(DestructiveKind.JOB_ABANDON, region(armed.newState).pendingDestructive?.kind)
        return machine.step(armed.newState, wake(armed.newState))
    }

    private fun unassignedEffects(effects: List<AppEffect>) = effects.filterIsInstance<AppEffect.LogEvent>()
        .filter { it.event.type == AppEventType.TASK_UNASSIGNED }

    private fun assertAbandoned(state: AppState, at: Long) {
        val after = region(state)
        assertNull(after.activeJob)
        assertNull(after.activeTask)
        assertEquals("job", after.lastClosedJobReceipt?.jobId)
        val abandoned = after.recentTasks.filter { it.jobId == "job" }
        assertEquals(tasks.map { it.taskId }.toSet(), abandoned.map { it.taskId }.toSet())
        abandoned.forEach { task ->
            assertEquals(if (task.taskId == pickup.taskId) 200L else at, task.unassignedAt)
            assertNull(task.completedAt)
        }
    }

    private fun assertOneUnassignPerTask(effects: List<AppEffect>) {
        val unassigned = unassignedEffects(effects)
        assertEquals(tasks.associate { it.taskId to 1 }, unassigned.groupingBy {
            (it.event.payload as TaskUnassignedPayload).taskId
        }.eachCount())
        unassigned.forEach {
            val taskId = (it.event.payload as TaskUnassignedPayload).taskId
            assertEquals("log:${AppEventType.TASK_UNASSIGNED}:$taskId", it.effectKey)
        }
        val types = effects.filterIsInstance<AppEffect.LogEvent>().map { it.event.type }
        assertFalse(AppEventType.PICKUP_CONFIRMED in types)
        assertTrue(types.none { it.name.startsWith("DELIVERY_") })
    }

    @Test
    fun `unassign then marked idle arms grace and wake abandons once before next accept mints fresh`() {
        // Null exercises the #736 WARN branch; a nonmatching hash also leaves both TBD drops.
        for (hash in listOf(null, "unmatched-customer")) {
            val first = unassign(initial(hash))
            assertEquals(listOf("Unassigned: Store A"),
                first.effects.filterIsInstance<AppEffect.UpdateBubble>().map { it.text })
            val repeated = machine.step(first.newState, frame(300L, Flow.TaskUnassigned))
            val armed = machine.step(repeated.newState, frame(5_300L, Flow.Idle))
            val pending = requireNotNull(region(armed.newState).pendingDestructive)
            assertEquals(DestructiveKind.JOB_ABANDON, pending.kind)
            assertEquals(Flow.TaskUnassigned, pending.armedFromFlow)
            assertEquals(5_300L, pending.since)
            assertEquals(5_300L, pending.windowFrom)
            assertEquals(5_300L + GraceConfig.JOB_ABANDON_GRACE_MS, pending.deadline)
            assertTrue(pending.wakeId > 0L)
            assertEquals("job", region(armed.newState).activeJob?.jobId)
            assertTrue(unassignedEffects(armed.effects).isEmpty())
            val timer = armed.effects.filterIsInstance<AppEffect.ScheduleTimeout>()
                .single { it.type == TimeoutType.GRACE_COMMIT }
            assertEquals(pending.deadline, timer.deadlineMs)
            assertEquals(GraceConfig.JOB_ABANDON_GRACE_MS, timer.durationMs)
            assertEquals(ObservationPayload.GraceWake(pending.wakeId), timer.payload)
            val closed = machine.step(armed.newState, wake(armed.newState))
            assertNull(region(closed.newState).pendingDestructive)
            assertAbandoned(closed.newState, 5_300L)
            assertEquals("one bubble counts two drops, not three tasks", listOf("Unassigned: 2 deliveries"),
                closed.effects.filterIsInstance<AppEffect.UpdateBubble>().map { it.text })
            // The normal close tripwire runs: explicit unassignments are accounted, and this
            // in-session single accept is also below its existing two-accept floor (#810/#1095).
            assertTrue(closed.effects.filterIsInstance<AppEffect.LogEvent>()
                .none { it.event.type == AppEventType.JOB_ACCEPT_MISMATCH })
            val again = machine.step(closed.newState, frame(10_400L, Flow.Idle))
            assertOneUnassignPerTask(first.effects + repeated.effects + closed.effects + again.effects)

            val accepted = stepper.consumeAcceptIntoJob(region(again.newState),
                frame(11_000L, Flow.TaskPickupNavigation),
                AcceptInputs("second", AcceptedOfferEconomics(offerHash = "second", acceptedAt = 11_000L),
                    listOf("Store C"), 1))
            val fresh = requireNotNull(accepted.activeJob)
            assertTrue(fresh.jobId != "job")
            assertEquals(listOf("second"), fresh.acceptedOffers.map { it.offerHash })
            assertTrue(fresh.tasks.none { task -> tasks.any { it.taskId == task.taskId } })
        }
    }

    @Test
    fun `unassign then an unmarked idle nav frame keeps the job`() {
        val first = unassign()
        val nav = machine.step(first.newState, frame(5_300L, Flow.Idle).copy(idleSurface = null))
        assertEquals("job", region(nav.newState).activeJob?.jobId)
        assertEquals(region(first.newState).recentTasks, region(nav.newState).recentTasks)
        assertTrue(unassignedEffects(nav.effects).isEmpty())
    }

    @Test
    fun `unassign then a marked offline idle frame keeps the job`() {
        val first = unassign()
        val offline = machine.step(first.newState, frame(5_300L, Flow.Idle).copy(modeHint = Mode.Offline))
        assertEquals("job", region(offline.newState).activeJob?.jobId)
        assertTrue(unassignedEffects(offline.effects).isEmpty())
    }

    @Test
    fun `a drop displaced en route is abandoned with its stamp intact and never delivered`() {
        val drop = tasks.single { it.taskId == "drop-a" }.copy(customerNameHash = "customer-a")
        val base = initial()
        val starting = base.copy(regions = base.regions.copy(
            flow = base.regions.flow.copy(flow = Flow.TaskDropoffNavigation),
            platforms = mapOf(platform to region(base).copy(
                activeTask = drop,
                activeJob = region(base).activeJob!!.copy(tasks = tasks.map {
                    if (it.taskId == drop.taskId) drop else it
                }),
            )),
        ))
        val navigate = machine.step(starting, frame(160L, Flow.TaskDropoffNavigation,
            ParsedFields.TaskFields(phase = TaskPhase.DROPOFF, subFlow = TaskSubFlow.NAVIGATION,
                customerNameHash = "customer-a")))
        val switched = machine.step(navigate.newState, frame(180L, Flow.TaskPickupNavigation,
            ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION,
                storeName = "Store B", customerNameHash = "customer-b")))
        val displaced = region(switched.newState).recentTasks.single { it.taskId == drop.taskId }
        assertTrue("pickup switch stamped the drop", displaced.completedAt != null)
        assertNull(displaced.arrivedAt)
        assertNull(displaced.completionStepsAt)
        assertEquals(sibling.taskId, region(switched.newState).activeTask?.taskId)
        val unassigned = machine.step(switched.newState, frame(200L, Flow.TaskUnassigned))
        val closed = armAndCommit(unassigned.newState)
        assertNull(region(closed.newState).activeJob)
        val abandoned = region(closed.newState).recentTasks.single { it.taskId == drop.taskId }
        assertEquals(5_300L, abandoned.unassignedAt)
        assertEquals(displaced.completedAt, abandoned.completedAt)
        val effects = navigate.effects + switched.effects + unassigned.effects + closed.effects
        assertEquals(1, unassignedEffects(effects).count {
            (it.event.payload as TaskUnassignedPayload).taskId == drop.taskId
        })
        assertFalse(effects.filterIsInstance<AppEffect.LogEvent>().any {
            it.event.type == AppEventType.DELIVERY_COMPLETED &&
                (it.event.payload as? DeliveryPayload)?.taskId == drop.taskId
        })
    }

    @Test
    fun `explicit unassign close leaves an arrived unfinished drop unassigned`() {
        val first = unassign()
        val old = region(first.newState)
        val job = requireNotNull(old.activeJob)
        val arrived = job.tasks.first { it.phase == TaskPhase.DROPOFF }.copy(
            customerNameHash = "arrived-customer", arrivedAt = 150L,
        )
        val seeded = first.newState.copy(regions = first.newState.regions.copy(
            platforms = mapOf(platform to old.copy(recentTasks = old.recentTasks + arrived)),
        ))
        val closed = armAndCommit(seeded)
        val abandoned = region(closed.newState).recentTasks.single { it.taskId == arrived.taskId }
        assertEquals(5_300L, abandoned.unassignedAt)
        assertNull(abandoned.completedAt)
        assertNull(abandoned.staleCloseCompletedAt)
        assertFalse(closed.effects.filterIsInstance<AppEffect.LogEvent>().any {
            it.event.type == AppEventType.DELIVERY_COMPLETED &&
                (it.event.payload as? DeliveryPayload)?.taskId == arrived.taskId
        })
    }

    @Test
    fun `abandon evicts nothing and still completes an older delivered receiptless drop`() {
        // Both arrival and a complete-steps tap are delivery evidence (#1315).
        for (arrived in listOf(true, false)) {
            val first = unassign()
            val before = region(first.newState)
            val job = requireNotNull(before.activeJob)
            val delivered = Task("delivered", "job", TaskPhase.DROPOFF,
                customerNameHash = "delivered-customer", startedAt = 100L,
                arrivedAt = if (arrived) 120L else null,
                completionStepsAt = if (arrived) null else 130L, completedAt = 140L)
            val recent = listOf(delivered) + (1..18).map {
                Task("older-$it", "other-job", TaskPhase.PICKUP, startedAt = 1L, completedAt = 2L)
            } + before.recentTasks
            assertEquals(20, recent.size)
            val crowded = first.newState.copy(regions = first.newState.regions.copy(
                platforms = mapOf(platform to before.copy(
                    recentTasks = recent,
                    activeJob = job.copy(tasks = job.tasks + delivered),
                )),
            ))
            val closed = armAndCommit(crowded)
            val after = region(closed.newState)
            assertNull(after.activeJob)
            assertEquals(23, after.recentTasks.size)
            assertTrue("no prior task is evicted on abandon", after.recentTasks.containsAll(recent))
            assertEquals(delivered, after.recentTasks.single { it.taskId == delivered.taskId })
            assertEquals(1, closed.effects.filterIsInstance<AppEffect.LogEvent>().count {
                it.event.type == AppEventType.DELIVERY_COMPLETED &&
                    (it.event.payload as? DeliveryPayload)?.taskId == delivered.taskId
            })
            assertEquals(3, unassignedEffects(closed.effects).size)
        }
    }

    @Test
    fun `unassign then sibling pickup keeps the job for absorption`() {
        val first = unassign()
        val resumed = machine.step(first.newState, frame(300L, Flow.TaskPickupNavigation,
            ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION,
                storeName = "Store B", customerNameHash = "sibling-customer")))
        val after = region(resumed.newState)
        assertEquals("job", after.activeJob?.jobId)
        assertEquals(sibling.taskId, after.activeTask?.taskId)
        assertNull(after.activeTask?.unassignedAt)
        assertEquals(1, unassignedEffects(first.effects + resumed.effects).size)
    }

    @Test
    fun `offer overlay between unassign and idle conservatively keeps the job`() {
        val first = unassign()
        val overlay = machine.step(first.newState, frame(300L, Flow.OfferPresented))
        assertEquals(Flow.OfferPresented, overlay.newState.regions.flow.flow)
        val idle = machine.step(overlay.newState, frame(5_300L, Flow.Idle))
        val after = region(idle.newState)
        assertEquals("job", after.activeJob?.jobId)
        assertNull(after.activeTask)
        assertEquals(region(first.newState).recentTasks, after.recentTasks)
        assertEquals(1, unassignedEffects(first.effects + overlay.effects + idle.effects).size)
    }

    @Test
    fun `post task between unassign and idle closes through 596 without new unassignments`() {
        val first = unassign()
        val postTask = machine.step(first.newState, frame(300L, Flow.PostTask))
        assertEquals("job", region(postTask.newState).activeJob?.jobId)
        val idle = machine.step(postTask.newState, frame(5_300L, Flow.Idle))
        val after = region(idle.newState)
        assertNull(after.activeJob)
        assertEquals("job", after.lastClosedJobReceipt?.jobId)
        assertEquals(region(first.newState).recentTasks, after.recentTasks)
        assertEquals(1, unassignedEffects(first.effects + postTask.effects + idle.effects).size)
    }

    @Test
    fun `flowless recognize only frame preserves unassign to idle edge`() {
        val first = unassign()
        val recognized = machine.step(first.newState, frame(300L, null).copy(modeHint = null))
        assertEquals(Flow.TaskUnassigned, recognized.newState.regions.flow.flow)
        assertEquals(region(first.newState).recentTasks, region(recognized.newState).recentTasks)
        val idle = armAndCommit(recognized.newState)
        assertAbandoned(idle.newState, 5_300L)
        assertOneUnassignPerTask(first.effects + recognized.effects + idle.effects)
    }

    @Test
    fun `marked idle then sibling task two seconds later cancels abandon and restores absorption`() {
        val first = unassign()
        val armed = machine.step(first.newState, frame(5_300L, Flow.Idle))
        val oldWake = wake(armed.newState)
        val resumed = machine.step(armed.newState, frame(7_300L, Flow.TaskPickupNavigation,
            ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION,
                storeName = "Store B", customerNameHash = "sibling-customer")))
        assertNull(region(resumed.newState).pendingDestructive)
        assertEquals("job", region(resumed.newState).activeJob?.jobId)
        assertEquals(sibling.taskId, region(resumed.newState).activeTask?.taskId)
        assertTrue(unassignedEffects(resumed.effects).isEmpty())
        assertTrue(resumed.effects.filterIsInstance<AppEffect.CancelTimeout>()
            .any { it.type == TimeoutType.GRACE_COMMIT })
        val stale = machine.step(resumed.newState, oldWake)
        assertEquals("job", region(stale.newState).activeJob?.jobId)
        assertTrue(unassignedEffects(stale.effects).isEmpty())
        val accepted = stepper.consumeAcceptIntoJob(region(stale.newState),
            frame(11_000L, Flow.TaskPickupNavigation),
            AcceptInputs("addon", AcceptedOfferEconomics(offerHash = "addon", acceptedAt = 11_000L),
                listOf("Store C"), 1))
        assertEquals("job", accepted.activeJob?.jobId)
    }

    @Test
    fun `marked idle commits on a non-task frame strictly past deadline but not at the tie`() {
        val first = unassign()
        val armed = machine.step(first.newState, frame(5_300L, Flow.Idle))
        val deadline = region(armed.newState).pendingDestructive!!.deadline
        val tied = machine.step(armed.newState, frame(deadline, Flow.Idle))
        assertEquals("job", region(tied.newState).activeJob?.jobId)
        assertEquals(region(armed.newState).pendingDestructive, region(tied.newState).pendingDestructive)
        val closed = machine.step(tied.newState, frame(deadline + 1L, Flow.Idle))
        assertAbandoned(closed.newState, 5_300L)
        assertNull(region(closed.newState).pendingDestructive)
        assertOneUnassignPerTask(first.effects + armed.effects + tied.effects + closed.effects)
    }

    @Test
    fun `any task frame cancels abandon even without parsed fields or past deadline`() {
        val armed = machine.step(unassign().newState, frame(5_300L, Flow.Idle))
        val deadline = region(armed.newState).pendingDestructive!!.deadline
        for (at in listOf(7_300L, deadline, deadline + 1L)) {
            val resumed = machine.step(armed.newState, frame(at, Flow.TaskActive))
            assertNull(region(resumed.newState).pendingDestructive)
            assertEquals("job", region(resumed.newState).activeJob?.jobId)
            assertTrue(unassignedEffects(resumed.effects).isEmpty())
        }
    }

    @Test
    fun `offer overlay preserves armed abandon inside and past its window`() {
        val armed = machine.step(unassign().newState, frame(5_300L, Flow.Idle))
        val pending = region(armed.newState).pendingDestructive!!
        val overlay = machine.step(armed.newState, frame(7_300L, Flow.OfferPresented))
        val overdue = machine.step(overlay.newState, frame(pending.deadline + 1L, Flow.OfferPresented))
        for (step in listOf(overlay, overdue)) {
            assertEquals(pending, region(step.newState).pendingDestructive)
            assertEquals("job", region(step.newState).activeJob?.jobId)
            assertTrue(unassignedEffects(step.effects).isEmpty())
        }
        val closed = machine.step(overdue.newState, wake(overdue.newState, pending.deadline + 2L))
        assertAbandoned(closed.newState, pending.since)
    }

    @Test
    fun `unassign confirmation rerender preserves the armed whole-job abandon`() {
        val armed = machine.step(unassign().newState, frame(5_300L, Flow.Idle))
        val repeated = machine.step(armed.newState, frame(7_300L, Flow.TaskUnassigned))
        assertEquals(region(armed.newState).pendingDestructive, region(repeated.newState).pendingDestructive)
        assertTrue(unassignedEffects(repeated.effects).isEmpty())
        val closed = machine.step(repeated.newState, wake(repeated.newState))
        assertAbandoned(closed.newState, 5_300L)
    }

    @Test
    fun `post task clears armed abandon and leaves closure to 596`() {
        val armed = machine.step(unassign().newState, frame(5_300L, Flow.Idle))
        val pending = region(armed.newState).pendingDestructive!!
        for (at in listOf(7_300L, pending.deadline + 1L)) {
            val postTask = machine.step(armed.newState, frame(at, Flow.PostTask))
            assertNull(region(postTask.newState).pendingDestructive)
            assertEquals("job", region(postTask.newState).activeJob?.jobId)
            val closed = machine.step(postTask.newState, frame(at + 1L, Flow.Idle))
            assertNull(region(closed.newState).activeJob)
            assertTrue(unassignedEffects(postTask.effects + closed.effects).isEmpty())
        }
    }

    @Test
    fun `marked idle preserves an existing destructive pending`() {
        val first = unassign().newState
        for (kind in DestructiveKind.entries) {
            val pending = PendingDestructive(kind, since = 200L, deadline = 20_000L,
                authoritative = true, wakeId = 1L, windowFrom = 200L)
            val starting = first.copy(regions = first.regions.copy(platforms = mapOf(
                platform to region(first).copy(pendingDestructive = pending, wakeSeq = 1L),
            )))
            val idle = machine.step(starting, frame(5_300L, Flow.Idle))
            assertEquals(pending, region(idle.newState).pendingDestructive)
            assertEquals("job", region(idle.newState).activeJob?.jobId)
            assertTrue(unassignedEffects(idle.effects).isEmpty())
        }
    }

    @Test
    fun `only the matching grace wake commits abandon including after clock rollback`() {
        val armed = machine.step(unassign().newState, frame(5_300L, Flow.Idle))
        val correct = wake(armed.newState, 5_000L)
        val deadline = region(armed.newState).pendingDestructive!!.deadline
        val stale = machine.step(armed.newState, correct.copy(timestamp = deadline + 1L,
            payload = ObservationPayload.GraceWake(region(armed.newState).pendingDestructive!!.wakeId + 1L)))
        assertEquals(region(armed.newState).pendingDestructive, region(stale.newState).pendingDestructive)
        assertTrue(unassignedEffects(stale.effects).isEmpty())
        val closed = machine.step(stale.newState, correct)
        assertAbandoned(closed.newState, 5_300L)
    }

    @Test
    fun `recovery mid-window rebases re-arms and commits abandon once at original idle instant`() {
        val first = unassign()
        val armed = machine.step(first.newState, frame(5_300L, Flow.Idle))
        val observed = machine.step(armed.newState, frame(7_300L, null))
        val original = region(observed.newState).pendingDestructive!!
        val restored = observed.newState.recoveryHygiene(nowMs = 100_000L)
        val pending = region(restored).pendingDestructive!!
        assertEquals(DestructiveKind.JOB_ABANDON, pending.kind)
        assertEquals(original.since, pending.since)
        assertEquals(original.armedFromFlow, pending.armedFromFlow)
        assertEquals(original.windowFrom, pending.windowFrom)
        assertEquals(100_000L, pending.servedFrom)
        assertEquals(103_000L, pending.deadline)
        assertTrue(pending.wakeId != original.wakeId)
        val timer = restored.pendingDeadlineTimers().single()
        assertEquals(TimeoutType.GRACE_COMMIT, timer.type)
        assertEquals(platform, timer.platform)
        assertEquals(pending.deadline, timer.wake.deadline)
        assertEquals(pending.wakeId, timer.wake.wakeId)
        val stale = machine.step(restored, wake(observed.newState, 100_001L))
        assertEquals(pending, region(stale.newState).pendingDestructive)
        val fire = Observation.Timeout(timestamp = timer.wake.deadline, type = timer.type,
            targetPlatform = timer.platform, payload = ObservationPayload.GraceWake(timer.wake.wakeId))
        val closed = machine.step(stale.newState, fire)
        assertAbandoned(closed.newState, original.since)
        assertNull(region(closed.newState).pendingDestructive)
        assertTrue(closed.newState.pendingDeadlineTimers().isEmpty())
        val duplicate = machine.step(closed.newState, fire.copy(timestamp = fire.timestamp + 1L))
        assertTrue(unassignedEffects(duplicate.effects).isEmpty())
        assertOneUnassignPerTask(first.effects + armed.effects + observed.effects + stale.effects +
            closed.effects + duplicate.effects)
    }
}

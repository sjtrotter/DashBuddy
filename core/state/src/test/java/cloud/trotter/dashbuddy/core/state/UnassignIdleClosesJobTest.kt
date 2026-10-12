package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.TaskUnassignedPayload
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AcceptedOfferEconomics
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1312: the next flow-bearing frame, not the confirmation wording, proves abandonment scope. */
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
    fun `unassign then idle abandons every open task once and next accept mints fresh`() {
        // Null exercises the #736 WARN branch; a nonmatching hash also leaves both TBD drops.
        for (hash in listOf(null, "unmatched-customer")) {
            val first = unassign(initial(hash))
            val repeated = machine.step(first.newState, frame(300L, Flow.TaskUnassigned))
            val closed = machine.step(repeated.newState, frame(5_300L, Flow.Idle))
            assertAbandoned(closed.newState, 5_300L)
            // The normal close tripwire runs: explicit unassignments are accounted, and this
            // in-session single accept is also below its existing two-accept floor (#810/#1095).
            assertTrue(closed.effects.filterIsInstance<AppEffect.LogEvent>()
                .none { it.event.type == AppEventType.JOB_ACCEPT_MISMATCH })
            val again = machine.step(closed.newState, frame(5_400L, Flow.Idle))
            assertOneUnassignPerTask(first.effects + repeated.effects + closed.effects + again.effects)

            val accepted = stepper.consumeAcceptIntoJob(region(again.newState),
                frame(6_000L, Flow.TaskPickupNavigation),
                AcceptInputs("second", AcceptedOfferEconomics(offerHash = "second", acceptedAt = 6_000L),
                    listOf("Store C"), 1))
            val fresh = requireNotNull(accepted.activeJob)
            assertTrue(fresh.jobId != "job")
            assertEquals(listOf("second"), fresh.acceptedOffers.map { it.offerHash })
            assertTrue(fresh.tasks.none { task -> tasks.any { it.taskId == task.taskId } })
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
        val idle = machine.step(recognized.newState, frame(5_300L, Flow.Idle))
        assertAbandoned(idle.newState, 5_300L)
        assertOneUnassignPerTask(first.effects + recognized.effects + idle.effects)
    }
}

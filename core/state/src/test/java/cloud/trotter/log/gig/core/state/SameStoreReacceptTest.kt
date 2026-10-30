package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.ObservationPayload
import cloud.trotter.log.gig.domain.pipeline.TimeoutType
import cloud.trotter.log.gig.domain.state.AcceptedOfferEconomics
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.FlowRegion
import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Regions
import cloud.trotter.log.gig.domain.state.Session
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1315: displacement is not confirmation; confirmed same-store revisits own a new arrival. */
class SameStoreReacceptTest {
    private val pickup = Task("pickup-a", "job", TaskPhase.PICKUP, storeName = "H-E-B", startedAt = 100L)
    private val placeholder = Task("placeholder", "job", TaskPhase.PICKUP,
        startedAt = 400L, expectedStoreHint = "H-E-B")
    private val machine = StateMachine(
        FlowRegionStepper(), PlatformRegionStepper(), CrossPlatformRegionStepper(), TransitionPolicy(), EffectMap(),
    )
    private fun region(state: AppState) = state.regions.platforms.getValue(Platform.DoorDash)
    private fun withRegion(state: AppState, region: PlatformRegion) =
        state.copy(regions = state.regions.copy(platforms = mapOf(Platform.DoorDash to region)))

    private fun initial(extra: List<Task> = emptyList(), recent: List<Task> = emptyList()) = AppState(regions = Regions(
        flow = FlowRegion(flow = Flow.TaskPickupNavigation, activePlatform = Platform.DoorDash),
        platforms = mapOf(Platform.DoorDash to PlatformRegion(
            platform = Platform.DoorDash, mode = Mode.Online, session = Session("dash", startedAt = 0L),
            activeJob = Job("job", offerStoreHint = emptyList(), parentOfferHash = null,
                startedAt = 100L, tasks = listOf(pickup) + extra + recent.filter { it.jobId == "job" }),
            activeTask = pickup, recentTasks = recent,
        )),
    ))
    private fun frame(t: Long, flow: Flow, store: String = "H-E-B", customer: String = "customer") = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "doordash.screen.synthetic", metadata = ReplayMetadata.EMPTY,
        flow = flow, modeHint = Mode.Online,
        parsed = ParsedFields.TaskFields(phase = flow.toTaskPhase()!!, subFlow = flow.toTaskSubFlow()!!,
            storeName = store, customerNameHash = customer, customerAddressHash = "$customer-address"),
    )
    private fun arrived(state: AppState = initial()) =
        machine.step(state, frame(200L, Flow.TaskPickupArrived)).newState
    private fun confirmed(state: AppState = initial()): AppState {
        val after = machine.step(arrived(state), frame(300L, Flow.TaskDropoffNavigation)).newState
        val task = region(after).recentTasks.single { it.taskId == pickup.taskId }
        assertEquals(300L, task.confirmedAt)
        assertEquals(task, region(after).activeJob!!.tasks.single { it.taskId == pickup.taskId })
        return after
    }
    private fun navigate(placeholders: List<Task>): PlatformRegion {
        val state = confirmed()
        val r = region(state)
        val job = requireNotNull(r.activeJob)
        return region(machine.step(withRegion(state, r.copy(activeJob = job.copy(
            tasks = job.tasks + placeholders,
        ))), frame(500L, Flow.TaskPickupNavigation)).newState)
    }

    @Test
    fun `confirmed pickup yields to an open placeholder through either store matching tier`() {
        for (hint in listOf("H-E-B", "h-e-b", "H-E-B (123)")) {
            val after = navigate(listOf(placeholder.copy(expectedStoreHint = hint)))
            val task = requireNotNull(after.activeTask) { "Expected active task" }
            assertEquals(hint, placeholder.taskId, task.taskId)
            assertNull(task.arrivedAt)
            assertEquals(300L, after.recentTasks.single { it.taskId == pickup.taskId }.confirmedAt)
        }
    }

    @Test
    fun `confirmed pickup still resumes without an open matching placeholder`() {
        for (placeholders in listOf(emptyList(), listOf(placeholder.copy(expectedStoreHint = "Target")),
            listOf(placeholder.copy(expectedStoreHint = null)))) {
            val after = navigate(placeholders)
            val task = requireNotNull(after.activeTask) { "Expected active task" }
            assertEquals(pickup.taskId, task.taskId)
            assertEquals(200L, task.arrivedAt)
            assertNull(task.completedAt)
            assertEquals(300L, task.confirmedAt)
        }
    }

    @Test
    fun `abandoned same-store pickup yields to the fresh open placeholder`() {
        // Restored lineage hand-build: an abandoned visit has no confirmation; a later accept
        // owns a fresh placeholder while another pickup is active. The nav must not self-heal
        // the abandoned H-E-B visit by resuming it over that new order.
        val abandoned = pickup.copy(taskId = "abandoned", arrivedAt = 150L, unassignedAt = 200L)
        val before = initial(extra = listOf(placeholder), recent = listOf(abandoned))
        val r = region(before)
        val active = pickup.copy(storeName = "Target", arrivedAt = 250L)
        val job = requireNotNull(r.activeJob)
        val restored = withRegion(before, r.copy(activeTask = active,
            activeJob = job.copy(tasks = job.tasks.map {
                if (it.taskId == pickup.taskId) active else it
            })))
        val after = region(machine.step(restored, frame(500L, Flow.TaskPickupNavigation)).newState)
        val task = requireNotNull(after.activeTask) { "Expected active task" }
        assertEquals(placeholder.taskId, task.taskId)
        assertEquals("H-E-B", task.storeName)
        assertNull(task.arrivedAt)
        assertNull(task.confirmedAt)
        assertEquals(abandoned, after.recentTasks.single { it.taskId == abandoned.taskId })
    }

    @Test
    fun `placeholder resolution prefers exact case insensitive hint over token match`() {
        val noisy = placeholder.copy(taskId = "noisy", expectedStoreHint = "H-E-B (123)")
        val after = navigate(listOf(noisy, placeholder.copy(expectedStoreHint = "h-e-b")))
        val task = requireNotNull(after.activeTask) { "Expected active task" }
        assertEquals(placeholder.taskId, task.taskId)
        assertNull(task.arrivedAt)
    }

    @Test
    fun `A to B to A mid-shop folds another A order and resumes its original arrival`() {
        for (arriveAtB in listOf(false, true)) {
            var state = machine.step(arrived(), frame(300L, Flow.TaskPickupNavigation, "Target")).newState
            if (arriveAtB) state = machine.step(state, frame(350L, Flow.TaskPickupArrived, "Target")).newState
            val before = region(state)
            val a = before.recentTasks.single { it.taskId == pickup.taskId }
            assertEquals(300L, a.completedAt)
            assertNull(a.confirmedAt)
            // Exercise the add-on fold with the stepper-owned displaced state, not a synthetic stamp.
            val stepper = PlatformRegionStepper()
            val inputs = AcceptInputs("second", AcceptedOfferEconomics(offerHash = "second", acceptedAt = 400L),
                listOf("H-E-B"), 1)
            val accepted = stepper.appendAddOn(before, before.activeJob!!, frame(400L, Flow.TaskPickupNavigation), inputs)
            assertEquals(2, accepted.activeJob!!.tasks.count { it.phase == TaskPhase.PICKUP })
            state = machine.step(withRegion(state, accepted), frame(500L, Flow.TaskPickupNavigation)).newState
            val after = region(state)
            val task = requireNotNull(after.activeTask) { "Expected active task" }
            assertEquals(pickup.taskId, task.taskId)
            assertEquals(200L, task.arrivedAt)
            assertNull(task.confirmedAt)
            assertNull(task.completedAt)
        }
    }

    @Test
    fun `dropoff after grace-retired pickup confirms it and same-store addon mints a placeholder`() {
        val idle = machine.step(arrived(), frame(1_000L, Flow.TaskPickupNavigation).copy(
            flow = Flow.Idle, parsed = ParsedFields.None,
        )).newState
        val pending = requireNotNull(region(idle).pendingDestructive)
        val retired = machine.step(idle, Observation.Timeout(
            timestamp = pending.deadline + 1L, type = TimeoutType.GRACE_COMMIT,
            targetPlatform = Platform.DoorDash, payload = ObservationPayload.GraceWake(pending.wakeId),
        )).newState
        assertNull(region(retired).activeTask)
        assertNull(region(retired).recentTasks.single { it.taskId == pickup.taskId }.confirmedAt)
        val at = pending.deadline + 2L
        val dropoff = machine.step(retired, frame(at, Flow.TaskDropoffNavigation))
        val confirmed = region(dropoff.newState)
        val task = confirmed.recentTasks.single { it.taskId == pickup.taskId }
        assertEquals(at, task.confirmedAt)
        assertEquals(task, requireNotNull(confirmed.activeJob).tasks.single { it.taskId == pickup.taskId })
        val again = region(machine.step(dropoff.newState, frame(at + 1L, Flow.TaskDropoffNavigation)).newState)
        assertEquals(at, again.recentTasks.single { it.taskId == pickup.taskId }.confirmedAt)
        val inputs = AcceptInputs("second", AcceptedOfferEconomics(offerHash = "second", acceptedAt = at + 2L),
            listOf("H-E-B"), 1)
        val accepted = PlatformRegionStepper().appendAddOn(again, requireNotNull(again.activeJob),
            frame(at + 2L, Flow.TaskDropoffNavigation), inputs)
        val pickups = requireNotNull(accepted.activeJob).tasks.filter { it.phase == TaskPhase.PICKUP }
        assertEquals(2, pickups.size)
        val fresh = pickups.single { it.taskId != pickup.taskId }
        assertEquals("H-E-B", fresh.expectedStoreHint)
        assertNull(fresh.storeName)
        assertNull(fresh.arrivedAt)
        assertNull(fresh.confirmedAt)
        assertTrue(pickups.any { it.taskId == pickup.taskId && it.confirmedAt == at })
    }

    @Test
    fun `every dropoff displacement path confirms arrived recent pickups and mirrors the sweep filter`() {
        // Restored lineage: excluded/unarrived/previously-confirmed copies document snapshot shapes.
        val recent = listOf(
            pickup.copy(taskId = "earlier", arrivedAt = 110L, completedAt = 150L),
            pickup.copy(taskId = "unarrived", completedAt = 150L),
            pickup.copy(taskId = "abandoned", arrivedAt = 110L, unassignedAt = 150L),
            pickup.copy(taskId = "other-job", jobId = "other", arrivedAt = 110L, completedAt = 150L),
            pickup.copy(taskId = "already-confirmed", arrivedAt = 110L, completedAt = 150L, confirmedAt = 160L),
        )
        val drop = Task("drop", "job", TaskPhase.DROPOFF, startedAt = 100L)
        for (path in listOf("fresh", "placeholder", "resume")) {
            val restoredDrop = drop.copy(customerNameHash = "customer", completedAt = 170L)
            val before = initial(extra = if (path == "placeholder") listOf(drop) else emptyList(),
                recent = recent + if (path == "resume") listOf(restoredDrop) else emptyList())
            val r = region(confirmed(before))
            assertEquals(if (path == "fresh") false else true, r.activeTask!!.taskId == drop.taskId)
            val earlier = r.recentTasks.single { it.taskId == "earlier" }
            assertEquals(300L, earlier.confirmedAt)
            assertEquals(150L, earlier.completedAt)
            assertEquals(earlier, r.activeJob!!.tasks.single { it.taskId == earlier.taskId })
            recent.drop(1).forEach { old -> assertEquals(old, r.recentTasks.single { it.taskId == old.taskId }) }
        }
    }
}

package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        return region(machine.step(withRegion(state, r.copy(activeJob = r.activeJob!!.copy(
            tasks = r.activeJob.tasks + placeholders,
        ))), frame(500L, Flow.TaskPickupNavigation)).newState)
    }

    @Test
    fun `confirmed pickup yields to an open placeholder through either store matching tier`() {
        for (hint in listOf("H-E-B", "h-e-b", "H-E-B (123)")) {
            val after = navigate(listOf(placeholder.copy(expectedStoreHint = hint)))
            assertEquals(hint, placeholder.taskId, after.activeTask!!.taskId)
            assertNull(after.activeTask.arrivedAt)
            assertEquals(300L, after.recentTasks.single { it.taskId == pickup.taskId }.confirmedAt)
        }
    }

    @Test
    fun `confirmed pickup still resumes without an open matching placeholder`() {
        for (placeholders in listOf(emptyList(), listOf(placeholder.copy(expectedStoreHint = "Target")),
            listOf(placeholder.copy(expectedStoreHint = null)))) {
            val after = navigate(placeholders)
            assertEquals(pickup.taskId, after.activeTask!!.taskId)
            assertEquals(200L, after.activeTask.arrivedAt)
            assertNull(after.activeTask.completedAt)
            assertEquals(300L, after.activeTask.confirmedAt)
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
        val restored = withRegion(before, r.copy(activeTask = active,
            activeJob = r.activeJob!!.copy(tasks = r.activeJob.tasks.map {
                if (it.taskId == pickup.taskId) active else it
            })))
        val after = region(machine.step(restored, frame(500L, Flow.TaskPickupNavigation)).newState)
        assertEquals(placeholder.taskId, after.activeTask!!.taskId)
        assertEquals("H-E-B", after.activeTask.storeName)
        assertNull(after.activeTask.arrivedAt)
        assertNull(after.activeTask.confirmedAt)
        assertEquals(abandoned, after.recentTasks.single { it.taskId == abandoned.taskId })
    }

    @Test
    fun `placeholder resolution prefers exact case insensitive hint over token match`() {
        val noisy = placeholder.copy(taskId = "noisy", expectedStoreHint = "H-E-B (123)")
        val after = navigate(listOf(noisy, placeholder.copy(expectedStoreHint = "h-e-b")))
        assertEquals(placeholder.taskId, after.activeTask!!.taskId)
        assertNull(after.activeTask.arrivedAt)
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
            assertEquals(pickup.taskId, after.activeTask!!.taskId)
            assertEquals(200L, after.activeTask.arrivedAt)
            assertNull(after.activeTask.confirmedAt)
            assertNull(after.activeTask.completedAt)
        }
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

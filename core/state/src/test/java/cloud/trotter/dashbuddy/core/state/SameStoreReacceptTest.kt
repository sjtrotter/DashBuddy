package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.pipeline.Observation
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
import org.junit.Assert.assertNull
import org.junit.Test

/** #1315: a same-store reaccept resolves its open pickup without borrowing a confirmed arrival. */
class SameStoreReacceptTest {
    private val recent = Task("confirmed", "job", TaskPhase.PICKUP, storeName = "H-E-B",
        startedAt = 100L, arrivedAt = 200L, completedAt = 300L)
    private val placeholder = Task("placeholder", "job", TaskPhase.PICKUP,
        startedAt = 400L, expectedStoreHint = "H-E-B")

    private fun navigate(pickup: Task = recent, placeholders: List<Task>): PlatformRegion {
        val drop = Task("drop", "job", TaskPhase.DROPOFF, startedAt = 300L, customerNameHash = "customer")
        val before = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskDropoffNavigation, activePlatform = Platform.DoorDash),
            platforms = mapOf(Platform.DoorDash to PlatformRegion(
                platform = Platform.DoorDash, mode = Mode.Online, session = Session("dash", startedAt = 0L),
                activeJob = Job("job", offerStoreHint = emptyList(), parentOfferHash = null,
                    startedAt = 100L, tasks = listOf(pickup, drop) + placeholders),
                activeTask = drop, recentTasks = listOf(pickup),
            )),
        ))
        val obs = Observation.Screen(
            timestamp = 500L, captureId = null, ruleId = "doordash.screen.pickup_navigation",
            metadata = ReplayMetadata.EMPTY, flow = Flow.TaskPickupNavigation, modeHint = Mode.Online,
            parsed = ParsedFields.TaskFields(phase = TaskPhase.PICKUP,
                subFlow = TaskSubFlow.NAVIGATION, storeName = "H-E-B"),
        )
        val machine = StateMachine(
            FlowRegionStepper(), PlatformRegionStepper(), CrossPlatformRegionStepper(), TransitionPolicy(), EffectMap(),
        )
        return machine.step(before, obs).newState.regions.platforms.getValue(Platform.DoorDash)
    }

    @Test
    fun `confirmed pickup yields to an open placeholder through either store matching tier`() {
        for (hint in listOf("H-E-B", "h-e-b", "H-E-B (123)")) {
            val after = navigate(placeholders = listOf(placeholder.copy(expectedStoreHint = hint)))
            assertEquals(hint, placeholder.taskId, after.activeTask!!.taskId)
            assertNull(after.activeTask!!.arrivedAt)
            assertEquals(recent, after.recentTasks.single { it.taskId == recent.taskId })
        }
    }

    @Test
    fun `confirmed pickup still resumes without an open matching placeholder`() {
        val alternatives = listOf(
            emptyList(),
            listOf(placeholder.copy(expectedStoreHint = "Target")),
            listOf(placeholder.copy(expectedStoreHint = null)),
            listOf(placeholder.copy(completedAt = 450L)),
            listOf(placeholder.copy(unassignedAt = 450L)),
            listOf(placeholder.copy(storeName = "H-E-B")),
        )
        for (placeholders in alternatives) {
            val after = navigate(placeholders = placeholders)
            assertEquals(placeholders.toString(), recent.taskId, after.activeTask!!.taskId)
            assertEquals(recent.arrivedAt, after.activeTask!!.arrivedAt)
            assertNull(after.activeTask!!.completedAt)
        }
    }

    @Test
    fun `unconfirmed pickup keeps resuming even with an open matching placeholder`() {
        val after = navigate(pickup = recent.copy(completedAt = null), placeholders = listOf(placeholder))
        assertEquals(recent.taskId, after.activeTask!!.taskId)
        assertEquals(recent.arrivedAt, after.activeTask!!.arrivedAt)
    }

    @Test
    fun `placeholder resolution prefers exact case insensitive hint over token match`() {
        val noisy = placeholder.copy(taskId = "noisy", expectedStoreHint = "H-E-B (123)")
        val after = navigate(placeholders = listOf(noisy, placeholder.copy(expectedStoreHint = "h-e-b")))
        assertEquals(placeholder.taskId, after.activeTask!!.taskId)
        assertNull(after.activeTask!!.arrivedAt)
    }
}

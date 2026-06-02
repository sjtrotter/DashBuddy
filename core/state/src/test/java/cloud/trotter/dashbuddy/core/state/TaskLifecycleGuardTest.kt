package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the task-clear grace guard and the stacked-dropoff transition in
 * [PlatformRegionStepper].
 *
 * A transient idle/offer mid-task (an informational screen, or the idle map
 * flashing before the delivery summary) must NOT forget the active task; only a
 * sustained idle past the grace window — or an authoritative end (PostTask /
 * session) — retires it. Back-to-back task transitions (including stacked
 * dropoffs) must still mint distinct taskIds.
 */
class TaskLifecycleGuardTest {

    private val stepper = PlatformRegionStepper()
    private val flowStepper = FlowRegionStepper()
    private val policy = TransitionPolicy()
    private val graceMs = TransitionPolicy.DEFAULT_GRACE_MS

    // ---- helpers ----

    private fun region(activeTask: Task?, taskClearGraceDeadline: Long? = null) = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Online,
        session = Session("sess-1", startedAt = 100L),
        activeJob = Job("job-1", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 200L),
        activeTask = activeTask,
        taskClearGraceDeadline = taskClearGraceDeadline,
    )

    private fun pickupTask(taskId: String, storeName: String, arrivedAt: Long? = null) = Task(
        taskId = taskId, jobId = "job-1", phase = TaskPhase.PICKUP,
        storeName = storeName, startedAt = 300L, arrivedAt = arrivedAt,
    )

    private fun dropoffTask(taskId: String, customerAddressHash: String, arrivedAt: Long? = null) = Task(
        taskId = taskId, jobId = "job-1", phase = TaskPhase.DROPOFF,
        customerAddressHash = customerAddressHash, startedAt = 300L, arrivedAt = arrivedAt,
    )

    private fun taskObs(
        flow: Flow,
        phase: TaskPhase,
        subFlow: TaskSubFlow,
        storeName: String? = null,
        storeAddress: String? = null,
        customerAddressHash: String? = null,
        timestamp: Long,
    ) = Observation.Screen(
        timestamp = timestamp, captureId = null, ruleId = "doordash.screen.test",
        metadata = ReplayMetadata.EMPTY, flow = flow, modeHint = Mode.Online,
        parsed = ParsedFields.TaskFields(
            phase = phase, subFlow = subFlow,
            storeName = storeName, storeAddress = storeAddress,
            customerAddressHash = customerAddressHash,
        ),
    )

    private fun idleObs(timestamp: Long) = Observation.Screen(
        timestamp = timestamp, captureId = null, ruleId = "doordash.screen.idle",
        metadata = ReplayMetadata.EMPTY, flow = Flow.Idle, modeHint = Mode.Online,
        parsed = ParsedFields.None,
    )

    private fun postTaskObs(timestamp: Long) = Observation.Screen(
        timestamp = timestamp, captureId = null, ruleId = "doordash.screen.delivery_summary",
        metadata = ReplayMetadata.EMPTY, flow = Flow.PostTask, modeHint = Mode.Online,
        parsed = ParsedFields.None,
    )

    /** Step through the real flow stepper, returning the new region + the flow it produced. */
    private fun step(prev: PlatformRegion, prevFlow: FlowRegion, obs: Observation): Pair<PlatformRegion, FlowRegion> {
        val nextFlow = if (obs is Observation.FlowObservation) flowStepper.step(prevFlow, obs) else prevFlow
        return stepper.step(prev, prevFlow, nextFlow, obs, policy) to nextFlow
    }

    // ---- the guard ----

    @Test
    fun `idle flash mid-pickup keeps the active task and arms the grace`() {
        val r0 = region(pickupTask("task-A", "H-E-B", arrivedAt = 800L))
        val (r1, _) = step(r0, FlowRegion(flow = Flow.TaskPickupArrived), idleObs(timestamp = 1_000L))

        assertNotNull("a transient idle must not forget the task", r1.activeTask)
        assertEquals("task-A", r1.activeTask?.taskId)
        assertEquals("grace deadline armed", 1_000L + graceMs, r1.taskClearGraceDeadline)
    }

    @Test
    fun `idle flash then return to the pickup keeps the same taskId`() {
        // The reported timeline round-trip: task -> idle -> back to the task.
        val r0 = region(pickupTask("task-A", "H-E-B", arrivedAt = 800L))
        val (r1, f1) = step(r0, FlowRegion(flow = Flow.TaskPickupArrived), idleObs(timestamp = 1_000L))
        val (r2, _) = step(
            r1, f1,
            taskObs(Flow.TaskPickupArrived, TaskPhase.PICKUP, TaskSubFlow.ARRIVED, storeName = "H-E-B", timestamp = 1_005L),
        )

        assertEquals("returning to the task must keep the same taskId", "task-A", r2.activeTask?.taskId)
        assertNull("returning to a task cancels the grace", r2.taskClearGraceDeadline)
    }

    @Test
    fun `sustained idle past the grace window retires the task`() {
        val r0 = region(pickupTask("task-A", "H-E-B", arrivedAt = 800L))
        val (r1, f1) = step(r0, FlowRegion(flow = Flow.TaskPickupArrived), idleObs(timestamp = 1_000L))
        // A later idle observation past the deadline retires the task lazily in step().
        val (r2, _) = step(r1, f1, idleObs(timestamp = 1_000L + graceMs + 1))

        assertNull("sustained idle retires the task", r2.activeTask)
        assertTrue(
            "the retired task is completed into recentTasks",
            r2.recentTasks.any { it.taskId == "task-A" && it.completedAt != null },
        )
    }

    @Test
    fun `PostTask completes the task and clears a pending grace`() {
        val r0 = region(pickupTask("task-A", "H-E-B", arrivedAt = 800L), taskClearGraceDeadline = 11_000L)
        val (r1, _) = step(r0, FlowRegion(flow = Flow.Idle), postTaskObs(timestamp = 1_005L))

        assertNull("PostTask is authoritative — the task completes", r1.activeTask)
        assertNull(r1.taskClearGraceDeadline)
        assertTrue(r1.recentTasks.any { it.taskId == "task-A" && it.completedAt != null })
    }

    // ---- back-to-back transitions ----

    @Test
    fun `pickup to dropoff across an idle flash still mints the dropoff`() {
        val r0 = region(pickupTask("task-A", "H-E-B", arrivedAt = 800L))
        val (r1, f1) = step(r0, FlowRegion(flow = Flow.TaskPickupArrived), idleObs(timestamp = 1_000L))
        val (r2, _) = step(
            r1, f1,
            taskObs(Flow.TaskDropoffNavigation, TaskPhase.DROPOFF, TaskSubFlow.NAVIGATION, customerAddressHash = "cust-1", timestamp = 1_005L),
        )

        assertNotEquals("phase change mints a new task", "task-A", r2.activeTask?.taskId)
        assertEquals(TaskPhase.DROPOFF, r2.activeTask?.phase)
        assertTrue(r2.recentTasks.any { it.taskId == "task-A" && it.completedAt != null })
    }

    @Test
    fun `stacked dropoff mints a new task on customer change`() {
        val r0 = region(dropoffTask("task-A", customerAddressHash = "cust-1", arrivedAt = 800L))
        val (r1, _) = step(
            r0, FlowRegion(flow = Flow.TaskDropoffArrived),
            taskObs(Flow.TaskDropoffNavigation, TaskPhase.DROPOFF, TaskSubFlow.NAVIGATION, customerAddressHash = "cust-2", timestamp = 1_000L),
        )

        assertNotEquals("a different customer mints a new dropoff task", "task-A", r1.activeTask?.taskId)
        assertEquals(TaskPhase.DROPOFF, r1.activeTask?.phase)
        assertEquals("cust-2", r1.activeTask?.customerAddressHash)
        assertTrue(r1.recentTasks.any { it.taskId == "task-A" && it.completedAt != null })
    }

    @Test
    fun `same-customer dropoff nav flicker keeps the same task`() {
        val r0 = region(dropoffTask("task-A", customerAddressHash = "cust-1", arrivedAt = 800L))
        val (r1, _) = step(
            r0, FlowRegion(flow = Flow.TaskDropoffArrived),
            taskObs(Flow.TaskDropoffNavigation, TaskPhase.DROPOFF, TaskSubFlow.NAVIGATION, customerAddressHash = "cust-1", timestamp = 1_000L),
        )

        assertEquals("same customer must NOT mint a new task", "task-A", r1.activeTask?.taskId)
    }

    @Test
    fun `dropoff to pickup mints a new task`() {
        val r0 = region(dropoffTask("task-A", customerAddressHash = "cust-1", arrivedAt = 800L))
        val (r1, _) = step(
            r0, FlowRegion(flow = Flow.TaskDropoffArrived),
            taskObs(Flow.TaskPickupNavigation, TaskPhase.PICKUP, TaskSubFlow.NAVIGATION, storeName = "Wingstop", timestamp = 1_000L),
        )

        assertNotEquals("phase change DROPOFF->PICKUP mints a new task", "task-A", r1.activeTask?.taskId)
        assertEquals(TaskPhase.PICKUP, r1.activeTask?.phase)
    }

    @Test
    fun `subPhase and storeAddress are threaded onto the task`() {
        val r0 = region(pickupTask("task-A", "H-E-B", arrivedAt = null))
        val (r1, _) = step(
            r0, FlowRegion(flow = Flow.TaskPickupNavigation),
            taskObs(
                Flow.TaskPickupNavigation, TaskPhase.PICKUP, TaskSubFlow.NAVIGATION,
                storeName = "H-E-B", storeAddress = "7330 N Loop 1604 W", timestamp = 1_000L,
            ),
        )

        assertEquals("task-A", r1.activeTask?.taskId)
        assertEquals(TaskSubFlow.NAVIGATION, r1.activeTask?.subPhase)
        assertEquals("7330 N Loop 1604 W", r1.activeTask?.storeAddress)
    }
}

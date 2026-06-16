package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
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

    private fun region(activeTask: Task?, pending: PendingDestructive? = null) = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Online,
        session = Session("sess-1", startedAt = 100L),
        activeJob = Job("job-1", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 200L),
        activeTask = activeTask,
        pendingDestructive = pending,
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
        itemsShopped: Int? = null,
        itemsRemaining: Int? = null,
        timestamp: Long,
    ) = Observation.Screen(
        timestamp = timestamp, captureId = null, ruleId = "doordash.screen.test",
        metadata = ReplayMetadata.EMPTY, flow = flow, modeHint = Mode.Online,
        parsed = ParsedFields.TaskFields(
            phase = phase, subFlow = subFlow,
            storeName = storeName, storeAddress = storeAddress,
            customerAddressHash = customerAddressHash,
            itemsShopped = itemsShopped, itemsRemaining = itemsRemaining,
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
        assertEquals("retire-grace armed", 1_000L + graceMs, r1.pendingDestructive?.deadline)
        assertEquals(DestructiveKind.TASK_RETIRE, r1.pendingDestructive?.kind)
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
        assertNull("returning to a task cancels the grace", r2.pendingDestructive)
    }

    @Test
    fun `returning to a prior task after switching away resumes its taskId (#499 A-B-A, slice 2)`() {
        // PICKUP(H-E-B) -> DROPOFF(addr-1) -> back to PICKUP(H-E-B): the pickup must keep its identity,
        // not re-mint. The single-slot model completes-and-re-mints on each phase switch (#499); with
        // Job.tasks as the container, returning re-matches the existing subtask by store + phase.
        val r0 = region(activeTask = null)
        val (r1, f1) = step(
            r0, FlowRegion(flow = Flow.Idle),
            taskObs(Flow.TaskPickupNavigation, TaskPhase.PICKUP, TaskSubFlow.NAVIGATION, storeName = "H-E-B", timestamp = 1_000L),
        )
        val pickupId = r1.activeTask?.taskId
        assertNotNull("pickup minted", pickupId)

        val (r2, f2) = step(
            r1, f1,
            taskObs(Flow.TaskDropoffNavigation, TaskPhase.DROPOFF, TaskSubFlow.NAVIGATION, customerAddressHash = "addr-1", timestamp = 2_000L),
        )
        assertNotEquals("switching to the dropoff is a different task", pickupId, r2.activeTask?.taskId)
        val dropoffId = r2.activeTask?.taskId

        val (r3, _) = step(
            r2, f2,
            taskObs(Flow.TaskPickupNavigation, TaskPhase.PICKUP, TaskSubFlow.NAVIGATION, storeName = "H-E-B", timestamp = 3_000L),
        )
        assertEquals("returning to the H-E-B pickup must resume its taskId, not re-mint", pickupId, r3.activeTask?.taskId)
        assertEquals("the job still has exactly the two subtasks (no phantom third)", 2, r3.activeJob?.tasks?.size)
        assertEquals(
            "Job.tasks holds the original pickup + dropoff, no duplicate",
            setOf(pickupId, dropoffId),
            r3.activeJob?.tasks?.map { it.taskId }?.toSet(),
        )
    }

    @Test
    fun `Job tasks mirrors the job's task lineage (#503 slice 1)`() {
        // A pickup then a dropoff in the same job → Job.tasks accumulates both in lineage order,
        // even though the model still tracks only one activeTask + recentTasks (additive shadow).
        val r0 = region(activeTask = null)
        val (r1, f1) = step(
            r0, FlowRegion(flow = Flow.Idle),
            taskObs(Flow.TaskPickupNavigation, TaskPhase.PICKUP, TaskSubFlow.NAVIGATION, storeName = "H-E-B", timestamp = 1_000L),
        )
        assertEquals("the active pickup is mirrored into Job.tasks", 1, r1.activeJob?.tasks?.size)
        assertEquals(r1.activeTask?.taskId, r1.activeJob?.tasks?.single()?.taskId)
        assertEquals(TaskPhase.PICKUP, r1.activeJob?.tasks?.single()?.phase)

        val (r2, _) = step(
            r1, f1,
            taskObs(Flow.TaskDropoffNavigation, TaskPhase.DROPOFF, TaskSubFlow.NAVIGATION, customerAddressHash = "addr-1", timestamp = 2_000L),
        )
        assertEquals(
            "Job.tasks now mirrors both legs in order",
            listOf(TaskPhase.PICKUP, TaskPhase.DROPOFF),
            r2.activeJob?.tasks?.map { it.phase },
        )
        assertEquals("the active dropoff is the last entry", r2.activeTask?.taskId, r2.activeJob?.tasks?.last()?.taskId)
        assertTrue(
            "every mirrored task belongs to the active job",
            r2.activeJob?.tasks?.all { it.jobId == r2.activeJob?.jobId } == true,
        )
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
    fun `PostTask arms a short authoritative retire grace - not an immediate completion (#431)`() {
        val r0 = region(
            pickupTask("task-A", "H-E-B", arrivedAt = 800L),
            pending = PendingDestructive(DestructiveKind.TASK_RETIRE, since = 800L, deadline = 11_000L),
        )
        val (r1, _) = step(r0, FlowRegion(flow = Flow.Idle), postTaskObs(timestamp = 1_005L))

        assertNotNull("the task survives the receipt frame (#431 pt 2)", r1.activeTask)
        assertEquals("task-A", r1.activeTask?.taskId)
        val pend = r1.pendingDestructive!!
        assertEquals(DestructiveKind.TASK_RETIRE, pend.kind)
        assertTrue("receipt arm is authoritative", pend.authoritative)
        assertEquals(
            "an existing idle-grace tightens to the short window",
            1_005L + TransitionPolicy.AUTHORITATIVE_GRACE_MS, pend.deadline,
        )
        assertEquals("the earliest destructive signal stays the honest end time", 800L, pend.since)
        assertTrue("nothing committed yet", r1.recentTasks.isEmpty())
    }

    @Test
    fun `fresh PostTask arms TASK_RETIRE stamped at the receipt's appearance`() {
        val r0 = region(dropoffTask("task-A", customerAddressHash = "cust-1", arrivedAt = 800L))
        val (r1, _) = step(r0, FlowRegion(flow = Flow.TaskDropoffArrived), postTaskObs(timestamp = 1_005L))

        assertEquals("task-A", r1.activeTask?.taskId)
        val pend = r1.pendingDestructive!!
        assertEquals(DestructiveKind.TASK_RETIRE, pend.kind)
        assertTrue(pend.authoritative)
        assertEquals(1_005L, pend.since)
        assertEquals(1_005L + TransitionPolicy.AUTHORITATIVE_GRACE_MS, pend.deadline)
    }

    @Test
    fun `a task-flow frame inside the receipt grace cancels the retire (misrecognition flap)`() {
        val r0 = region(dropoffTask("task-A", customerAddressHash = "cust-1", arrivedAt = 800L))
        val (r1, f1) = step(r0, FlowRegion(flow = Flow.TaskDropoffArrived), postTaskObs(timestamp = 1_005L))
        val (r2, _) = step(
            r1, f1,
            taskObs(Flow.TaskDropoffArrived, TaskPhase.DROPOFF, TaskSubFlow.ARRIVED, customerAddressHash = "cust-1", timestamp = 1_500L),
        )

        assertEquals("the same task lives on", "task-A", r2.activeTask?.taskId)
        assertNull("the flap cancelled the retire", r2.pendingDestructive)
        assertTrue("nothing committed", r2.recentTasks.isEmpty())
    }

    @Test
    fun `receipt grace expiry retires the task at the receipt's timestamp`() {
        val r0 = region(dropoffTask("task-A", customerAddressHash = "cust-1", arrivedAt = 800L))
        val (r1, f1) = step(r0, FlowRegion(flow = Flow.TaskDropoffArrived), postTaskObs(timestamp = 1_005L))
        val deadline = r1.pendingDestructive!!.deadline
        val (r2, _) = step(r1, f1, idleObs(timestamp = deadline + 1))

        assertNull("expiry retires the task", r2.activeTask)
        assertNull(r2.pendingDestructive)
        assertEquals(
            "completed when the receipt appeared, not when we got around to believing it",
            1_005L, r2.recentTasks.single { it.taskId == "task-A" }.completedAt,
        )
    }

    @Test
    fun `stacked next-task frame inside the receipt grace commits the old task inline`() {
        val r0 = region(dropoffTask("task-A", customerAddressHash = "cust-1", arrivedAt = 800L))
        val (r1, f1) = step(r0, FlowRegion(flow = Flow.TaskDropoffArrived), postTaskObs(timestamp = 1_005L))
        val (r2, _) = step(
            r1, f1,
            taskObs(Flow.TaskPickupNavigation, TaskPhase.PICKUP, TaskSubFlow.NAVIGATION, storeName = "Wingstop", timestamp = 1_500L),
        )

        assertEquals(TaskPhase.PICKUP, r2.activeTask?.phase)
        assertNotEquals("task-A", r2.activeTask?.taskId)
        assertEquals(
            "the displaced task committed at the receipt's appearance",
            1_005L, r2.recentTasks.single { it.taskId == "task-A" }.completedAt,
        )
        assertNull(r2.pendingDestructive)
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

    @Test
    fun `shopping item counts thread onto the task and carry forward across item screens`() {
        val r0 = region(pickupTask("task-A", "H-E-B", arrivedAt = 1_000L))
        // First shop-list reading: Done(2) / To shop(16) → total 18.
        val (r1, f1) = step(
            r0, FlowRegion(flow = Flow.TaskPickupArrived),
            taskObs(
                Flow.TaskPickupArrived, TaskPhase.PICKUP, TaskSubFlow.ARRIVED,
                storeName = "H-E-B", itemsShopped = 2, itemsRemaining = 16, timestamp = 2_000L,
            ),
        )
        assertEquals(2, r1.activeTask?.itemsShopped)
        assertEquals(16, r1.activeTask?.itemsRemaining)

        // An item-detail screen carries no counts → prior values persist.
        val (r2, _) = step(
            r1, f1,
            taskObs(Flow.TaskPickupArrived, TaskPhase.PICKUP, TaskSubFlow.ARRIVED, storeName = "H-E-B", timestamp = 3_000L),
        )
        assertEquals(2, r2.activeTask?.itemsShopped)
        assertEquals(16, r2.activeTask?.itemsRemaining)

        // Back on the list: progress advances.
        val (r3, _) = step(
            r2, f1,
            taskObs(
                Flow.TaskPickupArrived, TaskPhase.PICKUP, TaskSubFlow.ARRIVED,
                storeName = "H-E-B", itemsShopped = 13, itemsRemaining = 5, timestamp = 4_000L,
            ),
        )
        assertEquals(13, r3.activeTask?.itemsShopped)
        assertEquals(5, r3.activeTask?.itemsRemaining)
    }

    @Test
    fun `shopping add-on (or same-store stack) bumps the combined counts on the same task`() {
        // A + B orders at the same store present as one combined shop list, so an
        // add-on mid-shop just makes "To shop" jump back up while "Done" holds. The
        // metric needs no special case: it stays on the same task and total
        // (Done + To-shop) grows. (Matches the 2026-05-31 capture where To-shop
        // jumped 2 -> 8 mid-session.)
        val r0 = region(pickupTask("task-A", "H-E-B", arrivedAt = 1_000L))
        val (r1, f1) = step(
            r0, FlowRegion(flow = Flow.TaskPickupArrived),
            taskObs(
                Flow.TaskPickupArrived, TaskPhase.PICKUP, TaskSubFlow.ARRIVED,
                storeName = "H-E-B", itemsShopped = 13, itemsRemaining = 5, timestamp = 2_000L,
            ),
        )
        assertEquals(13, r1.activeTask?.itemsShopped) // total so far = 18

        // Add-on B arrives mid-shop: To-shop jumps up, Done unchanged, SAME task.
        val (r2, _) = step(
            r1, f1,
            taskObs(
                Flow.TaskPickupArrived, TaskPhase.PICKUP, TaskSubFlow.ARRIVED,
                storeName = "H-E-B", itemsShopped = 13, itemsRemaining = 13, timestamp = 3_000L,
            ),
        )
        assertEquals("task-A", r2.activeTask?.taskId) // not re-minted
        assertEquals(13, r2.activeTask?.itemsShopped)
        assertEquals(13, r2.activeTask?.itemsRemaining) // went UP — combined total now 26
    }
}

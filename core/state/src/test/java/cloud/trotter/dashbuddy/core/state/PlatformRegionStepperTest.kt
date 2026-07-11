package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPayItem
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Platform
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
 * Tests for [PlatformRegionStepper] — focused on the task-lifecycle branch
 * that handles same-phase PICKUP → PICKUP transitions (stacked pickups) vs.
 * legitimate same-task in-place updates (Unknown → resolved name, text drift,
 * parser flicker on the arrived screen).
 */
class PlatformRegionStepperTest {

    private val stepper = PlatformRegionStepper()
    private val policy = TransitionPolicy()

    // =========================================================================
    // HELPERS
    // =========================================================================

    private val platform = Platform.DoorDash
    private val session = Session("sess-1", startedAt = 100L)
    private val job = Job(jobId = "job-1", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 200L)

    private fun pickupTask(
        taskId: String,
        storeName: String,
        arrivedAt: Long? = null,
        startedAt: Long = 300L,
    ) = Task(
        taskId = taskId,
        jobId = "job-1",
        phase = TaskPhase.PICKUP,
        storeName = storeName,
        startedAt = startedAt,
        arrivedAt = arrivedAt,
    )

    private fun region(activeTask: Task?) = PlatformRegion(
        platform = platform,
        mode = Mode.Online,
        session = session,
        activeJob = job,
        activeTask = activeTask,
    )

    private fun screenObs(
        flow: Flow,
        storeName: String?,
        phase: TaskPhase,
        subFlow: TaskSubFlow,
        customerNameHash: String? = null,
        customerAddressHash: String? = null,
        timestamp: Long = 1_000L,
    ): Observation.Screen = Observation.Screen(
        timestamp = timestamp,
        captureId = "cap-$timestamp",
        ruleId = "doordash.screen.test",
        metadata = ReplayMetadata.EMPTY,
        flow = flow,
        modeHint = Mode.Online,
        parsed = ParsedFields.TaskFields(
            storeName = storeName,
            phase = phase,
            subFlow = subFlow,
            customerNameHash = customerNameHash,
            customerAddressHash = customerAddressHash,
        ),
    )

    // =========================================================================
    // STACKED-PICKUP MINT-NEW-TASK
    // =========================================================================

    @Test
    fun `stacked pickup mints a new task with a different taskId`() {
        val costaPacifica = pickupTask("task-A", "Costa Pacifica", arrivedAt = 800L)
        val prev = region(activeTask = costaPacifica)

        val next = stepper.step(
            prev = prev,
            prevFlow = FlowRegion(flow = Flow.TaskPickupArrived),
            nextFlow = FlowRegion(flow = Flow.TaskPickupNavigation),
            obs = screenObs(
                flow = Flow.TaskPickupNavigation,
                storeName = "Chili's Grill & Bar",
                phase = TaskPhase.PICKUP,
                subFlow = TaskSubFlow.NAVIGATION,
            ),
            policy = policy,
        )

        assertNotNull("activeTask should be set", next.activeTask)
        assertNotEquals(
            "Stacked pickup should mint a new taskId",
            costaPacifica.taskId,
            next.activeTask?.taskId,
        )
        assertEquals(
            "New task should carry the new storeName",
            "Chili's Grill & Bar",
            next.activeTask?.storeName,
        )
        assertNull(
            "New task should reset arrivedAt to null (will be set on Chili's arrival)",
            next.activeTask?.arrivedAt,
        )
        assertTrue(
            "Costa Pacifica task should be moved to recentTasks with completedAt",
            next.recentTasks.any { it.taskId == "task-A" && it.completedAt != null },
        )
    }

    // =========================================================================
    // #565 — DROPOFF placeholder resolution vs genuine stacked dropoff
    // =========================================================================

    @Test
    fun `customer-bearing nav resolves onto an active customer-less placeholder dropoff, no re-mint (#565)`() {
        // A genuine handoff screen activated the pre-created placeholder dropoff customer-less; now the
        // first customer-bearing nav frame arrives. It MUST fill in the placeholder, not mint a new task.
        val placeholder = Task(
            taskId = "task-placeholder", jobId = "job-1", phase = TaskPhase.DROPOFF,
            customerNameHash = null, customerAddressHash = null,
            startedAt = 300L, arrivedAt = 800L,
        )
        val next = stepper.step(
            prev = region(activeTask = placeholder),
            prevFlow = FlowRegion(flow = Flow.TaskDropoffArrived),
            nextFlow = FlowRegion(flow = Flow.TaskDropoffNavigation),
            obs = screenObs(
                flow = Flow.TaskDropoffNavigation, storeName = null,
                phase = TaskPhase.DROPOFF, subFlow = TaskSubFlow.NAVIGATION,
                customerNameHash = "cust-real", customerAddressHash = "addr-real",
            ),
            policy = policy,
        )
        assertEquals(
            "the customer resolves onto the placeholder, not a fresh mint",
            "task-placeholder", next.activeTask?.taskId,
        )
        assertEquals("cust-real", next.activeTask?.customerNameHash)
        assertTrue(
            "no customer-less dropoff husk should be displaced into recentTasks",
            next.recentTasks.none { it.phase == TaskPhase.DROPOFF && it.customerNameHash == null },
        )
    }

    @Test
    fun `a different real customer dropoff nav still mints a distinct task (#565 keeps genuine stacks)`() {
        // The guard must not over-suppress: arrived at customer A, now navigating to a DIFFERENT
        // customer B → still a distinct stacked dropoff.
        val custA = Task(
            taskId = "task-A", jobId = "job-1", phase = TaskPhase.DROPOFF,
            customerNameHash = "cust-A", customerAddressHash = "addr-A",
            startedAt = 300L, arrivedAt = 800L,
        )
        val next = stepper.step(
            prev = region(activeTask = custA),
            prevFlow = FlowRegion(flow = Flow.TaskDropoffArrived),
            nextFlow = FlowRegion(flow = Flow.TaskDropoffNavigation),
            obs = screenObs(
                flow = Flow.TaskDropoffNavigation, storeName = null,
                phase = TaskPhase.DROPOFF, subFlow = TaskSubFlow.NAVIGATION,
                customerNameHash = "cust-B", customerAddressHash = "addr-B",
            ),
            policy = policy,
        )
        assertNotEquals(
            "a genuinely different customer still starts a distinct dropoff",
            "task-A", next.activeTask?.taskId,
        )
        assertEquals("cust-B", next.activeTask?.customerNameHash)
    }

    // =========================================================================
    // FALSE-POSITIVE GUARDS — these must NOT mint new tasks
    // =========================================================================

    @Test
    fun `Unknown to resolved name pre-arrival keeps the same task`() {
        val initial = pickupTask("task-A", "Unknown", arrivedAt = null)
        val prev = region(activeTask = initial)

        val next = stepper.step(
            prev = prev,
            prevFlow = FlowRegion(flow = Flow.TaskPickupNavigation),
            nextFlow = FlowRegion(flow = Flow.TaskPickupNavigation),
            obs = screenObs(
                flow = Flow.TaskPickupNavigation,
                storeName = "Costa Pacifica",
                phase = TaskPhase.PICKUP,
                subFlow = TaskSubFlow.NAVIGATION,
            ),
            policy = policy,
        )

        assertEquals(
            "Unknown→resolved should NOT mint a new task",
            "task-A",
            next.activeTask?.taskId,
        )
        assertEquals("Costa Pacifica", next.activeTask?.storeName)
    }

    @Test
    fun `text drift pre-arrival keeps the same task`() {
        val initial = pickupTask("task-A", "Best Buy", arrivedAt = null)
        val prev = region(activeTask = initial)

        val next = stepper.step(
            prev = prev,
            prevFlow = FlowRegion(flow = Flow.TaskPickupNavigation),
            nextFlow = FlowRegion(flow = Flow.TaskPickupNavigation),
            obs = screenObs(
                flow = Flow.TaskPickupNavigation,
                storeName = "Best Buy #1234",
                phase = TaskPhase.PICKUP,
                subFlow = TaskSubFlow.NAVIGATION,
            ),
            policy = policy,
        )

        assertEquals(
            "Pre-arrival text drift should NOT mint a new task (arrivedAt gate)",
            "task-A",
            next.activeTask?.taskId,
        )
        assertEquals("Best Buy #1234", next.activeTask?.storeName)
    }

    @Test
    fun `parser flicker on the ARRIVED screen keeps the same task`() {
        // Simulates the doordash pickup_arrival storeName parser bug:
        // the wrong instructions_title wins (e.g. "Walk into store"). The
        // stepper must not mint a new task just because storeName changed,
        // since the subFlow is still ARRIVED.
        val arrived = pickupTask("task-A", "Costa Pacifica", arrivedAt = 800L)
        val prev = region(activeTask = arrived)

        val next = stepper.step(
            prev = prev,
            prevFlow = FlowRegion(flow = Flow.TaskPickupArrived),
            nextFlow = FlowRegion(flow = Flow.TaskPickupArrived),
            obs = screenObs(
                flow = Flow.TaskPickupArrived,
                storeName = "Walk into store",
                phase = TaskPhase.PICKUP,
                subFlow = TaskSubFlow.ARRIVED,
            ),
            policy = policy,
        )

        assertEquals(
            "ARRIVED-screen parser flicker should NOT mint a new task (subFlow gate)",
            "task-A",
            next.activeTask?.taskId,
        )
    }

    @Test
    fun `classifier flicker NAVIGATION re-entry on the same store keeps the same task`() {
        // Simulates a brief classifier flip ARRIVED → NAVIGATION → ARRIVED
        // on the same store: the storeName hasn't changed, so the storeName
        // gate prevents a spurious mint.
        val arrived = pickupTask("task-A", "Costa Pacifica", arrivedAt = 800L)
        val prev = region(activeTask = arrived)

        val next = stepper.step(
            prev = prev,
            prevFlow = FlowRegion(flow = Flow.TaskPickupArrived),
            nextFlow = FlowRegion(flow = Flow.TaskPickupNavigation),
            obs = screenObs(
                flow = Flow.TaskPickupNavigation,
                storeName = "Costa Pacifica",
                phase = TaskPhase.PICKUP,
                subFlow = TaskSubFlow.NAVIGATION,
            ),
            policy = policy,
        )

        assertEquals(
            "Same-store NAVIGATION re-entry should NOT mint a new task (storeName gate)",
            "task-A",
            next.activeTask?.taskId,
        )
    }

    @Test
    fun `PICKUP to DROPOFF still mints a new task (regression guard)`() {
        val pickup = pickupTask("task-A", "Costa Pacifica", arrivedAt = 800L)
        val prev = region(activeTask = pickup)

        val next = stepper.step(
            prev = prev,
            prevFlow = FlowRegion(flow = Flow.TaskPickupArrived),
            nextFlow = FlowRegion(flow = Flow.TaskDropoffNavigation),
            obs = screenObs(
                flow = Flow.TaskDropoffNavigation,
                storeName = null,
                phase = TaskPhase.DROPOFF,
                subFlow = TaskSubFlow.NAVIGATION,
                // A real dropoff_navigation always carries a customer hash; an identity-less
                // dropoff frame is suppressed by the #498 phantom guard (see TaskLifecycleGuardTest).
                customerNameHash = "cust-name-1",
                customerAddressHash = "cust-addr-1",
            ),
            policy = policy,
        )

        assertNotEquals(
            "Phase change PICKUP→DROPOFF should mint a new task",
            "task-A",
            next.activeTask?.taskId,
        )
        assertEquals(TaskPhase.DROPOFF, next.activeTask?.phase)
    }

    // =========================================================================
    // #630 R3 — no expanded→collapsed receipt downgrade for the SAME announced task
    // =========================================================================

    private fun postTaskObs(
        parsedPay: ParsedPay?,
        totalPay: Double,
        sessionEarnings: Double?,
        timestamp: Long,
    ): Observation.Screen = Observation.Screen(
        timestamp = timestamp,
        captureId = "cap-$timestamp",
        ruleId = "doordash.screen.test",
        metadata = ReplayMetadata.EMPTY,
        flow = Flow.PostTask,
        modeHint = Mode.Online,
        parsed = ParsedFields.PostTaskFields(
            totalPay = totalPay,
            parsedPay = parsedPay,
            sessionEarnings = sessionEarnings,
        ),
    )

    private val expandedReceipt = ParsedPay(
        appPayComponents = listOf(ParsedPayItem("Base Pay", 4.5)),
        customerTips = listOf(ParsedPayItem("Wendy's", 3.0)),
    )

    @Test
    fun `same-task collapsed PostTask re-render does not clobber the captured expanded receipt (#630 R3)`() {
        val task = pickupTask("task-D", "Wendy's")
        val prev = region(activeTask = task)

        // Frame 1: EXPANDED receipt for task-D.
        val afterExpanded = stepper.step(
            prev = prev,
            prevFlow = FlowRegion(flow = Flow.PostTask),
            nextFlow = FlowRegion(flow = Flow.PostTask),
            obs = postTaskObs(expandedReceipt, totalPay = 7.5, sessionEarnings = 50.0, timestamp = 1_000L),
            policy = policy,
        )
        assertNotNull("expanded receipt captured", afterExpanded.lastPostTaskFields?.parsedPay)
        assertEquals("task-D", afterExpanded.lastAnnouncedPostTaskTaskId)

        // Frame 2: a COLLAPSED re-render (parsedPay = null) for the SAME announced task — e.g. a
        // PostTask re-entry after a chained-offer decline renders collapsed first.
        val afterCollapsed = stepper.step(
            prev = afterExpanded,
            prevFlow = FlowRegion(flow = Flow.PostTask),
            nextFlow = FlowRegion(flow = Flow.PostTask),
            obs = postTaskObs(parsedPay = null, totalPay = 7.5, sessionEarnings = 51.0, timestamp = 2_000L),
            policy = policy,
        )

        assertNotNull(
            "the itemized (expanded) receipt survives a same-task collapsed re-render — the retire-" +
                "grace close-out must apportion off the EXPANDED parsedPay, never apportion(null)",
            afterCollapsed.lastPostTaskFields?.parsedPay,
        )
        assertEquals(
            "sessionEarnings still folds from the collapsed frame",
            51.0,
            afterCollapsed.session?.runningEarnings,
        )
    }

    @Test
    fun `different-task collapsed PostTask frame DOES overwrite (a new receipt, not a downgrade)`() {
        val taskD = pickupTask("task-D", "Wendy's")
        val prev = region(activeTask = taskD).copy(
            // An expanded receipt already held for a PRIOR task.
            lastPostTaskFields = ParsedFields.PostTaskFields(
                totalPay = 12.0,
                parsedPay = expandedReceipt,
                sessionEarnings = 40.0,
            ),
            lastAnnouncedPostTaskTaskId = "task-C",
        )

        val next = stepper.step(
            prev = prev,
            prevFlow = FlowRegion(flow = Flow.PostTask),
            nextFlow = FlowRegion(flow = Flow.PostTask),
            obs = postTaskObs(parsedPay = null, totalPay = 7.5, sessionEarnings = 51.0, timestamp = 2_000L),
            policy = policy,
        )

        assertNull(
            "a DIFFERENT task's collapsed receipt is a genuinely NEW receipt and overwrites",
            next.lastPostTaskFields?.parsedPay,
        )
        assertEquals(7.5, next.lastPostTaskFields?.totalPay)
        assertEquals("task-D", next.lastAnnouncedPostTaskTaskId)
        assertEquals("sessionEarnings still folds", 51.0, next.session?.runningEarnings)
    }

}

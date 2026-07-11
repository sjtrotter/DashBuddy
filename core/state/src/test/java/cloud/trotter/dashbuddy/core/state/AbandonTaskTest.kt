package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.TaskUnassignedPayload
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.PickupActivity
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

/**
 * #736 — the unassign-abandon vocabulary. Covers the stepper's inline `abandonActiveTask`
 * (a `Flow.TaskUnassigned` frame) and the EffectMap guards that stop a fabricated
 * `PICKUP_CONFIRMED`/`DELIVERY_CONFIRMED` for an abandoned task.
 */
class AbandonTaskTest {

    private val stepper = PlatformRegionStepper()
    private val policy = TransitionPolicy()
    private val effectMap = EffectMap()
    private val platform = Platform.DoorDash
    private val session = Session("sess-1", startedAt = 100L)

    // ---- helpers ------------------------------------------------------------

    private fun pickup(
        taskId: String,
        store: String = "H-E-B",
        arrivedAt: Long? = 400L,
        activity: String? = PickupActivity.SHOPPING,
        itemsShopped: Int? = 6,
        customerNameHash: String? = null,
        unassignedAt: Long? = null,
        completedAt: Long? = null,
    ) = Task(
        taskId = taskId, jobId = "job-1", phase = TaskPhase.PICKUP,
        storeName = store, arrivedAt = arrivedAt, activity = activity, itemsShopped = itemsShopped,
        customerNameHash = customerNameHash, startedAt = 300L,
        unassignedAt = unassignedAt, completedAt = completedAt,
    )

    private fun dropoffPlaceholder(taskId: String, customerNameHash: String? = null) = Task(
        taskId = taskId, jobId = "job-1", phase = TaskPhase.DROPOFF,
        customerNameHash = customerNameHash, startedAt = 300L,
    )

    private fun job(tasks: List<Task>) =
        Job(jobId = "job-1", offerStoreHint = listOf("H-E-B"), parentOfferHash = null, startedAt = 200L, tasks = tasks)

    private fun region(
        activeTask: Task?,
        activeJob: Job?,
        recentTasks: List<Task> = emptyList(),
        pending: PendingDestructive? = null,
    ) = PlatformRegion(
        platform = platform, mode = Mode.Online, session = session,
        activeJob = activeJob, activeTask = activeTask, recentTasks = recentTasks,
        pendingDestructive = pending, lastActedFlow = Flow.TaskPickupArrived,
    )

    private fun unassignObs(ts: Long = 1_000L) = Observation.Screen(
        timestamp = ts, captureId = "cap-$ts", ruleId = "doordash.screen.pickup_unassigned_confirmation",
        metadata = ReplayMetadata.EMPTY, flow = Flow.TaskUnassigned, modeHint = Mode.Online,
        parsed = ParsedFields.None,
    )

    private fun step(r: PlatformRegion, obs: Observation.Screen, prevFlow: Flow = Flow.TaskPickupArrived) =
        stepper.step(r, FlowRegion(flow = prevFlow), FlowRegion(flow = Flow.TaskUnassigned), obs, policy)

    // ---- stepper cases ------------------------------------------------------

    @Test
    fun `abandon of an arrived pickup marks unassignedAt, leaves completedAt null, closes the single-order job`() {
        val active = pickup("pk")
        val next = step(region(active, job(listOf(dropoffPlaceholder("dO")))), unassignObs())

        assertNull("activeTask cleared", next.activeTask)
        val abandoned = next.recentTasks.single { it.taskId == "pk" }
        assertEquals("marker set to the frame time", 1_000L, abandoned.unassignedAt)
        assertNull("an abandoned task is NEVER completed", abandoned.completedAt)
        assertNull("single-order job closed on the confirmation frame", next.activeJob)
    }

    @Test
    fun `the second confirmation frame is a no-op (activeTask and job already gone)`() {
        val prior = region(activeTask = null, activeJob = null, recentTasks = listOf(pickup("pk", unassignedAt = 1_000L)))
        val next = step(prior, unassignObs(ts = 1_100L))

        assertNull(next.activeTask)
        assertNull(next.activeJob)
        assertEquals("recentTasks unchanged", listOf("pk"), next.recentTasks.map { it.taskId })
        assertEquals("the existing marker is untouched", 1_000L, next.recentTasks.single().unassignedAt)
    }

    @Test
    fun `a pending TASK_RETIRE is superseded by the abandon`() {
        val pend = PendingDestructive(DestructiveKind.TASK_RETIRE, since = 900L, deadline = 3_000L)
        val next = step(region(pickup("pk"), job(listOf(dropoffPlaceholder("dO"))), pending = pend), unassignObs())
        assertNull("TASK_RETIRE cleared", next.pendingDestructive)
    }

    @Test
    fun `a pending SESSION_END survives the abandon`() {
        val pend = PendingDestructive(DestructiveKind.SESSION_END, since = 900L, deadline = 3_000L)
        val next = step(region(pickup("pk"), job(listOf(dropoffPlaceholder("dO"))), pending = pend), unassignObs())
        assertEquals("SESSION_END preserved (the graced-offline state)", DestructiveKind.SESSION_END, next.pendingDestructive?.kind)
    }

    @Test
    fun `multi-dropoff abandon with a matching hash removes only the matching placeholder and the job survives`() {
        val active = pickup("pk", customerNameHash = "cust-B")
        val j = job(listOf(dropoffPlaceholder("dA", "cust-A"), dropoffPlaceholder("dB", "cust-B")))
        val next = step(region(active, j), unassignObs())

        val dropIds = next.activeJob!!.tasks.filter { it.phase == TaskPhase.DROPOFF }.map { it.taskId }.toSet()
        assertEquals("only the abandoned order's drop (dB) is removed", setOf("dA"), dropIds)
        assertTrue("job stays open (dA still outstanding)", next.activeJob != null)
    }

    @Test
    fun `multi-dropoff abandon with no customer hash removes nothing and the job survives`() {
        val active = pickup("pk", customerNameHash = null)
        val j = job(listOf(dropoffPlaceholder("dA"), dropoffPlaceholder("dB")))
        val next = step(region(active, j), unassignObs())

        val dropIds = next.activeJob!!.tasks.filter { it.phase == TaskPhase.DROPOFF }.map { it.taskId }.toSet()
        assertEquals("no drop removed (absorption over fabrication)", setOf("dA", "dB"), dropIds)
    }

    @Test
    fun `resume self-heal clears unassignedAt when a genuine same-order frame reactivates the task`() {
        val abandoned = pickup("pk", unassignedAt = 500L)
        val r = region(activeTask = null, activeJob = job(emptyList()), recentTasks = listOf(abandoned))
        val navObs = Observation.Screen(
            timestamp = 2_000L, captureId = "c", ruleId = "doordash.screen.pickup_navigation",
            metadata = ReplayMetadata.EMPTY, flow = Flow.TaskPickupNavigation, modeHint = Mode.Online,
            parsed = ParsedFields.TaskFields(storeName = "H-E-B", phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION),
        )
        val next = stepper.step(r, FlowRegion(flow = Flow.TaskUnassigned), FlowRegion(flow = Flow.TaskPickupNavigation), navObs, policy)

        assertEquals("resumed the same task identity", "pk", next.activeTask?.taskId)
        assertNull("the unassign marker is healed away", next.activeTask?.unassignedAt)
    }

    // ---- effect cases -------------------------------------------------------

    private fun state(region: PlatformRegion, flow: Flow) = AppState(
        regions = Regions(
            flow = FlowRegion(flow = flow, activePlatform = platform),
            platforms = mapOf(platform to region),
        ),
    )

    private fun List<AppEffect>.logTypes() = filterIsInstance<AppEffect.LogEvent>().map { it.event.type }

    @Test
    fun `the close-out sweep never confirms an abandoned arrived pickup, but DOES emit one TASK_UNASSIGNED`() {
        val prevRegion = region(pickup("pk"), job(listOf(dropoffPlaceholder("dO")))).copy(lastActedFlow = Flow.TaskPickupArrived)
        val nextRegion = region(null, null, recentTasks = listOf(pickup("pk", unassignedAt = 1_000L))).copy(lastActedFlow = Flow.TaskUnassigned)

        val effects = effectMap.diff(state(prevRegion, Flow.TaskPickupArrived), state(nextRegion, Flow.TaskUnassigned), unassignObs())

        assertFalse("no fabricated PICKUP_CONFIRMED (the seq-71 bug)", effects.logTypes().contains(AppEventType.PICKUP_CONFIRMED))
        assertFalse("no RecordShopRate for the abandoned shop", effects.any { it is AppEffect.RecordShopRate })
        assertEquals("exactly one TASK_UNASSIGNED", 1, effects.logTypes().count { it == AppEventType.TASK_UNASSIGNED })
        val payload = effects.filterIsInstance<AppEffect.LogEvent>().first { it.event.type == AppEventType.TASK_UNASSIGNED }.event.payload as TaskUnassignedPayload
        assertEquals("job-1", payload.jobId)
        assertEquals("pk", payload.taskId)
        assertEquals(TaskPhase.PICKUP, payload.phase)
        assertTrue("an 'Unassigned:' bubble fires", effects.filterIsInstance<AppEffect.UpdateBubble>().any { it.text.startsWith("Unassigned:") })
    }

    @Test
    fun `CONTROL - the very same close-out DOES confirm the pickup when it was NOT unassigned`() {
        val prevRegion = region(pickup("pk"), job(listOf(dropoffPlaceholder("dO")))).copy(lastActedFlow = Flow.TaskPickupArrived)
        // Same shape, but the pickup is a normal completed arrival (no unassign marker).
        val nextRegion = region(null, null, recentTasks = listOf(pickup("pk", completedAt = 900L))).copy(lastActedFlow = Flow.Idle)

        val effects = effectMap.diff(state(prevRegion, Flow.TaskPickupArrived), state(nextRegion, Flow.Idle), unassignObs())
        assertTrue("without the marker the sweep still confirms — proving the filter is load-bearing", effects.logTypes().contains(AppEventType.PICKUP_CONFIRMED))
    }

    @Test
    fun `a dropoff-phase abandon suppresses DELIVERY_CONFIRMED`() {
        val activeDrop = Task(taskId = "dr", jobId = "job-1", phase = TaskPhase.DROPOFF, customerNameHash = "cust", arrivedAt = 500L, startedAt = 300L)
        val prevRegion = region(activeDrop, job(listOf(activeDrop))).copy(lastActedFlow = Flow.TaskDropoffArrived)
        val nextRegion = region(null, null, recentTasks = listOf(activeDrop.copy(unassignedAt = 1_000L))).copy(lastActedFlow = Flow.TaskUnassigned)

        val effects = effectMap.diff(state(prevRegion, Flow.TaskDropoffArrived), state(nextRegion, Flow.TaskUnassigned), unassignObs())
        assertFalse("an unassigned dropoff must not mint DELIVERY_CONFIRMED", effects.logTypes().contains(AppEventType.DELIVERY_CONFIRMED))
    }

    @Test
    fun `delivered-A then unassign-B still mints A's completion only`() {
        val a = Task(taskId = "dA", jobId = "job-1", phase = TaskPhase.DROPOFF, customerNameHash = "cust-A", arrivedAt = 400L, completedAt = 900L, startedAt = 300L)
        val b = Task(taskId = "dB", jobId = "job-1", phase = TaskPhase.DROPOFF, customerNameHash = "cust-B", arrivedAt = 500L, unassignedAt = 1_000L, startedAt = 300L)
        val prevRegion = PlatformRegion(platform = platform, mode = Mode.Online, session = session, activeJob = job(listOf(a, b)), activeTask = null, recentTasks = listOf(a), lastActedFlow = Flow.TaskDropoffArrived)
        val nextRegion = PlatformRegion(platform = platform, mode = Mode.Online, session = session, activeJob = null, activeTask = null, recentTasks = listOf(a, b), lastActedFlow = Flow.TaskUnassigned)

        val effects = effectMap.diff(state(prevRegion, Flow.TaskDropoffArrived), state(nextRegion, Flow.TaskUnassigned), unassignObs())
        val completed = effects.filterIsInstance<AppEffect.LogEvent>().filter { it.event.type == AppEventType.DELIVERY_COMPLETED }
        assertEquals("only A completes (B was abandoned, never completed)", 1, completed.size)
    }
}

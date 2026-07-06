package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.PickupPayload
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.CrossPlatformRegion
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #526 D5 CONFIRM SWEEP — replaces the old per-edge pickup→pickup displacement confirm.
 *
 * New design: a pickup→pickup active-task change emits NOTHING new (the next pickup's nav fires on
 * its own). Every arrived pickup in the job's lineage is confirmed at the pickup→dropoff edge (and,
 * for a job that closes without a dropoff edge, at the #596 close-out), each at its OWN completion
 * time, keyed on its task id so the two sweep sites are idempotent under effects_fired.
 */
class PickupConfirmStackTest {

    private val effectMap = EffectMap()

    private fun appState(flow: FlowRegion, region: PlatformRegion) = AppState(
        regions = Regions(flow = flow, platforms = mapOf(Platform.DoorDash to region), crossPlatform = CrossPlatformRegion()),
    )

    private fun pickup(taskId: String, store: String, arrivedAt: Long?, startedAt: Long, completedAt: Long? = null) = Task(
        taskId = taskId, jobId = "J1", phase = TaskPhase.PICKUP, storeName = store,
        arrivedAt = arrivedAt, startedAt = startedAt, completedAt = completedAt,
    )

    private fun region(active: Task?, recentTasks: List<Task> = emptyList(), activeJob: Job? = null) = PlatformRegion(
        platform = Platform.DoorDash, mode = Mode.Online, session = Session("s1", startedAt = 100L),
        activeTask = active, recentTasks = recentTasks, activeJob = activeJob,
    )

    private fun screenObs(flow: Flow, t: Long) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "test.rule", metadata = ReplayMetadata.EMPTY,
        flow = flow, modeHint = Mode.Online, parsed = ParsedFields.None,
    )

    private fun logEvents(prev: AppState, next: AppState, obs: Observation) =
        effectMap.diff(prev, next, obs).filterIsInstance<AppEffect.LogEvent>()

    private fun confirms(events: List<AppEffect.LogEvent>) =
        events.filter { it.event.type == AppEventType.PICKUP_CONFIRMED }

    @Test
    fun `a pickup to pickup edge emits NO PICKUP_CONFIRMED (sweep design)`() {
        // The displaced pickup is confirmed later, at the dropoff-edge sweep — NOT here.
        val a = pickup("T-A", "Bill Miller BBQ", arrivedAt = 1_800L, startedAt = 1_000L, completedAt = 2_000L)
        val b = pickup("T-B", "Mama Margies", arrivedAt = null, startedAt = 2_000L)
        val prev = appState(FlowRegion(flow = Flow.TaskPickupArrived), region(a))
        val next = appState(FlowRegion(flow = Flow.TaskPickupNavigation), region(b, recentTasks = listOf(a)))
        val events = logEvents(prev, next, screenObs(Flow.TaskPickupNavigation, 2_000L))

        assertTrue("no PICKUP_CONFIRMED on a pickup→pickup edge", confirms(events).isEmpty())
        assertTrue("the new pickup's nav still fires", events.any { it.event.type == AppEventType.PICKUP_NAV_STARTED })
        assertTrue("no delivery nav on a pickup→pickup edge", events.none { it.event.type == AppEventType.DELIVERY_NAV_STARTED })
    }

    @Test
    fun `both stacked pickups are confirmed at the pickup to dropoff edge, each at its own time`() {
        val a = pickup("T-A", "Bill Miller BBQ", arrivedAt = 1_800L, startedAt = 1_000L, completedAt = 2_000L)
        val b = pickup("T-B", "Mama Margies", arrivedAt = 2_500L, startedAt = 2_000L, completedAt = 3_000L)
        val d = Task(taskId = "T-D", jobId = "J1", phase = TaskPhase.DROPOFF, startedAt = 3_000L, customerNameHash = "cx")
        val prev = appState(FlowRegion(flow = Flow.TaskPickupArrived), region(b, recentTasks = listOf(a)))
        val next = appState(
            FlowRegion(flow = Flow.TaskDropoffNavigation),
            region(d, recentTasks = listOf(a, b)),
        )
        val events = logEvents(prev, next, screenObs(Flow.TaskDropoffNavigation, 3_000L))

        val confirmed = confirms(events).associate { (it.event.payload as PickupPayload).taskId to it.event.occurredAt }
        assertEquals("both pickups confirmed at the dropoff edge", setOf("T-A", "T-B"), confirmed.keys)
        assertEquals("T-A confirms at its own completion time", 2_000L, confirmed["T-A"])
        assertEquals("T-B confirms at its own completion time", 3_000L, confirmed["T-B"])

        // CONFIRMED-before-NAV ordering.
        val allTypes = events.map { it.event.type }
        val lastConfirm = allTypes.indexOfLast { it == AppEventType.PICKUP_CONFIRMED }
        val navIdx = allTypes.indexOfFirst { it == AppEventType.DELIVERY_NAV_STARTED }
        assertTrue("CONFIRMED precedes DELIVERY_NAV_STARTED", lastConfirm in 0 until navIdx)
    }

    @Test
    fun `the sweep is keyed on the confirmed task id`() {
        val a = pickup("T-A", "Bill Miller BBQ", arrivedAt = 1_800L, startedAt = 1_000L, completedAt = 2_000L)
        val d = Task(taskId = "T-D", jobId = "J1", phase = TaskPhase.DROPOFF, startedAt = 3_000L, customerNameHash = "cx")
        val prev = appState(FlowRegion(flow = Flow.TaskPickupArrived), region(a))
        val next = appState(FlowRegion(flow = Flow.TaskDropoffNavigation), region(d, recentTasks = listOf(a)))
        val events = logEvents(prev, next, screenObs(Flow.TaskDropoffNavigation, 3_000L))
        assertEquals("log:PICKUP_CONFIRMED:T-A", confirms(events).single().effectKey)
    }

    @Test
    fun `the sweep confirms only ARRIVED pickups (an un-arrived flap sibling is skipped)`() {
        // A→B→A flap where B never arrived: at A→dropoff only A (arrived) is confirmed, ONCE.
        val a = pickup("T-A", "Bill Miller BBQ", arrivedAt = 1_800L, startedAt = 1_000L, completedAt = 3_000L)
        val bNeverArrived = pickup("T-B", "Mama Margies", arrivedAt = null, startedAt = 2_000L, completedAt = 2_500L)
        val d = Task(taskId = "T-D", jobId = "J1", phase = TaskPhase.DROPOFF, startedAt = 3_000L, customerNameHash = "cx")
        val prev = appState(FlowRegion(flow = Flow.TaskPickupArrived), region(a))
        val next = appState(FlowRegion(flow = Flow.TaskDropoffNavigation), region(d, recentTasks = listOf(bNeverArrived, a)))
        val events = logEvents(prev, next, screenObs(Flow.TaskDropoffNavigation, 3_000L))
        assertEquals("only the arrived pickup is confirmed, once", listOf("T-A"),
            confirms(events).map { (it.event.payload as PickupPayload).taskId })
    }

    @Test
    fun `close-out sweep confirms arrived pickups for a job that closes without a dropoff edge`() {
        // Job closes (activeJob goes null) with an arrived pickup still in the lineage.
        val a = pickup("T-A", "Bill Miller BBQ", arrivedAt = 1_800L, startedAt = 1_000L, completedAt = 2_000L)
        val job = Job(jobId = "J1", offerStoreHint = listOf("Bill Miller BBQ"), parentOfferHash = "o1", startedAt = 100L)
        val prev = appState(FlowRegion(flow = Flow.Idle), region(active = null, recentTasks = listOf(a), activeJob = job))
        val next = appState(FlowRegion(flow = Flow.Idle), region(active = null, recentTasks = listOf(a), activeJob = null))
        val events = logEvents(prev, next, screenObs(Flow.Idle, 4_000L))
        assertEquals("the close-out sweep confirms the arrived pickup", listOf("T-A"),
            confirms(events).map { (it.event.payload as PickupPayload).taskId })
    }
}

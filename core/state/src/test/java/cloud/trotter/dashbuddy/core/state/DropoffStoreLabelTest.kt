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
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #526 — a dropoff's store is resolved from the job's PICKUP lineage, not from the dropoff card's
 * own (per-merchant-variable, often unparseable) text. A dropoff always follows its pickup, and the
 * job already holds the pickups with their authoritative store names:
 *  - single pickup store → every dropoff is that store (no dropoff parse; the common 8/9 case);
 *  - multiple pickup stores → match the dropoff's parsed candidate to a pickup by shared leading
 *    name tokens, which also validates (garbage candidate → no match → null, never a wrong store).
 *
 * This is the proper fix for the 06-19 Target+Maple stack mislabel (the Target drop had inherited
 * the active Maple pickup's store).
 */
class DropoffStoreLabelTest {

    private val stepper = PlatformRegionStepper()
    private val flowStepper = FlowRegionStepper()
    private val policy = TransitionPolicy()

    private fun completedPickup(id: String, store: String) = Task(
        taskId = id, jobId = "job-1", phase = TaskPhase.PICKUP,
        storeName = store, arrivedAt = 300L, startedAt = 200L, completedAt = 400L,
    )

    private fun region(pickups: List<Task>, activeTask: Task? = null) = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Online,
        session = Session("sess-1", startedAt = 100L),
        activeJob = Job("job-1", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 150L),
        activeTask = activeTask,
        recentTasks = pickups,
    )

    private fun dropoffObs(timestamp: Long, store: String? = null, customer: String) =
        Observation.Screen(
            timestamp = timestamp, captureId = null, ruleId = "doordash.screen.dropoff_pre_arrival",
            metadata = ReplayMetadata.EMPTY, flow = Flow.TaskDropoffNavigation, modeHint = Mode.Online,
            parsed = ParsedFields.TaskFields(
                phase = TaskPhase.DROPOFF, subFlow = TaskSubFlow.NAVIGATION,
                storeName = store, customerNameHash = customer, customerAddressHash = "addr-$customer",
            ),
        )

    private fun step(prev: PlatformRegion, prevFlow: FlowRegion, obs: Observation): Pair<PlatformRegion, FlowRegion> {
        val nextFlow = if (obs is Observation.FlowObservation) flowStepper.step(prevFlow, obs) else prevFlow
        return stepper.step(prev, prevFlow, nextFlow, obs, policy) to nextFlow
    }

    @Test
    fun `single-store job — the dropoff takes the pickup store with no dropoff parse`() {
        val (r, _) = step(
            region(pickups = listOf(completedPickup("pick-1", "H-E-B"))),
            FlowRegion(flow = Flow.Idle),
            dropoffObs(1_000L, store = null, customer = "cust-1"),
        )
        assertEquals(TaskPhase.DROPOFF, r.activeTask?.phase)
        assertEquals("a single-pickup job's dropoff is that pickup's store", "H-E-B", r.activeTask?.storeName)
    }

    @Test
    fun `single-store job — a dropoff card's running-key form resolves to the canonical pickup store`() {
        val (r, _) = step(
            region(pickups = listOf(completedPickup("pick-1", "H-E-B"))),
            FlowRegion(flow = Flow.Idle),
            dropoffObs(1_000L, store = "H-E-B plus! #1055", customer = "cust-1"),
        )
        assertEquals("the dropoff is canonicalised to the pickup's store name", "H-E-B", r.activeTask?.storeName)
    }

    @Test
    fun `multi-store stack — each dropoff matches its own pickup, not the last active one`() {
        val pickups = listOf(
            completedPickup("pick-target", "Target"),
            completedPickup("pick-maple", "Maple Street Biscuit Company"),
        )
        val (rTarget, _) = step(
            region(pickups), FlowRegion(flow = Flow.Idle),
            dropoffObs(1_000L, store = "Target (02426)", customer = "cust-target"),
        )
        assertEquals("the Target drop resolves to Target (not the last pickup, Maple)", "Target", rTarget.activeTask?.storeName)

        val (rMaple, _) = step(
            region(pickups), FlowRegion(flow = Flow.Idle),
            dropoffObs(2_000L, store = "Maple Street Biscuit - Alamo Ranch", customer = "cust-maple"),
        )
        assertEquals(
            "the Maple drop resolves to Maple via shared leading tokens (cores differ: '- Alamo Ranch' vs 'Company')",
            "Maple Street Biscuit Company", rMaple.activeTask?.storeName,
        )
    }

    @Test
    fun `multi-store stack — a garbage candidate matches no pickup and resolves to null (never a wrong store)`() {
        val pickups = listOf(
            completedPickup("pick-target", "Target"),
            completedPickup("pick-maple", "Maple Street Biscuit Company"),
        )
        val (r, _) = step(
            region(pickups), FlowRegion(flow = Flow.Idle),
            // A store-absent dropoff frame can yield junk (e.g. an item name); it must NOT be assigned.
            dropoffObs(1_000L, store = "Horizon Organic Half & Half", customer = "cust-x"),
        )
        assertNull("an unmatched candidate is rejected, not mis-assigned", r.activeTask?.storeName)
    }
}

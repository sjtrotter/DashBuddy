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

    private fun completedPickup(id: String, store: String, customer: String? = null) = Task(
        taskId = id, jobId = "job-1", phase = TaskPhase.PICKUP,
        storeName = store, customerNameHash = customer, arrivedAt = 300L, startedAt = 200L, completedAt = 400L,
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
    fun `multi-store stack — hyphen-code and place-name dropoff forms resolve to their pickup (#557)`() {
        // The 06-20 Peng's + Little Caesars stack: dropoff cards show running-key forms the broadened
        // regex now parses — `Little Caesars (0164-0045)` (store-code-hyphen) and a place-name paren.
        val pickups = listOf(
            completedPickup("p-lc", "Little Caesars"),
            completedPickup("p-peng", "Peng's Chinatown Chinese Restaurant"),
        )
        val (rLc, _) = step(
            region(pickups), FlowRegion(flow = Flow.Idle),
            dropoffObs(1_000L, store = "Little Caesars (0164-0045)", customer = "cust-lc"),
        )
        assertEquals("Little Caesars", rLc.activeTask?.storeName)

        val (rPeng, _) = step(
            region(pickups), FlowRegion(flow = Flow.Idle),
            dropoffObs(2_000L, store = "Peng's Chinatown Chinese Restaurant (San Antonio)", customer = "cust-peng"),
        )
        assertEquals("Peng's Chinatown Chinese Restaurant", rPeng.activeTask?.storeName)
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

    // =====================================================================
    // #526 D6 — customer-hash join (priority rule 1)
    // =====================================================================

    @Test
    fun `D6 join — a null-store drop resolves via its customer hash to the matching pickup (F2)`() {
        // The 07-05 Bill Miller + Mama Margies stack: the drop card parses NO store, so the token
        // fallback has nothing to match — but the customer hash joins it to its pickup.
        val pickups = listOf(
            completedPickup("p-bill", "Bill Miller BBQ", customer = "cx-bill"),
            completedPickup("p-mama", "Mama Margies", customer = "cx-mama"),
        )
        val (rBill, _) = step(
            region(pickups), FlowRegion(flow = Flow.Idle),
            dropoffObs(1_000L, store = null, customer = "cx-bill"),
        )
        assertEquals("the drop joins its pickup by customer hash", "Bill Miller BBQ", rBill.activeTask?.storeName)

        val (rMama, _) = step(
            region(pickups), FlowRegion(flow = Flow.Idle),
            dropoffObs(2_000L, store = null, customer = "cx-mama"),
        )
        assertEquals("Mama Margies", rMama.activeTask?.storeName)
    }

    @Test
    fun `D6 join — no pickup carries the drop's hash — falls through to the token rules`() {
        val pickups = listOf(
            completedPickup("p-target", "Target", customer = "cx-target"),
            completedPickup("p-maple", "Maple Street Biscuit Company", customer = "cx-maple"),
        )
        // The drop's customer matches no pickup hash → rule 1 no-ops; the token rule resolves it.
        val (r, _) = step(
            region(pickups), FlowRegion(flow = Flow.Idle),
            dropoffObs(1_000L, store = "Target (02426)", customer = "cx-unknown"),
        )
        assertEquals("falls through to the token match", "Target", r.activeTask?.storeName)
    }

    @Test
    fun `D6 join — ambiguous (two pickups share the drop's hash) falls through to the token rules`() {
        // Two pickups with the SAME customer hash → the join is ambiguous and must not pick one.
        val pickups = listOf(
            completedPickup("p-target", "Target", customer = "cx-dup"),
            completedPickup("p-maple", "Maple Street Biscuit Company", customer = "cx-dup"),
        )
        val (r, _) = step(
            region(pickups), FlowRegion(flow = Flow.Idle),
            // No store candidate → the token fallback also can't resolve → null, never a wrong store.
            dropoffObs(1_000L, store = null, customer = "cx-dup"),
        )
        assertNull("ambiguous join → no attribution (never a wrong store)", r.activeTask?.storeName)
    }

    @Test
    fun `D6 join — a #499 single-store job still resolves via rule 2 regardless of the hash`() {
        val (r, _) = step(
            region(pickups = listOf(completedPickup("p-heb", "H-E-B", customer = "cx-1"))),
            FlowRegion(flow = Flow.Idle),
            // A different customer (the second order of a same-store 2-order offer) → rule 1 no-ops,
            // rule 2's single-distinct-store arm still attributes H-E-B.
            dropoffObs(1_000L, store = null, customer = "cx-2"),
        )
        assertEquals("single distinct pickup store → every drop is that store", "H-E-B", r.activeTask?.storeName)
    }
}

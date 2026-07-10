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
 * #526/#733/#745 — a dropoff's store is resolved from the job's PICKUP lineage, not from the dropoff
 * card's own (per-merchant-variable, often unparseable) text. The resolution is the CUSTOMER-HASH
 * JOIN; the former structural single-drop arm was DELETED (#745 review — inert on the fielded shape,
 * wrong-store-capable on undercount shapes). Precedence:
 *  1. EXACT single-match join: the drop's customerNameHash joins pickups that all map to ONE store →
 *     resolve it (unconditional — a single agreed store is correct even under a hash collision).
 *     Normalization (#733) keeps the hashes cross-surface-stable.
 *  2. CONSTRAINED multi-store default: matched pickups span ≥2 stores → the earliest-confirmed store,
 *     but ONLY when this drop is the sole ACTIVATED dropoff carrying that hash. ≥2 activated drops
 *     share the hash → INCONCLUSIVE → fall through (fail-null beats fail-wrong).
 *  3. TOKEN MATCH: a 0-match drop (or an inconclusive collision) matches its parsed candidate by
 *     shared brand tokens; garbage → null, never a wrong store. A single-pickup-store job resolves
 *     here trivially (one distinct store) regardless of hash.
 *
 * A job's task lineage is read off `activeJob.tasks` / `recentTasks`, which [PlatformRegionStepper]
 * reconciles from the completed siblings + the active drop.
 */
class DropoffStoreLabelTest {

    private val stepper = PlatformRegionStepper()
    private val flowStepper = FlowRegionStepper()
    private val policy = TransitionPolicy()

    private fun completedPickup(
        id: String,
        store: String,
        customer: String? = null,
        completedAt: Long = 400L,
        arrivedAt: Long = 300L,
        startedAt: Long = 200L,
    ) = Task(
        taskId = id, jobId = "job-1", phase = TaskPhase.PICKUP,
        storeName = store, customerNameHash = customer,
        arrivedAt = arrivedAt, startedAt = startedAt, completedAt = completedAt,
    )

    /** A completed sibling dropoff of the same job — its presence in the lineage makes the job
     *  MULTI-drop (so structural single-drop resolution doesn't apply). Distinct customer so it
     *  never resumes the active drop under test. */
    private fun siblingDropoff(id: String = "d-sibling", customer: String = "cx-sibling") =
        Task(taskId = id, jobId = "job-1", phase = TaskPhase.DROPOFF, customerNameHash = customer,
            storeName = null, arrivedAt = 250L, startedAt = 220L, completedAt = 260L)

    private fun region(
        recentTasks: List<Task>,
        activeTask: Task? = null,
    ) = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Online,
        session = Session("sess-1", startedAt = 100L),
        activeJob = Job("job-1", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 150L),
        activeTask = activeTask,
        recentTasks = recentTasks,
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

    // =====================================================================
    // Part 1 — single-store jobs resolve trivially (one distinct pickup store)
    // =====================================================================

    @Test
    fun `single-store job — the dropoff takes the pickup store with no dropoff parse`() {
        val (r, _) = step(
            region(recentTasks = listOf(completedPickup("pick-1", "H-E-B"))),
            FlowRegion(flow = Flow.Idle),
            dropoffObs(1_000L, store = null, customer = "cust-1"),
        )
        assertEquals(TaskPhase.DROPOFF, r.activeTask?.phase)
        assertEquals("a single-pickup job's dropoff is that pickup's store", "H-E-B", r.activeTask?.storeName)
    }

    @Test
    fun `single-store job — a dropoff card's running-key form resolves to the canonical pickup store`() {
        val (r, _) = step(
            region(recentTasks = listOf(completedPickup("pick-1", "H-E-B"))),
            FlowRegion(flow = Flow.Idle),
            dropoffObs(1_000L, store = "H-E-B plus! #1055", customer = "cust-1"),
        )
        assertEquals("the dropoff is canonicalised to the pickup's store name", "H-E-B", r.activeTask?.storeName)
    }

    @Test
    fun `single-store job resolves regardless of the drop's hash — one distinct store (#499)`() {
        val (r, _) = step(
            region(recentTasks = listOf(completedPickup("p-heb", "H-E-B", customer = "cx-1"))),
            FlowRegion(flow = Flow.Idle),
            // A different customer (the second order of a same-store 2-order offer): the hash doesn't
            // match, but the token rule sees one distinct pickup store → H-E-B (never NULL).
            dropoffObs(1_000L, store = null, customer = "cx-2"),
        )
        assertEquals("single distinct pickup store → the drop is that store", "H-E-B", r.activeTask?.storeName)
    }

    // =====================================================================
    // Part 2/3 — multi-drop jobs (hash join → token fallback)
    // =====================================================================

    @Test
    fun `multi-store stack — each dropoff matches its own pickup, not the last active one`() {
        val pickups = listOf(
            completedPickup("pick-target", "Target"),
            completedPickup("pick-maple", "Maple Street Biscuit Company"),
        )
        val (rTarget, _) = step(
            region(pickups + siblingDropoff()), FlowRegion(flow = Flow.Idle),
            dropoffObs(1_000L, store = "Target (02426)", customer = "cust-target"),
        )
        assertEquals("the Target drop resolves to Target (not the last pickup, Maple)", "Target", rTarget.activeTask?.storeName)

        val (rMaple, _) = step(
            region(pickups + siblingDropoff()), FlowRegion(flow = Flow.Idle),
            dropoffObs(2_000L, store = "Maple Street Biscuit - Alamo Ranch", customer = "cust-maple"),
        )
        assertEquals(
            "the Maple drop resolves to Maple via shared leading tokens (cores differ: '- Alamo Ranch' vs 'Company')",
            "Maple Street Biscuit Company", rMaple.activeTask?.storeName,
        )
    }

    @Test
    fun `multi-store stack — hyphen-code and place-name dropoff forms resolve to their pickup (#557)`() {
        val pickups = listOf(
            completedPickup("p-lc", "Little Caesars"),
            completedPickup("p-peng", "Peng's Chinatown Chinese Restaurant"),
        )
        val (rLc, _) = step(
            region(pickups + siblingDropoff()), FlowRegion(flow = Flow.Idle),
            dropoffObs(1_000L, store = "Little Caesars (0164-0045)", customer = "cust-lc"),
        )
        assertEquals("Little Caesars", rLc.activeTask?.storeName)

        val (rPeng, _) = step(
            region(pickups + siblingDropoff()), FlowRegion(flow = Flow.Idle),
            dropoffObs(2_000L, store = "Peng's Chinatown Chinese Restaurant (San Antonio)", customer = "cust-peng"),
        )
        assertEquals("Peng's Chinatown Chinese Restaurant", rPeng.activeTask?.storeName)
    }

    @Test
    fun `multi-drop job — a garbage candidate matches no pickup and resolves to null (never a wrong store)`() {
        val pickups = listOf(
            completedPickup("pick-target", "Target"),
            completedPickup("pick-maple", "Maple Street Biscuit Company"),
        )
        val (r, _) = step(
            region(pickups + siblingDropoff()), FlowRegion(flow = Flow.Idle),
            // A store-absent dropoff frame can yield junk (e.g. an item name); it must NOT be assigned.
            dropoffObs(1_000L, store = "Horizon Organic Half & Half", customer = "cust-x"),
        )
        assertNull("an unmatched candidate is rejected, not mis-assigned", r.activeTask?.storeName)
    }

    @Test
    fun `D6 join — a null-store drop resolves via its customer hash to the matching pickup (F2)`() {
        val pickups = listOf(
            completedPickup("p-bill", "Bill Miller BBQ", customer = "cx-bill"),
            completedPickup("p-mama", "Mama Margies", customer = "cx-mama"),
        )
        val (rBill, _) = step(
            region(pickups + siblingDropoff()), FlowRegion(flow = Flow.Idle),
            dropoffObs(1_000L, store = null, customer = "cx-bill"),
        )
        assertEquals("the drop joins its pickup by customer hash", "Bill Miller BBQ", rBill.activeTask?.storeName)

        val (rMama, _) = step(
            region(pickups + siblingDropoff()), FlowRegion(flow = Flow.Idle),
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
        val (r, _) = step(
            region(pickups + siblingDropoff()), FlowRegion(flow = Flow.Idle),
            dropoffObs(1_000L, store = "Target (02426)", customer = "cx-unknown"),
        )
        assertEquals("falls through to the token match", "Target", r.activeTask?.storeName)
    }

    @Test
    fun `D6 join — sole activated drop, matches spanning two stores, resolves earliest-confirmed (#733 part 2)`() {
        // THE fielded 07-08 shape (isolated): one customer picked up at two stores that BOTH carry that
        // customer's normalized hash → the join matches both → multi-store-fed. Because this is the
        // SOLE activated dropoff carrying the hash (the sibling drop is a different customer), the
        // CONSTRAINED default fires → the earliest-confirmed lineage store (was: NULL). Target < Maple.
        val pickups = listOf(
            completedPickup("p-target", "Target", customer = "cx-dup", completedAt = 350L),
            completedPickup("p-maple", "Maple Street Biscuit Company", customer = "cx-dup", completedAt = 450L),
        )
        val (r, _) = step(
            region(pickups + siblingDropoff()), FlowRegion(flow = Flow.Idle),
            dropoffObs(1_000L, store = null, customer = "cx-dup"),
        )
        assertEquals(
            "sole activated drop, multi-store-fed → the earliest-confirmed lineage store (deterministic)",
            "Target", r.activeTask?.storeName,
        )
    }

    @Test
    fun `D6 join — collision — two activated drops share a hash, matches span two stores, no attribution (#745)`() {
        // Two DISTINCT physical drops collapse to the same customerNameHash (colliding customers whose
        // normalized keys coincide, or a strip-miss). The hash matches pickups across TWO stores, but
        // with ≥2 activated drops sharing it we can't say which store feeds which → the multi-store
        // default is INCONCLUSIVE and must NOT guess. With a null candidate it falls to null, never a
        // wrong store. A completed sibling drop (already in the lineage) carries the same hash, and the
        // active drop keeps its own taskId (same-phase update, no resume) so both count as activated.
        val pickups = listOf(
            completedPickup("p-target", "Target", customer = "cx-dup", completedAt = 350L),
            completedPickup("p-maple", "Maple Street Biscuit Company", customer = "cx-dup", completedAt = 450L),
        )
        val priorDrop = Task(
            taskId = "d-prior", jobId = "job-1", phase = TaskPhase.DROPOFF,
            customerNameHash = "cx-dup", storeName = null,
            arrivedAt = 480L, startedAt = 470L, completedAt = 500L,
        )
        val activeDrop = Task(
            taskId = "d-active", jobId = "job-1", phase = TaskPhase.DROPOFF,
            subPhase = TaskSubFlow.NAVIGATION, customerNameHash = "cx-dup", storeName = null,
            startedAt = 600L,
        )
        val (r, _) = step(
            region(recentTasks = pickups + priorDrop, activeTask = activeDrop),
            FlowRegion(flow = Flow.TaskDropoffNavigation),
            dropoffObs(1_000L, store = null, customer = "cx-dup"), // keeps d-active (same-phase update)
        )
        assertEquals("the colliding drop keeps its own taskId (no resume)", "d-active", r.activeTask?.taskId)
        assertNull("≥2 activated drops share the hash → inconclusive → no store attribution", r.activeTask?.storeName)
    }

    @Test
    fun `D6 join — all matches map to one store resolves it (multi-match same store)`() {
        // A same-customer, same-store double order inside a multi-store job → both matched pickups
        // map to one store; resolve it (today this used to fall through).
        val pickups = listOf(
            completedPickup("p-chip-1", "Chipotle", customer = "cx-a"),
            completedPickup("p-chip-2", "Chipotle", customer = "cx-a"),
            completedPickup("p-wendys", "Wendy's", customer = "cx-b"),
        )
        val (r, _) = step(
            region(pickups + siblingDropoff()), FlowRegion(flow = Flow.Idle),
            dropoffObs(1_000L, store = null, customer = "cx-a"),
        )
        assertEquals("both matched pickups map to Chipotle → resolve it", "Chipotle", r.activeTask?.storeName)
    }

    // =====================================================================
    // Part 5 — WARN edge-gating, once per taskId (P7 — no per-frame storm)
    // =====================================================================

    @Test
    fun `D6 join-miss WARN fires once per taskId, silent across a two-form hash flap (#745)`() {
        // A two-form customer surface alternates the drop's hash A↔B per frame. The WARN must be keyed
        // ONCE PER taskId (not per (taskId, hash)), so a flap on the SAME drop can't re-storm the log
        // (the field ×23). The gate lives in region state (lastJoinMissWarnTaskId), so it threads
        // across steps and is replay-deterministic.
        val pickups = listOf(
            completedPickup("pick-1", "Bill Miller BBQ", customer = "hashA"),
            completedPickup("pick-2", "Mama Margies", customer = "hashB"),
        )
        val logged = mutableListOf<String>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (message.contains("D6 join miss")) logged += message
            }
        }
        timber.log.Timber.plant(tree)
        try {
            // Frame 1 — the drop activates with a join-missing hash → one WARN, gate stamped.
            val r1 = stepper.step(
                region(pickups + siblingDropoff()),
                FlowRegion(flow = Flow.TaskDropoffNavigation), FlowRegion(flow = Flow.TaskDropoffNavigation),
                dropoffObs(1_000L, store = null, customer = "hashY"),
                policy,
            )
            val warnsAfterFrame1 = logged.size
            assertEquals("a fresh join-miss surfaces one WARN", 1, warnsAfterFrame1)
            assertEquals("the gate is stamped with the drop's taskId", r1.activeTask?.taskId, r1.lastJoinMissWarnTaskId)

            // Frame 2 — the SAME drop (same taskId, same-phase update), hash flaps Y→Z, still a
            // join-miss → SILENT (already warned for this taskId).
            val r2 = stepper.step(
                r1,
                FlowRegion(flow = Flow.TaskDropoffNavigation), FlowRegion(flow = Flow.TaskDropoffNavigation),
                dropoffObs(1_100L, store = null, customer = "hashZ"),
                policy,
            )
            assertEquals("the flap stays on the same drop taskId", r1.activeTask?.taskId, r2.activeTask?.taskId)
            assertEquals("the two-form hash flap does NOT re-WARN (once per taskId)", 1, logged.size)
        } finally {
            timber.log.Timber.uproot(tree)
        }
    }
}

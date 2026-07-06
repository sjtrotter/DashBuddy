package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #526 — pre-created PICKUP placeholders (the symmetric counterpart of the dropoff placeholders),
 * their resolution priority (exact hint → StoreNameMatch tier → next-open), and the D4a swap guard.
 *
 * An accepted offer pre-creates one pickup placeholder per *distinct* store it covers (same-store
 * orders are one combined pickup, #499); a pickup screen resolves onto its placeholder keeping the
 * offer-owned taskId. Because store hints are unreliable, a slot can be mis-bound — the swap guard
 * re-attributes accumulated data to the right order slot without re-minting.
 */
class PickupPlaceholderTest {

    private val stepper = PlatformRegionStepper()
    private val flowStepper = FlowRegionStepper()
    private val policy = TransitionPolicy()

    // ---- helpers ----

    private fun region(
        activeJob: Job? = null,
        activeTask: Task? = null,
        recentTasks: List<Task> = emptyList(),
    ) = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Online,
        session = Session("sess-1", startedAt = 100L),
        activeJob = activeJob,
        activeTask = activeTask,
        recentTasks = recentTasks,
    )

    private fun order(i: Int, store: String) = ParsedOrder(
        orderIndex = i, orderType = OrderType.PICKUP, storeName = store,
        itemCount = 1, isItemCountEstimated = false, badges = emptySet(),
    )

    private fun offerFlow(hash: String, stores: List<String>) = FlowRegion(
        flow = Flow.OfferPresented,
        pendingOffer = PendingOffer(
            offerHash = hash,
            offerFields = ParsedFields.OfferFields(
                parsedOffer = ParsedOffer(
                    offerHash = hash,
                    payAmount = 10.0,
                    distanceMiles = 5.0,
                    orders = stores.mapIndexed { i, s -> order(i, s) },
                ),
            ),
            presentedAt = 500L,
            returnFlow = Flow.Idle,
        ),
    )

    private fun pickupObs(timestamp: Long, store: String, subFlow: TaskSubFlow = TaskSubFlow.NAVIGATION) =
        Observation.Screen(
            timestamp = timestamp, captureId = null, ruleId = "doordash.screen.pickup",
            metadata = ReplayMetadata.EMPTY,
            flow = if (subFlow == TaskSubFlow.ARRIVED) Flow.TaskPickupArrived else Flow.TaskPickupNavigation,
            modeHint = Mode.Online,
            parsed = ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = subFlow, storeName = store),
        )

    /** Step through the real FlowRegionStepper (accept/resolution paths). */
    private fun step(prev: PlatformRegion, prevFlow: FlowRegion, obs: Observation): PlatformRegion {
        val nextFlow = if (obs is Observation.FlowObservation) flowStepper.step(prevFlow, obs) else prevFlow
        return stepper.step(prev, prevFlow, nextFlow, obs, policy)
    }

    /** Direct step with an explicit nextFlow (for the same-phase swap-guard path). */
    private fun stepRaw(prev: PlatformRegion, prevFlow: FlowRegion, nextFlow: FlowRegion, obs: Observation) =
        stepper.step(prev, prevFlow, nextFlow, obs, policy)

    private fun pickups(r: PlatformRegion) = r.activeJob!!.tasks.filter { it.phase == TaskPhase.PICKUP }
    private fun pickupHints(r: PlatformRegion) = pickups(r).mapNotNull { it.expectedStoreHint }.toSet()

    // =====================================================================
    // CREATION
    // =====================================================================

    @Test
    fun `single-store offer owns one pickup placeholder carrying the store hint`() {
        val r = step(region(), offerFlow("o1", listOf("H-E-B")), pickupObs(1_000L, "H-E-B"))
        assertEquals(1, pickups(r).size)
        assertEquals(setOf("H-E-B"), pickupHints(r))
    }

    @Test
    fun `two-distinct-store offer owns two pickup placeholders`() {
        val r = step(region(), offerFlow("o1", listOf("Sprouts", "CVS")), pickupObs(1_000L, "Sprouts"))
        assertEquals("two distinct stores → two pickup placeholders", 2, pickups(r).size)
        assertEquals(setOf("Sprouts", "CVS"), pickupHints(r))
        assertEquals("Sprouts", r.activeTask?.storeName)
        val open = pickups(r).filter { it.storeName == null }
        assertEquals(1, open.size)
        assertEquals("CVS", open.single().expectedStoreHint)
    }

    @Test
    fun `same-store two-order offer owns ONE combined pickup but TWO dropoffs (#499 fold)`() {
        val r = step(region(), offerFlow("o1", listOf("H-E-B", "H-E-B")), pickupObs(1_000L, "H-E-B"))
        val job = r.activeJob!!
        assertEquals("two same-store orders share one combined pickup", 1, job.tasks.count { it.phase == TaskPhase.PICKUP })
        assertEquals("but each order is still its own dropoff", 2, job.tasks.count { it.phase == TaskPhase.DROPOFF })
    }

    @Test
    fun `an offer with no parsed orders creates no pickup placeholder (single-delivery fallback)`() {
        val r = step(region(), offerFlow("o1", emptyList()), pickupObs(1_000L, "H-E-B"))
        assertNull("the active pickup is a fresh mint, not a placeholder", r.activeTask?.expectedStoreHint)
        assertEquals("no offer-spawned pickup placeholder exists", 0, pickups(r).count { it.expectedStoreHint != null })
    }

    @Test
    fun `add-on at a new store appends a pickup placeholder, a same-store add-on does not`() {
        val r1 = step(region(), offerFlow("o1", listOf("H-E-B")), pickupObs(1_000L, "H-E-B"))
        val r2 = step(r1, offerFlow("o2", listOf("Wendy's")), pickupObs(2_000L, "Wendy's"))
        assertEquals("a new-store add-on brings its own pickup placeholder", setOf("H-E-B", "Wendy's"), pickupHints(r2))

        val r3 = step(r2, offerFlow("o3", listOf("H-E-B")), pickupObs(3_000L, "H-E-B"))
        assertEquals("a same-store add-on folds in — no duplicate placeholder", setOf("H-E-B", "Wendy's"), pickupHints(r3))
        assertEquals(2, pickups(r3).count())
    }

    @Test
    fun `FIX3c - a same-store add-on onto a BARE-fallback job makes no duplicate pickup placeholder`() {
        // A no-orders offer mints a bare job — the active pickup carries a storeName but hint=null.
        val r1 = step(region(), offerFlow("o1", emptyList()), pickupObs(1_000L, "H-E-B"))
        assertNull("bare fallback: active pickup has no hint", r1.activeTask?.expectedStoreHint)
        assertEquals("H-E-B", r1.activeTask?.storeName)
        // A same-store add-on folds in — deduped against the RESOLVED storeName, not just the hint.
        val r2 = step(r1, offerFlow("o2", listOf("H-E-B")), pickupObs(2_000L, "H-E-B"))
        assertEquals(
            "no duplicate H-E-B pickup placeholder from the same-store add-on",
            0, r2.activeJob!!.tasks.count { it.phase == TaskPhase.PICKUP && it.expectedStoreHint != null },
        )
    }

    // =====================================================================
    // RESOLUTION
    // =====================================================================

    @Test
    fun `a stacked pickup resolves onto its hint-matching placeholder with the pre-created id`() {
        val r1 = step(region(), offerFlow("o1", listOf("Sprouts", "CVS")), pickupObs(1_000L, "Sprouts"))
        val cvsSlotId = pickups(r1).single { it.storeName == null }.taskId

        val r2 = step(r1, FlowRegion(flow = Flow.TaskPickupNavigation), pickupObs(2_000L, "Sprouts", TaskSubFlow.ARRIVED))
        val r3 = step(r2, FlowRegion(flow = Flow.TaskPickupArrived), pickupObs(3_000L, "CVS"))

        assertEquals("the CVS pickup keeps the offer-owned slot id", cvsSlotId, r3.activeTask?.taskId)
        assertEquals("CVS", r3.activeTask?.storeName)
        assertEquals("CVS", r3.activeTask?.expectedStoreHint)
        assertTrue("the Sprouts pickup completed into recentTasks", r3.recentTasks.any { it.storeName == "Sprouts" && it.completedAt != null })
    }

    @Test
    fun `D3a - a noisy store resolves onto the best-matching slot, not blindly the first open one`() {
        // "CVS Pharmacy #123" matches neither hint exactly, but shares the leading "cvs" token with
        // the CVS slot and zero with Sprouts — so it must resolve onto CVS, not next-open (Sprouts).
        val r = step(region(), offerFlow("o1", listOf("Sprouts", "CVS")), pickupObs(1_000L, "CVS Pharmacy #123"))
        assertEquals("bestMatch tier binds to CVS, not the first open slot", "CVS", r.activeTask?.expectedStoreHint)
        assertEquals("CVS Pharmacy #123", r.activeTask?.storeName)
    }

    @Test
    fun `a no-match store with 2+ open slots freshly mints (FIX3b - no blind next-open bind)`() {
        // FIX3b: with ≥2 open placeholders and a store matching neither hint, a blind first-open
        // bind would guess the wrong order — fall through to a fresh mint (master behavior). A later
        // frame carrying a real store can hint-match the right placeholder.
        val r = step(region(), offerFlow("o1", listOf("Sprouts", "CVS")), pickupObs(1_000L, "Mystery Mart"))
        assertEquals("Mystery Mart", r.activeTask?.storeName)
        assertNull("no blind placeholder bind with 2 open slots — fresh mint", r.activeTask?.expectedStoreHint)
    }

    @Test
    fun `a no-match store with exactly ONE open slot still binds next-open (FIX3b)`() {
        val r = step(region(), offerFlow("o1", listOf("Sprouts")), pickupObs(1_000L, "Mystery Mart"))
        assertEquals("Mystery Mart", r.activeTask?.storeName)
        assertEquals("single open slot → next-open binds", "Sprouts", r.activeTask?.expectedStoreHint)
    }

    // =====================================================================
    // D4a — SWAP GUARD (mis-bind repair)
    // =====================================================================

    private fun twoOpenPickupJob() = Job(
        jobId = "job-1", offerStoreHint = listOf("Sprouts", "CVS"), parentOfferHash = "o1", startedAt = 100L,
        tasks = listOf(
            Task(taskId = "pk-A", jobId = "job-1", phase = TaskPhase.PICKUP, expectedStoreHint = "Sprouts", storeName = null, startedAt = 100L),
            Task(taskId = "pk-B", jobId = "job-1", phase = TaskPhase.PICKUP, expectedStoreHint = "CVS", storeName = null, startedAt = 100L),
        ),
    )

    private val pickupNavFlow = FlowRegion(flow = Flow.TaskPickupNavigation)

    @Test
    fun `swap fires when the active pickup's store belongs to a different open slot`() {
        // pk-A (hint Sprouts) is mis-bound: it accumulated the CVS store. A frame parsing "CVS
        // Pharmacy" (matches CVS slot, 0 tokens with Sprouts) triggers the swap.
        val job = twoOpenPickupJob()
        val active = job.tasks.single { it.taskId == "pk-A" }.copy(storeName = "CVS Pharmacy")
        val r = stepRaw(
            region(activeJob = job, activeTask = active),
            pickupNavFlow, pickupNavFlow,
            pickupObs(2_000L, "CVS Pharmacy"),
        )
        assertEquals("accumulation moved onto the CVS slot (its taskId), stable", "pk-B", r.activeTask?.taskId)
        assertEquals("CVS Pharmacy", r.activeTask?.storeName)
        assertEquals("CVS", r.activeTask?.expectedStoreHint)
        val sproutsSlot = r.activeJob!!.tasks.single { it.taskId == "pk-A" }
        assertNull("the Sprouts slot is open again for its real order", sproutsSlot.storeName)
    }

    @Test
    fun `swap does NOT fire when the active pickup's store matches its own hint`() {
        val job = twoOpenPickupJob()
        val active = job.tasks.single { it.taskId == "pk-A" }.copy(storeName = "Sprouts Market")
        val r = stepRaw(
            region(activeJob = job, activeTask = active),
            pickupNavFlow, pickupNavFlow,
            pickupObs(2_000L, "Sprouts Market"),
        )
        assertEquals("stays on its own (Sprouts) slot", "pk-A", r.activeTask?.taskId)
    }

    @Test
    fun `swap does NOT fire on an ambiguous match (two slots share the leading token)`() {
        // Both other slots start with "cvs" → not a unique match → conservative no-swap.
        val job = Job(
            jobId = "job-1", offerStoreHint = listOf("Sprouts", "CVS", "CVS Express"), parentOfferHash = "o1", startedAt = 100L,
            tasks = listOf(
                Task(taskId = "pk-A", jobId = "job-1", phase = TaskPhase.PICKUP, expectedStoreHint = "Sprouts", storeName = "CVS Pharmacy", startedAt = 100L),
                Task(taskId = "pk-B", jobId = "job-1", phase = TaskPhase.PICKUP, expectedStoreHint = "CVS", storeName = null, startedAt = 100L),
                Task(taskId = "pk-C", jobId = "job-1", phase = TaskPhase.PICKUP, expectedStoreHint = "CVS Express", storeName = null, startedAt = 100L),
            ),
        )
        val active = job.tasks.single { it.taskId == "pk-A" }
        val r = stepRaw(
            region(activeJob = job, activeTask = active),
            pickupNavFlow, pickupNavFlow,
            pickupObs(2_000L, "CVS Pharmacy"),
        )
        assertEquals("ambiguous → no swap, stays put", "pk-A", r.activeTask?.taskId)
    }

    @Test
    fun `swap does NOT fire when the active pickup has neither hint nor accumulated store (FIX3a)`() {
        // FIX3a: a null hint made the 0-token divergence vacuously true → wrong swaps. With NO hint
        // AND NO accumulated store the active slot owns nothing to diverge from → never swap.
        val job = Job(
            jobId = "job-1", offerStoreHint = listOf("CVS"), parentOfferHash = "o1", startedAt = 100L,
            tasks = listOf(
                Task(taskId = "pk-A", jobId = "job-1", phase = TaskPhase.PICKUP, expectedStoreHint = null, storeName = null, startedAt = 100L),
                Task(taskId = "pk-B", jobId = "job-1", phase = TaskPhase.PICKUP, expectedStoreHint = "CVS", storeName = null, startedAt = 100L),
            ),
        )
        val active = job.tasks.single { it.taskId == "pk-A" }
        val r = stepRaw(
            region(activeJob = job, activeTask = active),
            pickupNavFlow, pickupNavFlow,
            pickupObs(2_000L, "CVS Pharmacy"),
        )
        assertEquals("no hint + no store on the active slot → no swap", "pk-A", r.activeTask?.taskId)
    }

    @Test
    fun `same brand at two locations - both hints share tokens, so no 0-token divergence and no swap`() {
        // "Taco Bell (Main St)" and "Taco Bell (5th Ave)" share the leading "taco bell" tokens with
        // BOTH the active task's own hint AND the sibling — so the 0-token divergence guard blocks a
        // swap even though the sibling also matches. The identity stays on the originally-bound slot.
        val job = Job(
            jobId = "job-1", offerStoreHint = listOf("Taco Bell (Main St)", "Taco Bell (5th Ave)"),
            parentOfferHash = "o1", startedAt = 100L,
            tasks = listOf(
                Task(taskId = "pk-A", jobId = "job-1", phase = TaskPhase.PICKUP, expectedStoreHint = "Taco Bell (Main St)", storeName = "Taco Bell", startedAt = 100L),
                Task(taskId = "pk-B", jobId = "job-1", phase = TaskPhase.PICKUP, expectedStoreHint = "Taco Bell (5th Ave)", storeName = null, startedAt = 100L),
            ),
        )
        val active = job.tasks.single { it.taskId == "pk-A" }
        val r = stepRaw(
            region(activeJob = job, activeTask = active),
            pickupNavFlow, pickupNavFlow,
            pickupObs(2_000L, "Taco Bell"),
        )
        assertEquals("same-brand two-location: no clean divergence → no swap", "pk-A", r.activeTask?.taskId)
    }
}

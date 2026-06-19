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
import cloud.trotter.dashbuddy.domain.state.swapTaskAccumulation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #526 — pre-created PICKUP placeholders (the symmetric counterpart of the dropoff placeholders)
 * and the **swap guard**.
 *
 * An accepted offer pre-creates one pickup placeholder per *distinct* store it covers (same-store
 * orders are one combined pickup, #499); a pickup screen resolves onto its placeholder by an exact
 * store-hint match, falling back to the next open slot. Because store hints are unreliable, a slot
 * can be mis-bound — the swap guard re-attributes accumulated data to the right order slot without
 * re-minting.
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

    private fun step(prev: PlatformRegion, prevFlow: FlowRegion, obs: Observation): PlatformRegion {
        val nextFlow = if (obs is Observation.FlowObservation) flowStepper.step(prevFlow, obs) else prevFlow
        return stepper.step(prev, prevFlow, nextFlow, obs, policy)
    }

    private fun pickups(r: PlatformRegion) = r.activeJob!!.tasks.filter { it.phase == TaskPhase.PICKUP }
    private fun pickupHints(r: PlatformRegion) =
        pickups(r).mapNotNull { it.expectedStoreHint }.toSet()

    // =====================================================================
    // SWAP PRIMITIVE (the capability the guard is built on)
    // =====================================================================

    @Test
    fun `swapTaskAccumulation exchanges screen data but preserves slot identity`() {
        val a = Task(
            taskId = "t-A", jobId = "job-1", phase = TaskPhase.PICKUP,
            expectedStoreHint = "Sprouts", storeName = "CVS Pharmacy",
            arrivedAt = 900L, itemsShopped = 3, startedAt = 100L,
        )
        val b = Task(
            taskId = "t-B", jobId = "job-1", phase = TaskPhase.PICKUP,
            expectedStoreHint = "CVS", storeName = "Sprouts Market",
            arrivedAt = null, itemsShopped = 10, startedAt = 200L,
        )

        val (na, nb) = swapTaskAccumulation(a, b)

        // Slot identity is preserved on each side.
        assertEquals("t-A", na.taskId); assertEquals("Sprouts", na.expectedStoreHint); assertEquals(100L, na.startedAt)
        assertEquals("t-B", nb.taskId); assertEquals("CVS", nb.expectedStoreHint); assertEquals(200L, nb.startedAt)
        // Accumulated screen data is exchanged.
        assertEquals("Sprouts Market", na.storeName); assertEquals(10, na.itemsShopped); assertNull(na.arrivedAt)
        assertEquals("CVS Pharmacy", nb.storeName); assertEquals(3, nb.itemsShopped); assertEquals(900L, nb.arrivedAt)
    }

    @Test
    fun `withSwappedAccumulation re-attributes mis-bound data to the correct order slot`() {
        // The Sprouts slot accidentally holds the CVS pickup's data, and vice versa.
        val sproutsSlot = Task(taskId = "t-A", jobId = "job-1", phase = TaskPhase.PICKUP, expectedStoreHint = "Sprouts", storeName = "CVS", startedAt = 100L)
        val cvsSlot = Task(taskId = "t-B", jobId = "job-1", phase = TaskPhase.PICKUP, expectedStoreHint = "CVS", storeName = "Sprouts", startedAt = 100L)
        val job = Job(jobId = "job-1", offerStoreHint = listOf("Sprouts", "CVS"), parentOfferHash = null, startedAt = 100L, tasks = listOf(sproutsSlot, cvsSlot))

        val fixed = job.withSwappedAccumulation("t-A", "t-B")

        assertEquals("Sprouts slot now holds Sprouts data", "Sprouts", fixed.tasks.single { it.taskId == "t-A" }.storeName)
        assertEquals("CVS slot now holds CVS data", "CVS", fixed.tasks.single { it.taskId == "t-B" }.storeName)
    }

    @Test
    fun `withSwappedAccumulation is a no-op for identical or missing ids`() {
        val t = Task(taskId = "t-A", jobId = "job-1", phase = TaskPhase.PICKUP, startedAt = 100L)
        val job = Job(jobId = "job-1", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 100L, tasks = listOf(t))
        assertSame(job, job.withSwappedAccumulation("t-A", "t-A"))
        assertSame(job, job.withSwappedAccumulation("t-A", "missing"))
    }

    // =====================================================================
    // PLACEHOLDER CREATION
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
        // The accept's Sprouts screen resolved onto the Sprouts slot; the CVS slot stays open.
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
        // No store hints → no pickup placeholder; the pickup is minted fresh (pre-#526 behaviour).
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

    // =====================================================================
    // RESOLUTION
    // =====================================================================

    @Test
    fun `a stacked pickup resolves onto its hint-matching placeholder with the pre-created id (#526)`() {
        // Offer covers two stores; visit Sprouts first, then CVS.
        val r1 = step(region(), offerFlow("o1", listOf("Sprouts", "CVS")), pickupObs(1_000L, "Sprouts"))
        val cvsSlotId = pickups(r1).single { it.storeName == null }.taskId
        assertNotNull(cvsSlotId)

        // Arrive at Sprouts (so the next pickup nav is a stacked transition).
        val r2 = step(r1, FlowRegion(flow = Flow.TaskPickupNavigation), pickupObs(2_000L, "Sprouts", TaskSubFlow.ARRIVED))
        // Navigate to CVS — resolves onto the pre-created CVS slot, not a fresh mint.
        val r3 = step(r2, FlowRegion(flow = Flow.TaskPickupArrived), pickupObs(3_000L, "CVS"))

        assertEquals("the CVS pickup keeps the offer-owned slot id", cvsSlotId, r3.activeTask?.taskId)
        assertEquals("CVS", r3.activeTask?.storeName)
        assertEquals("CVS", r3.activeTask?.expectedStoreHint)
        assertTrue("the Sprouts pickup completed into recentTasks", r3.recentTasks.any { it.storeName == "Sprouts" && it.completedAt != null })
    }

    @Test
    fun `a pickup whose store matches no hint falls onto the next open slot (the mis-bind the swap guard repairs)`() {
        // The parsed store ("Mystery Mart") matches neither hint, so next-open binds it to the
        // FIRST slot — which may be the wrong order. This is exactly the case the swap guard exists
        // to repair once a reliable mis-bind signal is available.
        val r = step(region(), offerFlow("o1", listOf("Sprouts", "CVS")), pickupObs(1_000L, "Mystery Mart"))
        assertEquals("Mystery Mart", r.activeTask?.storeName)
        assertEquals("bound by next-open to the first slot", "Sprouts", r.activeTask?.expectedStoreHint)
    }
}

package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.evaluation.OfferQuality
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.CrossPlatformRegion
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
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
 * #996 / #997 — the two receipt-less pay-attribution money defects, pinned END-TO-END at the mint
 * sites on the two shapes that produced them in the field. The accept edges run through the REAL
 * `JobAcceptFlow` (so the placeholders' [Task.mintedByOfferHash] lineage is stamped by production
 * code, never hand-set), and the completion runs through the REAL [EffectMap] close-out.
 *
 * The captured sessions' own `app_events` rows ENCODE the bugs (they are the characterization
 * oracle), so every assertion here is a hand-authored correct-behaviour invariant — never
 * `replay == db`.
 *
 * - **#997, 08-06 job …-299 (seqs 1442 / 1445 / 1455).** Three separately-accepted offers absorbed
 *   into ONE job — Target \$10.45, Target \$16.55, H-E-B \$20.20 — folded the pooled 47.20/3
 *   (\$15.73 / \$15.73 / \$15.74): a per-drop error up to \$5.28 (50 % relative) that session-level
 *   reconciliation hides completely, because Σ is 47.20 either way.
 * - **#996, 08-07 job …-313 (seqs 1496 → 1506).** ONE offer quoting \$13.10 over two orders for one
 *   customer; both pickups confirmed, ONE physical drop, which folded 13.10/2 = \$6.55 — and the
 *   dash's reconciliation read reported \$52.85 − attributed \$46.30 = exactly the missing \$6.55.
 *
 * The invariant both share, asserted on both: **the job's Σ never exceeds the accepted quotes.** For
 * #997 Σ is IDENTICAL to today's (per-offer only redistributes within the job, so period gross and
 * `unattributedPay` cannot move); for #996 Σ RISES to the full quote — that delta is the fix, and it
 * moves dollars out of `unattributedPay` into attributed.
 */
class PerOfferPayAttributionTest {

    private val stepper = PlatformRegionStepper()
    private val effectMap = EffectMap()

    // ---- accept-side scaffolding (drives the REAL mint site) ----

    private fun eval(pay: Double) = OfferEvaluation(
        action = OfferAction.ACCEPT, score = 70.0, qualityLevel = OfferQuality.GOOD,
        payAmount = pay, fuelCostEstimate = 0.5, netPayAmount = pay - 1.0,
        distanceMiles = 4.0, dollarsPerMile = pay / 4.0, dollarsPerHour = 20.0,
        estimatedTimeMinutes = 20.0, itemCount = 1.0, merchantName = "Test Store",
    )

    private fun order(index: Int, store: String) = ParsedOrder(
        orderIndex = index, orderType = OrderType.PICKUP, storeName = store,
        itemCount = 1, isItemCountEstimated = false, badges = emptySet(),
    )

    /** An accepted-pending-consumption survivor on the region's own `pendingOffers` (#438 B3). */
    private fun acceptedOffer(hash: String, pay: Double, stores: List<String>, at: Long) = PendingOffer(
        offerHash = hash,
        offerFields = ParsedFields.OfferFields(
            parsedOffer = ParsedOffer(
                offerHash = hash, payAmount = pay, distanceMiles = 4.0, timeToCompleteMinutes = 20L,
                orders = stores.mapIndexed { i, s -> order(i, s) },
            ),
        ),
        presentedAt = at - 10L,
        evaluation = eval(pay),
        returnFlow = Flow.Idle,
        lastClickIntent = OfferIntent.ACCEPT,
        acceptClickAt = at,
        acceptedAt = at,
    )

    private fun taskObs(timestamp: Long, store: String) = Observation.Screen(
        timestamp = timestamp, captureId = null, ruleId = "test.pickup_nav",
        metadata = ReplayMetadata.EMPTY, flow = Flow.TaskPickupNavigation, modeHint = Mode.Online,
        parsed = ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION, storeName = store),
    )

    /** Consume one accepted offer into the job graph through the production accept path. */
    private fun accept(
        region: PlatformRegion,
        hash: String,
        pay: Double,
        stores: List<String>,
        at: Long,
    ): PlatformRegion {
        val pending = acceptedOffer(hash, pay, stores, at)
        val obs = taskObs(at, stores.first())
        return stepper.consumeAcceptIntoJob(
            region,
            obs,
            stepper.acceptInputsFromPending(pending, acceptedAt = at),
        )
    }

    private fun baseRegion(at: Long = 1_000L) = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Online,
        session = Session("sess-1", startedAt = at, runningEarnings = 0.0),
    )

    // ---- completion-side scaffolding (drives the REAL close-out mint) ----

    private fun appState(flow: Flow, region: PlatformRegion) = AppState(
        regions = Regions(
            flow = FlowRegion(flow = flow, activePlatform = Platform.DoorDash),
            platforms = mapOf(Platform.DoorDash to region),
            crossPlatform = CrossPlatformRegion(),
        ),
    )

    private fun closeOutRows(regionPrev: PlatformRegion): List<DeliveryPayload> {
        val regionNext = regionPrev.copy(activeJob = null)
        val obs = Observation.Screen(
            timestamp = 9_000L, captureId = null, ruleId = "test.idle",
            metadata = ReplayMetadata.EMPTY, flow = Flow.Idle, modeHint = Mode.Online,
            parsed = ParsedFields.None,
        )
        return effectMap.diff(appState(Flow.Idle, regionPrev), appState(Flow.Idle, regionNext), obs)
            .filterIsInstance<AppEffect.LogEvent>()
            .filter { it.event.type == AppEventType.DELIVERY_COMPLETED }
            .map { it.event.payload as DeliveryPayload }
    }

    private fun cents(v: Double): Long = Math.round(v * 100.0)

    private fun Job.dropoffs() = tasks.filter { it.phase == TaskPhase.DROPOFF }
    private fun Job.pickups() = tasks.filter { it.phase == TaskPhase.PICKUP }

    /** Activate + deliver a pre-created dropoff placeholder (what the dropoff screens do live). */
    private fun Task.delivered(customer: String, store: String, at: Long) =
        copy(customerNameHash = customer, storeName = store, arrivedAt = at - 60_000L, completedAt = at)

    /** Resolve + confirm a pre-created pickup placeholder. */
    private fun Task.confirmed(customer: String, store: String, at: Long) =
        copy(customerNameHash = customer, storeName = store, arrivedAt = at - 60_000L, completedAt = at)

    // ---- #997: the 08-06 three-accept merge ----

    @Test
    fun `#997 mint site — every absorbed accept stamps its own drops with its own offer hash`() {
        var region = baseRegion()
        region = accept(region, "o-1442", 10.45, listOf("Target"), at = 1_100L)
        region = accept(region, "o-1445", 16.55, listOf("Target"), at = 1_200L)
        region = accept(region, "o-1455", 20.20, listOf("H-E-B"), at = 1_300L)

        val job = region.activeJob
        assertNotNull("all three accepts landed on one open job", job)
        assertEquals("one job absorbed all three accepts", 3, job!!.acceptedOffers.size)
        assertEquals(
            "one dropoff placeholder per absorbed order, each carrying its OWN accept's hash",
            listOf("o-1442", "o-1445", "o-1455"),
            job.dropoffs().map { it.mintedByOfferHash },
        )
        // The same-store add-on folds into the existing pickup (#499) — pickup lineage is deliberately
        // NOT stamped (the split's denominator is dropoffs; no consumer, YAGNI).
        assertTrue("pickups carry no lineage", job.pickups().all { it.mintedByOfferHash == null })
    }

    @Test
    fun `#997 08-06 (seqs 1442, 1445, 1455) — each drop folds its OWN offer's pay, session Sigma unchanged`() {
        var region = baseRegion()
        region = accept(region, "o-1442", 10.45, listOf("Target"), at = 1_100L)
        region = accept(region, "o-1445", 16.55, listOf("Target"), at = 1_200L)
        region = accept(region, "o-1455", 20.20, listOf("H-E-B"), at = 1_300L)
        val minted = region.activeJob!!

        // All three drops delivered to distinct customers; the H-E-B add-on's pickup confirmed too.
        val drops = minted.dropoffs()
        val d1 = drops[0].delivered("cust-a", "Target", at = 5_000L)
        val d2 = drops[1].delivered("cust-b", "Target", at = 6_000L)
        val d3 = drops[2].delivered("cust-c", "H-E-B", at = 7_000L)
        val pickups = minted.pickups().mapIndexed { i, p ->
            p.confirmed("cust-${'a' + i}", p.expectedStoreHint ?: "Target", at = 4_000L + i * 100L)
        }
        val closing = minted.copy(tasks = listOf(d1, d2, d3) + pickups)
        val regionPrev = region.copy(
            activeJob = closing,
            recentTasks = pickups + listOf(d1, d2, d3),
            lastPostTaskFields = null, // WHOLLY receipt-less — the #999 out-of-zone mode
        )

        val rows = closeOutRows(regionPrev)
        assertEquals("all three drops complete", 3, rows.size)
        assertEquals(10.45, rows.single { it.taskId == d1.taskId }.offerPayShare!!, 1e-9)
        assertEquals(16.55, rows.single { it.taskId == d2.taskId }.offerPayShare!!, 1e-9)
        assertEquals(20.20, rows.single { it.taskId == d3.taskId }.offerPayShare!!, 1e-9)

        // Σ invariant: identical to the pooled behaviour it replaces — per-offer only redistributes
        // WITHIN the job, so the session's gross / unattributedPay cannot move.
        assertEquals(
            "Σ = the accepted quotes, to the cent (unchanged from the pooled split)",
            cents(47.20), rows.sumOf { cents(it.offerPayShare!!) },
        )
        // …and the log now carries the per-drop→offer join it lacked.
        assertEquals("o-1442", rows.single { it.taskId == d1.taskId }.mintedByOfferHash)
        assertEquals("o-1455", rows.single { it.taskId == d3.taskId }.mintedByOfferHash)
        assertEquals(
            "jobOfferHashes still carries the whole chain on every row",
            3, rows.first().jobOfferHashes.size,
        )
    }

    // ---- #996: the 08-07 same-customer two-order consolidation ----

    @Test
    fun `#996 08-07 (seqs 1496 to 1506) — the sole physical drop of a proven-complete job folds the FULL quote`() {
        var region = baseRegion()
        region = accept(region, "o-1496", 13.10, listOf("Mama Margies", "Raising Cane's"), at = 1_100L)
        val minted = region.activeJob!!
        assertEquals("two orders → two dropoff placeholders", 2, minted.dropoffs().size)
        assertEquals("two distinct stores → two pickup placeholders", 2, minted.pickups().size)

        // One customer: the FIRST placeholder activates and every later same-hash frame resumes it
        // (#498), so the second dropoff stays customer-TBD for the job's whole life.
        val delivered = minted.dropoffs()[0].delivered("cust-one", "Mama Margies", at = 7_633L)
        val tbd = minted.dropoffs()[1]
        val p1 = minted.pickups()[0].confirmed("cust-one", "Mama Margies", at = 6_034L) // seq 1501
        val p2 = minted.pickups()[1].confirmed("cust-one", "Raising Cane's", at = 6_933L) // seq 1502
        val closing = minted.copy(tasks = listOf(delivered, tbd, p1, p2))
        val regionPrev = region.copy(
            activeJob = closing,
            recentTasks = listOf(p1, p2, delivered),
            lastPostTaskFields = null, // receipt-less settlement
        )

        val rows = closeOutRows(regionPrev)
        assertEquals("only the one physical drop mints", 1, rows.size)
        assertEquals(
            "the #749 coverage proof retires the consolidation artifact → the full quote lands",
            13.10, rows.single().offerPayShare!!, 1e-9,
        )
        assertEquals(
            "Σ-attributed converges on the accepted quote (the missing \$6.55 is recovered)",
            cents(13.10), rows.sumOf { cents(it.offerPayShare!!) },
        )
    }

    @Test
    fun `#996 conservatism — an UNPROVEN close still dilutes across the placeholder (bit-exact today)`() {
        // Same job shape, but one pickup was never confirmed: order 2's items are still in the store
        // (#759 F1), so the #749 coverage arm refuses to prove completion. The estimate must keep
        // today's conservative 13.10/2 and let the remainder ride the unattributedPay bucket.
        var region = baseRegion()
        region = accept(region, "o-1496", 13.10, listOf("Mama Margies", "Raising Cane's"), at = 1_100L)
        val minted = region.activeJob!!

        val delivered = minted.dropoffs()[0].delivered("cust-one", "Mama Margies", at = 7_633L)
        val tbd = minted.dropoffs()[1]
        val p1 = minted.pickups()[0].confirmed("cust-one", "Mama Margies", at = 6_034L)
        val p2Unconfirmed = minted.pickups()[1]
            .confirmed("cust-one", "Raising Cane's", at = 6_933L).copy(completedAt = null)
        val closing = minted.copy(tasks = listOf(delivered, tbd, p1, p2Unconfirmed))
        val regionPrev = region.copy(
            activeJob = closing,
            recentTasks = listOf(p1, p2Unconfirmed, delivered),
            lastPostTaskFields = null,
        )

        val rows = closeOutRows(regionPrev)
        assertEquals(1, rows.size)
        assertEquals(
            "completion unproven → the pre-#996 dilution stands, to the cent",
            6.55, rows.single().offerPayShare!!, 1e-9,
        )
    }

    @Test
    fun `a receipted job is still suppressed entirely — neither fix can invent an estimate`() {
        // The eligibility gates in front of both fixes are untouched: a PAY-BEARING receipt is truth,
        // so no offer-pay estimate is stamped no matter how the split would have partitioned.
        var region = baseRegion()
        region = accept(region, "o-1", 10.45, listOf("Target"), at = 1_100L)
        region = accept(region, "o-2", 16.55, listOf("Target"), at = 1_200L)
        val minted = region.activeJob!!
        val d1 = minted.dropoffs()[0].delivered("cust-a", "Target", at = 5_000L)
        val d2 = minted.dropoffs()[1].delivered("cust-b", "Target", at = 6_000L)
        val closing = minted.copy(tasks = listOf(d1, d2) + minted.pickups())
        val regionPrev = region.copy(
            activeJob = closing,
            recentTasks = listOf(d1, d2),
            lastPostTaskFields = ParsedFields.PostTaskFields(
                totalPay = 25.0, parsedPay = null, sessionEarnings = 25.0,
            ),
            lastAnnouncedPostTaskTaskId = d2.taskId,
        )

        val rows = closeOutRows(regionPrev)
        assertEquals(2, rows.size)
        rows.forEach { assertNull("a real receipt suppresses the estimate", it.offerPayShare) }
    }
}

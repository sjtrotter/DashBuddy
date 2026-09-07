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
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.OfferPayAttribution
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.ReceiptCoverage
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
 * sites on the shapes that produced them in the field. The accept edges run through the REAL
 * `JobAcceptFlow` (so both the placeholders' [Task.mintedByOfferHash] hint AND each accept's own
 * `storeHints` are stamped by production code, never hand-set), and the completion runs through the
 * REAL [EffectMap].
 *
 * The captured sessions' own `app_events` rows ENCODE the bugs (they are the characterization
 * oracle), so every assertion here is a hand-authored correct-behaviour invariant — never
 * `replay == db`.
 *
 * - **#997, 08-06 job …-299 (seqs 1442 / 1445 / 1455).** Three separately-accepted offers absorbed
 *   into ONE job — Target \$10.45, Target \$16.55, H-E-B \$20.20 — all folded the pooled 47.20/3
 *   (\$15.73 / \$15.73 / \$15.74). Which Target quote is which Target drop is platform-side
 *   unknowable, so the honest answer sub-pools those two and pays H-E-B its exact \$20.20.
 * - **#996, 08-07 job …-313 (seqs 1496 → 1506).** ONE offer quoting \$13.10 over two orders for one
 *   customer; both pickups confirmed, ONE physical drop, which folded 13.10/2 = \$6.55 — and the
 *   dash's reconciliation read reported \$52.85 − attributed \$46.30 = exactly the missing \$6.55.
 *
 * The invariant both share: **the job's Σ never exceeds the accepted quotes**, at either mint site.
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
    ): PlatformRegion = stepper.consumeAcceptIntoJob(
        region,
        taskObs(at, stores.first()),
        stepper.acceptInputsFromPending(acceptedOffer(hash, pay, stores, at), acceptedAt = at),
    )

    private fun baseRegion(at: Long = 1_000L) = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Online,
        session = Session("sess-1", startedAt = at, runningEarnings = 0.0),
    )

    // ---- completion-side scaffolding (drives the REAL mints) ----

    private fun appState(flow: Flow, region: PlatformRegion) = AppState(
        regions = Regions(
            flow = FlowRegion(flow = flow, activePlatform = Platform.DoorDash),
            platforms = mapOf(Platform.DoorDash to region),
            crossPlatform = CrossPlatformRegion(),
        ),
    )

    private fun screenObs(flow: Flow, at: Long) = Observation.Screen(
        timestamp = at, captureId = null, ruleId = "test.screen",
        metadata = ReplayMetadata.EMPTY, flow = flow, modeHint = Mode.Online, parsed = ParsedFields.None,
    )

    private fun rows(prev: PlatformRegion, next: PlatformRegion, prevFlow: Flow, nextFlow: Flow): List<DeliveryPayload> =
        effectMap.diff(appState(prevFlow, prev), appState(nextFlow, next), screenObs(nextFlow, at = 9_000L))
            .filterIsInstance<AppEffect.LogEvent>()
            .filter { it.event.type == AppEventType.DELIVERY_COMPLETED }
            .map { it.event.payload as DeliveryPayload }

    /** The terminal close: the job goes null while R0 sits on a non-PostTask flow. */
    private fun closeOutRows(regionPrev: PlatformRegion): List<DeliveryPayload> =
        rows(regionPrev, regionPrev.copy(activeJob = null), Flow.Idle, Flow.Idle)

    private fun cents(v: Double): Long = Math.round(v * 100.0)

    private fun Job.dropoffs() = tasks.filter { it.phase == TaskPhase.DROPOFF }
    private fun Job.pickups() = tasks.filter { it.phase == TaskPhase.PICKUP }

    /**
     * ONE helper for both phases (the pickup/dropoff pair used to be two identical bodies): activate a
     * pre-created placeholder onto a customer + store and finish it, exactly as the screens do live.
     */
    private fun Task.resolved(customer: String?, store: String?, at: Long, finished: Boolean = true) = copy(
        customerNameHash = customer,
        storeName = store,
        arrivedAt = at - 60_000L,
        completedAt = at.takeIf { finished },
    )

    // ---- #997: the 08-06 three-accept merge ----

    @Test
    fun `#997 mint site — every absorbed accept stamps its own slots and keeps its own store hints`() {
        var region = baseRegion()
        region = accept(region, "o-1442", 10.45, listOf("Target"), at = 1_100L)
        region = accept(region, "o-1445", 16.55, listOf("Target"), at = 1_200L)
        region = accept(region, "o-1455", 20.20, listOf("H-E-B"), at = 1_300L)

        val job = region.activeJob
        assertNotNull("all three accepts landed on one open job", job)
        assertEquals("one job absorbed all three accepts", 3, job!!.acceptedOffers.size)
        assertEquals(
            "one dropoff placeholder per absorbed order, each carrying its OWN accept's hash (a HINT)",
            listOf("o-1442", "o-1445", "o-1455"),
            job.dropoffs().map { it.mintedByOfferHash },
        )
        assertEquals(
            "each accept keeps its OWN quoted stores — Job.offerStoreHint flattens them together",
            listOf(listOf("Target"), listOf("Target"), listOf("H-E-B")),
            job.acceptedOffers.map { it.storeHints },
        )
        assertTrue("pickups carry no lineage (no consumer — YAGNI)", job.pickups().all { it.mintedByOfferHash == null })
    }

    @Test
    fun `#997 08-06 (seqs 1442, 1445, 1455) — same-store quotes sub-pool, the unique store takes its own`() {
        var region = baseRegion()
        region = accept(region, "o-1442", 10.45, listOf("Target"), at = 1_100L)
        region = accept(region, "o-1445", 16.55, listOf("Target"), at = 1_200L)
        region = accept(region, "o-1455", 20.20, listOf("H-E-B"), at = 1_300L)
        val minted = region.activeJob!!

        val drops = minted.dropoffs()
        val d1 = drops[0].resolved("cust-a", "Target", at = 5_000L)
        val d2 = drops[1].resolved("cust-b", "Target", at = 6_000L)
        val d3 = drops[2].resolved("cust-c", "H-E-B", at = 7_000L)
        val pickups = minted.pickups().mapIndexed { i, p ->
            p.resolved("cust-$i", p.expectedStoreHint, at = 4_000L + i * 100L)
        }
        val regionPrev = region.copy(
            activeJob = minted.copy(tasks = listOf(d1, d2, d3) + pickups),
            recentTasks = pickups + listOf(d1, d2, d3),
            lastPostTaskFields = null, // WHOLLY receipt-less — the #999 out-of-zone mode
        )

        val out = closeOutRows(regionPrev)
        assertEquals("all three drops complete", 3, out.size)
        assertEquals(13.50, out.single { it.taskId == d1.taskId }.offerPayShare!!, 1e-9)
        assertEquals(13.50, out.single { it.taskId == d2.taskId }.offerPayShare!!, 1e-9)
        assertEquals(
            "the H-E-B drop takes its exact quote (was \$15.74 pooled)",
            20.20, out.single { it.taskId == d3.taskId }.offerPayShare!!, 1e-9,
        )
        assertEquals(
            "Σ = the accepted quotes, to the cent (unchanged from the pooled split)",
            cents(47.20), out.sumOf { cents(it.offerPayShare!!) },
        )
        // The RESOLVED attribution rides the log — not the mint stamp.
        assertEquals(OfferPayAttribution.SUB_POOLED_STORE, out.single { it.taskId == d1.taskId }.offerPayAttribution)
        assertNull("a sub-pooled share names no single offer", out.single { it.taskId == d1.taskId }.offerPayAttributedHash)
        assertEquals(OfferPayAttribution.PER_OFFER_STORE, out.single { it.taskId == d3.taskId }.offerPayAttribution)
        assertEquals("o-1455", out.single { it.taskId == d3.taskId }.offerPayAttributedHash)
        assertEquals("jobOfferHashes still carries the whole chain", 3, out.first().jobOfferHashes.size)
    }

    @Test
    fun `#997 — a CROSS-STORE swap auto-corrects, the store beats the mint stamp`() {
        // Placeholders activate blind first-open, so the platform routing the add-on's drop first
        // crosses the slot stamps. Folding the stamp's exact quote would be confidently WRONG with Σ
        // unchanged — invisible to reconciliation. The reconciled store is what survives the reshuffle.
        var region = baseRegion()
        region = accept(region, "o-A", 10.00, listOf("Target"), at = 1_100L)
        region = accept(region, "o-B", 20.00, listOf("H-E-B"), at = 1_200L)
        val minted = region.activeJob!!
        val drops = minted.dropoffs()
        assertEquals("o-A", drops[0].mintedByOfferHash)

        // Slot 0 (stamped o-A) actually took the H-E-B order; slot 1 took the Target one.
        val d1 = drops[0].resolved("cust-a", "H-E-B", at = 5_000L)
        val d2 = drops[1].resolved("cust-b", "Target", at = 6_000L)
        val regionPrev = region.copy(
            activeJob = minted.copy(tasks = listOf(d1, d2) + minted.pickups()),
            recentTasks = listOf(d1, d2),
            lastPostTaskFields = null,
        )

        val out = closeOutRows(regionPrev)
        assertEquals(20.00, out.single { it.taskId == d1.taskId }.offerPayShare!!, 1e-9)
        assertEquals(10.00, out.single { it.taskId == d2.taskId }.offerPayShare!!, 1e-9)
        assertEquals("o-B", out.single { it.taskId == d1.taskId }.offerPayAttributedHash)
    }

    @Test
    fun `#997 — a NULL-store drop falls back to the offer that minted its slot`() {
        // The fielded D6 join-miss class: the drop's store never resolved, so the mint stamp is the
        // best evidence there is.
        var region = baseRegion()
        region = accept(region, "o-A", 10.00, listOf("Target"), at = 1_100L)
        region = accept(region, "o-B", 20.00, listOf("H-E-B"), at = 1_200L)
        val minted = region.activeJob!!
        val drops = minted.dropoffs()
        val d1 = drops[0].resolved("cust-a", "Target", at = 5_000L)
        val d2 = drops[1].resolved("cust-b", store = null, at = 6_000L)
        val regionPrev = region.copy(
            activeJob = minted.copy(tasks = listOf(d1, d2) + minted.pickups()),
            recentTasks = listOf(d1, d2),
            lastPostTaskFields = null,
        )

        val out = closeOutRows(regionPrev)
        assertEquals(10.00, out.single { it.taskId == d1.taskId }.offerPayShare!!, 1e-9)
        assertEquals(20.00, out.single { it.taskId == d2.taskId }.offerPayShare!!, 1e-9)
        assertEquals(OfferPayAttribution.STAMP_FALLBACK, out.single { it.taskId == d2.taskId }.offerPayAttribution)
    }

    @Test
    fun `#997 — every degrade is stated once per close, with its reason and counts`() {
        val logged = mutableListOf<String>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { logged += message }
        }
        timber.log.Timber.plant(tree)
        try {
            var region = baseRegion()
            region = accept(region, "o-1", 10.45, listOf("Target"), at = 1_100L)
            region = accept(region, "o-2", 16.55, listOf("Target"), at = 1_200L)
            val minted = region.activeJob!!
            val drops = minted.dropoffs()
            val d1 = drops[0].resolved("cust-a", "Target", at = 5_000L)
            val d2 = drops[1].resolved("cust-b", "Target", at = 6_000L)
            val regionPrev = region.copy(
                activeJob = minted.copy(tasks = listOf(d1, d2) + minted.pickups()),
                recentTasks = listOf(d1, d2),
                lastPostTaskFields = null,
            )
            closeOutRows(regionPrev)
        } finally {
            timber.log.Timber.uproot(tree)
        }
        assertEquals(
            "one degrade line naming the arm and its counts",
            1,
            logged.count { it.contains("attribution degraded to SUB_POOLED_STORE") && it.contains("2 offer(s) over 2 drop(s)") },
        )
    }

    // ---- #996: the 08-07 same-customer consolidation ----

    @Test
    fun `#996 08-07 (seqs 1496 to 1506) — the sole physical drop of a proven-complete job folds the FULL quote`() {
        var region = baseRegion()
        region = accept(region, "o-1496", 13.10, listOf("Mama Margies", "Raising Cane's"), at = 1_100L)
        val minted = region.activeJob!!
        assertEquals("two orders → two dropoff placeholders", 2, minted.dropoffs().size)
        assertEquals("two distinct stores → two pickup placeholders", 2, minted.pickups().size)

        // One customer: the FIRST placeholder activates and every later same-hash frame resumes it
        // (#498), so the second dropoff stays customer-TBD for the job's whole life.
        val delivered = minted.dropoffs()[0].resolved("cust-one", "Mama Margies", at = 7_633L)
        val tbd = minted.dropoffs()[1]
        val p1 = minted.pickups()[0].resolved("cust-one", "Mama Margies", at = 6_034L) // seq 1501
        val p2 = minted.pickups()[1].resolved("cust-one", "Raising Cane's", at = 6_933L) // seq 1502
        val regionPrev = region.copy(
            activeJob = minted.copy(tasks = listOf(delivered, tbd, p1, p2)),
            recentTasks = listOf(p1, p2, delivered),
            lastPostTaskFields = null, // receipt-less settlement
        )

        val out = closeOutRows(regionPrev)
        assertEquals("only the one physical drop mints", 1, out.size)
        assertEquals(
            "the #749 coverage proof retires the consolidation artifact → the full quote lands",
            13.10, out.single().offerPayShare!!, 1e-9,
        )
        assertEquals(
            "Σ-attributed converges on the accepted quote (the missing \$6.55 is recovered)",
            cents(13.10), out.sumOf { cents(it.offerPayShare!!) },
        )
    }

    @Test
    fun `#996 conservatism — an UNPROVEN close still dilutes across the placeholder (bit-exact today)`() {
        // Same job shape, but one pickup was never confirmed: order 2's items are still in the store
        // (#759 F1), so the #749 coverage arm refuses to prove completion.
        var region = baseRegion()
        region = accept(region, "o-1496", 13.10, listOf("Mama Margies", "Raising Cane's"), at = 1_100L)
        val minted = region.activeJob!!
        val delivered = minted.dropoffs()[0].resolved("cust-one", "Mama Margies", at = 7_633L)
        val tbd = minted.dropoffs()[1]
        val p1 = minted.pickups()[0].resolved("cust-one", "Mama Margies", at = 6_034L)
        val p2 = minted.pickups()[1].resolved("cust-one", "Raising Cane's", at = 6_933L, finished = false)
        val regionPrev = region.copy(
            activeJob = minted.copy(tasks = listOf(delivered, tbd, p1, p2)),
            recentTasks = listOf(p1, p2, delivered),
            lastPostTaskFields = null,
        )

        val out = closeOutRows(regionPrev)
        assertEquals(1, out.size)
        assertEquals(
            "completion unproven → the pre-#996 dilution stands, to the cent",
            6.55, out.single().offerPayShare!!, 1e-9,
        )
    }

    // ---- amendment B: one proof site, and a bail can never prove completeness ----

    @Test
    fun `amendment B — an endSession BAIL can never prove completeness - the 3-order construction`() {
        // `endSession` force-stamps `completedAt` on whatever task was active at the bail. For a drop
        // that had already ARRIVED, that satisfies the #749 coverage arm's finished-and-arrived test
        // and FORGES the completeness proof — which would retire the outstanding TBD placeholder and
        // pay the delivered drops 30/2 = \$15.00 each instead of the honest 30/3 = \$10.00. The proof
        // therefore reads MINT-QUALIFIED evidence only (the amdt-#5 discriminator, one owner).
        var region = baseRegion()
        region = accept(region, "o-1", 30.00, listOf("S-One", "S-Two", "S-Three"), at = 1_100L)
        val minted = region.activeJob!!
        val drops = minted.dropoffs()

        val delivered = drops[0].resolved("cust-a", "S-One", at = 5_000L) // finished on a PRIOR step
        val bailed = drops[1].resolved("cust-b", "S-Two", at = 9_000L) // ARRIVED, force-stamped at the bail
        val tbd = drops[2]
        val pickups = minted.pickups().mapIndexed { i, p ->
            p.resolved(listOf("cust-a", "cust-b", "cust-a")[i], p.expectedStoreHint, at = 4_000L + i)
        }
        val regionPrev = region.copy(
            activeJob = minted.copy(tasks = listOf(delivered, bailed, tbd) + pickups),
            // `bailed` is NOT in prev's recentTasks — it was the ACTIVE task when the bail landed.
            activeTask = drops[1].resolved("cust-b", "S-Two", at = 9_000L, finished = false),
            recentTasks = pickups + listOf(delivered),
            pendingDestructive = PendingDestructive(kind = DestructiveKind.SESSION_END, since = 9_000L, deadline = 9_500L),
            lastPostTaskFields = null,
        )
        // endSession's shape: session + job cleared, the active task force-stamped into recentTasks.
        val regionNext = regionPrev.copy(
            session = null, activeJob = null, activeTask = null,
            recentTasks = pickups + listOf(delivered, bailed),
            pendingDestructive = null,
        )

        val out = rows(regionPrev, regionNext, Flow.Idle, Flow.Idle)
        assertEquals("the force-stamped, undelivered drop does not mint (T3 guard)", 1, out.size)
        val row = out.single()
        assertEquals(delivered.taskId, row.taskId)
        assertEquals(
            "a bail proves nothing → the TBD placeholder still divides the quote (30/3)",
            10.00, row.offerPayShare!!, 1e-9,
        )
    }

    @Test
    fun `amendment B — the PostTask-exit mint keeps the pre-#1012 conservative split (no Σ overrun)`() {
        // The review's Σ-overrun construction: an inline mint on the 08-07 shape stamping the FULL
        // quote, followed by the filtered placeholder later activating and minting too. The inline
        // mint never shrinks and never attributes per-offer, so the first stamp is 13.10/2 and the
        // two mints together can only ever sum to the quote.
        var region = baseRegion()
        region = accept(region, "o-1496", 13.10, listOf("Mama Margies", "Raising Cane's"), at = 1_100L)
        val minted = region.activeJob!!
        val d1 = minted.dropoffs()[0].resolved("cust-one", "Mama Margies", at = 7_633L, finished = false)
        val tbd = minted.dropoffs()[1]
        val p1 = minted.pickups()[0].resolved("cust-one", "Mama Margies", at = 6_034L)
        val p2 = minted.pickups()[1].resolved("cust-one", "Raising Cane's", at = 6_933L)
        val jobOpen = minted.copy(tasks = listOf(d1, tbd, p1, p2))
        val regionPrev = region.copy(
            activeJob = jobOpen, activeTask = d1, recentTasks = listOf(p1, p2), lastPostTaskFields = null,
        )
        val regionNext = regionPrev.copy(activeTask = d1.copy(completedAt = 7_633L))

        val inline = rows(regionPrev, regionNext, Flow.PostTask, Flow.Idle)
        assertEquals(1, inline.size)
        assertEquals(
            "the inline mint pools over the QUOTED orders — never the shrunk denominator",
            6.55, inline.single().offerPayShare!!, 1e-9,
        )
        assertEquals(OfferPayAttribution.INLINE_POOLED, inline.single().offerPayAttribution)

        // The placeholder later activates and the job closes: the second drop takes the other half.
        val d2 = tbd.resolved("cust-two", "Raising Cane's", at = 8_000L)
        val closed = region.copy(
            activeJob = minted.copy(tasks = listOf(d1.copy(completedAt = 7_633L), d2, p1, p2)),
            recentTasks = listOf(p1, p2, d1.copy(completedAt = 7_633L), d2),
            lastPostTaskFields = null,
        )
        val closeRows = closeOutRows(closed)
        assertEquals(
            "Σ across BOTH mint sites is still exactly the quote — no overrun",
            cents(13.10), closeRows.sumOf { cents(it.offerPayShare!!) },
        )
    }

    @Test
    fun `a receipted job is still suppressed entirely — neither fix can invent an estimate`() {
        var region = baseRegion()
        region = accept(region, "o-1", 10.45, listOf("Target"), at = 1_100L)
        region = accept(region, "o-2", 16.55, listOf("Target"), at = 1_200L)
        val minted = region.activeJob!!
        val d1 = minted.dropoffs()[0].resolved("cust-a", "Target", at = 5_000L)
        val d2 = minted.dropoffs()[1].resolved("cust-b", "Target", at = 6_000L)
        val regionPrev = region.copy(
            activeJob = minted.copy(tasks = listOf(d1, d2) + minted.pickups()),
            recentTasks = listOf(d1, d2),
            lastPostTaskFields = ParsedFields.PostTaskFields(
                totalPay = 25.0, parsedPay = null, sessionEarnings = 25.0,
            ),
            lastAnnouncedPostTaskTaskId = d2.taskId,
            // #1073 round 15: a receipt suppresses the estimate only when it DESCRIBES this job's
            // drops — the coverage a real receipt frame would have computed for these two.
            lastPostTaskCoverage = ReceiptCoverage(setOf(d1.taskId, d2.taskId)),
        )

        val out = closeOutRows(regionPrev)
        assertEquals(2, out.size)
        out.forEach {
            assertNull("a real receipt suppresses the estimate", it.offerPayShare)
            assertNull("…and stamps no attribution provenance either", it.offerPayAttribution)
        }
    }
}

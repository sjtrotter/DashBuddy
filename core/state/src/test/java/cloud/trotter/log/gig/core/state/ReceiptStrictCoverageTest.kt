package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.core.state.model.DecisionSink
import cloud.trotter.log.gig.core.state.model.ReceiptEvidenceDecision
import cloud.trotter.log.gig.core.state.model.ReceiptRepriceDecision
import cloud.trotter.log.gig.core.state.model.StepDecisions
import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryPayload
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryReceiptRepricePayload
import cloud.trotter.log.gig.domain.model.pay.ParsedPay
import cloud.trotter.log.gig.domain.model.pay.ParsedPayItem
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.TimeoutType
import cloud.trotter.log.gig.domain.state.AcceptedOfferEconomics
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.FlowRegion
import cloud.trotter.log.gig.domain.state.JobReceiptAnchors
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.ReceiptCoverage
import cloud.trotter.log.gig.domain.state.ReceiptProofGap
import cloud.trotter.log.gig.domain.state.ReceiptScope
import cloud.trotter.log.gig.domain.state.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1598 — the closed PR #1325's counterexample set (its `ReceiptCoverageTest`), re-expressed on master's APIs, each
 * with a proven and an unproven expectation. "Unproven" asserts that no receipt money lands anywhere: no share, no
 * bare total on a completion, no re-price.
 */
class ReceiptStrictCoverageTest {

    private val evidence = mutableListOf<ReceiptEvidenceDecision>()

    private fun fields(amount: Double, base: Double = amount, store: String = RS.STORE) =
        ParsedFields.PostTaskFields(totalPay = amount, parsedPay = itemized(base, amount - base, store), isExpanded = true)

    private fun itemized(base: Double, tip: Double, store: String = RS.STORE) = ParsedPay(
        appPayComponents = listOf(ParsedPayItem("Base Pay", base)),
        customerTips = if (tip > 0.0) listOf(ParsedPayItem(store, tip)) else emptyList(),
    )

    private fun drop(id: String, store: String? = RS.STORE, completedAt: Long? = null, jobId: String = "J1") =
        RS.drop(id, jobId = jobId, store = store, completedAt = completedAt)

    private fun region(
        active: Task,
        recent: List<Task> = emptyList(),
        offers: List<AcceptedOfferEconomics> = listOf(RS.offer()),
        jobTasks: List<Task> = recent + active,
    ) = RS.live(active, recent, offers, jobTasks)
        .copy(jobReceiptAnchors = JobReceiptAnchors(jobId = active.jobId, firstEnteredAt = 3_500L))

    private fun idle(at: Long) = Observation.Screen(
        timestamp = at, captureId = "cap-$at", ruleId = "idle", metadata = ReplayMetadata.EMPTY,
        flow = Flow.Idle, modeHint = Mode.Online, parsed = ParsedFields.None,
    )

    /** The PostTask arm's receipt read, exactly as the stepper wires it: the proof, the evidence, the cache. */
    private fun read(region: PlatformRegion, subject: String, f: ParsedFields.PostTaskFields, at: Long = 4_500L): PlatformRegion {
        val coverage = receiptCoverageAt(region, subject)
        val sink = DecisionSink()
        val observed = observeReceiptEvidence(sink, region, f, subject, coverage, RS.postTask(f, at))
        sink.build().receiptEvidence?.let { evidence += it }
        return cacheReceipt(observed, f, subject, coverage.copy(evidenceId = observed.openReceiptEvidence?.evidenceId))
    }

    /** The completions a PostTask exit mints from [read] (the exit edge only). */
    private fun exitRows(read: PlatformRegion): List<DeliveryPayload> = completions(
        RS.effectMap.diff(
            RS.appState(read.copy(lastActedFlow = Flow.PostTask), Flow.PostTask),
            RS.appState(read.copy(lastActedFlow = Flow.Idle), Flow.Idle),
            idle(4_600L), StepDecisions.NONE,
        ),
    )

    private class Closed(
        val region: PlatformRegion,
        val decision: ReceiptRepriceDecision?,
        val rows: List<DeliveryPayload>,
        val reprices: List<DeliveryReceiptRepricePayload>,
    )

    private fun retired(source: PlatformRegion): PlatformRegion {
        val active = source.activeTask ?: return source
        return source.copy(
            activeTask = null,
            recentTasks = source.recentTasks + active.copy(completedAt = active.completedAt ?: 4_800L),
        )
    }

    /** The job's retire commits and closes it: the close-time decision plus the close-out sweep's rows. */
    private fun close(source: PlatformRegion): Closed {
        val before = retired(source)
        val sink = DecisionSink()
        val closed = RS.stepper.completeActiveJob(sink, before, 5_000L)
        val decisions = sink.build()
        val effects = RS.effectMap.diff(
            RS.appState(before.copy(lastActedFlow = Flow.Idle), Flow.Idle),
            RS.appState(closed.copy(lastActedFlow = Flow.Idle), Flow.Idle),
            idle(5_000L), StepDecisions(mapOf(Platform.DoorDash to decisions)),
        )
        return Closed(
            closed, decisions.receiptReprice, completions(effects),
            effects.filterIsInstance<AppEffect.LogEvent>()
                .filter { it.event.type == AppEventType.DELIVERY_RECEIPT_REPRICE }
                .map { it.event.payload as DeliveryReceiptRepricePayload },
        )
    }

    private fun completions(effects: List<AppEffect>) = effects.filterIsInstance<AppEffect.LogEvent>()
        .filter { it.event.type == AppEventType.DELIVERY_COMPLETED }
        .map { it.event.payload as DeliveryPayload }

    /** Receipt money a completion row carries (a share, else a bare total). The estimate is not receipt money. */
    private fun DeliveryPayload.receiptMoney(): Double = dropRealizedPay ?: totalPay ?: 0.0

    private fun assertNoReceiptMoney(rows: List<DeliveryPayload>) {
        rows.forEach { row ->
            assertNull("no share on ${row.taskId}", row.dropRealizedPay)
            assertNull("no bare total on ${row.taskId}", row.totalPay)
            assertNull("no itemization on ${row.taskId}", row.parsedPay)
            assertEquals(ReceiptScope.UNPROVEN, row.receiptScope)
        }
    }

    private fun assertUnproven(region: PlatformRegion, gap: ReceiptProofGap) {
        assertEquals(ReceiptCoverage(scope = ReceiptScope.UNPROVEN, gap = gap, evidenceId = region.lastPostTaskCoverage?.evidenceId),
            region.lastPostTaskCoverage)
    }

    // ---- :98, the 2026-09-20 shape ---------------------------------------------------------------------------------

    private val twoOffers = listOf(RS.offer("oA", hints = listOf("Store A")), RS.offer("oB", hints = listOf("Store B")))

    @Test
    fun `09-20 unproven - A and B in one two-offer job, neither receipt places money or re-prices the other`() {
        val a = drop("A", store = "Store A")
        val b = drop("B", store = "Store B")
        val aRead = read(region(a, jobTasks = listOf(a, b), offers = twoOffers), "A", fields(28.51))
        assertNoReceiptMoney(exitRows(aRead))
        assertUnproven(aRead, ReceiptProofGap.OFFER_COUNT)

        val bRead = read(region(b, recent = listOf(a.copy(completedAt = 4_000L)), offers = twoOffers), "B", fields(20.50), at = 6_000L)
        val closed = close(bRead)
        assertNull("no re-price of either drop", closed.decision)
        assertTrue(closed.reprices.isEmpty())
        assertNoReceiptMoney(closed.rows)
        assertUnproven(bRead, ReceiptProofGap.OFFER_COUNT)
        assertEquals("both reads are evidence", 2, evidence.size)
        assertNotEquals(evidence[0].evidenceId, evidence[1].evidenceId)
    }

    @Test
    fun `09-20 proven - A's own one-offer receipt pays A, B's later read in the two-offer job never re-prices A`() {
        val a = drop("A", store = "Store A")
        val aRead = read(region(a, offers = listOf(twoOffers[0])), "A", fields(28.51))
        assertEquals(ReceiptScope.PROVEN_SINGLE, aRead.lastPostTaskCoverage!!.scope)
        val aRow = exitRows(aRead).single()
        assertEquals(28.51, aRow.dropRealizedPay!!, 1e-9)
        assertEquals(ReceiptScope.PROVEN_SINGLE, aRow.receiptScope)

        val b = drop("B", store = "Store B")
        val bRead = read(region(b, recent = listOf(a.copy(completedAt = 4_000L)), offers = twoOffers), "B", fields(20.50), at = 6_000L)
        assertUnproven(bRead, ReceiptProofGap.OFFER_COUNT)
        val closed = close(bRead)
        assertNull(closed.decision)
        assertTrue("A keeps its own receipt", closed.reprices.none { it.taskId == "A" })
        assertNoReceiptMoney(closed.rows.filter { it.taskId == "B" })
    }

    // ---- :108, one job, two drops ----------------------------------------------------------------------------------

    @Test
    fun `one job, two drops - unproven for any offer count or slot hint, no receipt money`() {
        for (offers in listOf(0, 1, 2)) for (hint in listOf(null, "wrong-offer")) {
            val a = drop("A", completedAt = 4_000L)
            val b = drop("B").copy(mintedByOfferHash = hint)
            val source = read(region(b, recent = listOf(a), offers = (1..offers).map { RS.offer("o$it") }), "B", fields(20.0))
            val closed = close(source)
            assertNull("offers=$offers hint=$hint", closed.decision)
            assertNoReceiptMoney(closed.rows)
            assertEquals(ReceiptScope.UNPROVEN, source.lastPostTaskCoverage!!.scope)
            assertTrue(source.lastPostTaskCoverage!!.taskIds.isEmpty())
        }
    }

    @Test
    fun `one job, one drop - the proven twin re-prices its drop with the whole receipt`() {
        val source = read(region(drop("B")), "B", fields(20.0))
        assertEquals(setOf("B"), source.lastPostTaskCoverage!!.taskIds)
        val closed = close(source)
        assertEquals(mapOf("B" to 20.0), closed.decision!!.shares)
        assertEquals(20.0, closed.rows.single().dropRealizedPay!!, 1e-9)
        assertEquals(ReceiptScope.PROVEN_SINGLE, closed.reprices.single().receiptScope)
    }

    // ---- :134, Astra a ---------------------------------------------------------------------------------------------

    @Test
    fun `Astra a - X keeps its own ten only as its own one-offer job, Y reads are unproven, sum at most ten`() {
        // Arrangement 1: X is its own one-offer job; Y1 and Y2 share a second job.
        val xRead = read(region(drop("X", jobId = "JX")), "X", fields(10.0))
        val xMoney = exitRows(xRead).single().receiptMoney()
        assertEquals(10.0, xMoney, 1e-9)
        val y1 = drop("Y1", completedAt = 3_500L, jobId = "JY")
        val ySource = read(region(drop("Y2", jobId = "JY"), recent = listOf(y1), offers = listOf(RS.offer("oY"))), "Y2", fields(20.0))
        val yClosed = close(ySource)
        assertNull(yClosed.decision)
        assertNoReceiptMoney(yClosed.rows)
        assertTrue(xMoney + yClosed.rows.sumOf { it.receiptMoney() } <= 10.0 + 1e-9)
        assertUnproven(ySource, ReceiptProofGap.OTHER_DROPS)

        // Arrangement 2: X, Y1 and Y2 in one two-offer job — nothing is proven.
        val shared = listOf(RS.offer("oX"), RS.offer("oY"))
        val x2 = read(region(drop("X"), offers = shared, jobTasks = listOf(drop("X"), drop("Y1"), drop("Y2"))), "X", fields(10.0))
        assertUnproven(x2, ReceiptProofGap.OFFER_COUNT)
        assertNoReceiptMoney(exitRows(x2))
    }

    // ---- :210, Astra b ---------------------------------------------------------------------------------------------

    @Test
    fun `Astra b unproven - a post-close combined update in a two-offer job re-prices nothing, never thirty-two`() {
        val a = drop("A", completedAt = 4_000L)
        val source = read(region(drop("B"), recent = listOf(a), offers = listOf(RS.offer("o1"), RS.offer("o2"))), "B", fields(20.0))
        val closed = close(source)
        assertNull(closed.decision)
        val after = RS.stepper.stepWithDecisions(
            closed.region.copy(lastActedFlow = Flow.Idle), FlowRegion(flow = Flow.Idle),
            FlowRegion(flow = Flow.PostTask), RS.postTask(fields(22.0), 6_000L), RS.policy,
        )
        assertNull(after.decisions.receiptReprice)
        assertEquals(ReceiptProofGap.NO_LIVE_JOB, after.region.lastPostTaskCoverage!!.gap)
        assertEquals(0.0, closed.rows.sumOf { it.receiptMoney() }, 1e-9)
    }

    @Test
    fun `Astra b proven - a single-drop job's collapsed twenty expanded post-close to twenty-two re-prices once`() {
        val f = ReceiptFold(RS.live())
            .step(RS.postTask(RS.collapsed(20.0), 10_000L))
            .step(RS.timeout(30_000L, TimeoutType.GRACE_COMMIT))
        assertNull(f.region.activeJob)
        f.step(RS.postTask(RS.expanded(itemized(12.0, 10.0)), 31_000L))
        assertEquals(1, f.reprices().size)
        assertEquals(mapOf("t1" to 22.0), f.receiptMoney())
    }

    // ---- :235, a restored wide coverage ----------------------------------------------------------------------------

    @Test
    fun `a pre-1598 restored wide coverage is honoured as written (legacy rule), a fresh read is unproven`() {
        val b = drop("B", completedAt = 4_000L)
        val restored = region(drop("A"), recent = listOf(b), offers = listOf(RS.offer("o1"), RS.offer("o2"))).copy(
            lastPostTaskFields = fields(20.0),
            lastPostTaskCoverage = ReceiptCoverage(setOf("A", "B")),
            lastAnnouncedPostTaskTaskId = "A",
        )
        assertEquals(10.0, exitRows(restored).single().dropRealizedPay!!, 1e-9)
        assertEquals(mapOf("A" to 10.0, "B" to 10.0), close(restored).decision!!.shares)

        val fresh = read(restored, "A", fields(20.0))
        assertNull(close(fresh).decision)
        assertUnproven(fresh, ReceiptProofGap.OFFER_COUNT)
    }

    // ---- :261-:291, the "C" repeats --------------------------------------------------------------------------------

    @Test
    fun `C repeats - equal totals, a changed itemization and a repeated unsplit twenty are all unproven revisions`() {
        val offers = listOf(RS.offer("oX"), RS.offer("oY"))
        val a = drop("A")
        var r = read(region(a, jobTasks = listOf(a, drop("B")), offers = offers), "A", fields(10.0, base = 5.0))
        // Same total, a changed itemization: a new revision of the same record.
        r = read(r, "A", fields(10.0, base = 4.0), at = 4_600L)
        assertNoReceiptMoney(exitRows(r))
        // The next drop's read of an equal, unsplit total: a new record.
        val b = r.copy(activeTask = drop("B"), recentTasks = listOf(a.copy(completedAt = 4_700L)))
        r = read(b, "B", fields(10.0, base = 4.0), at = 5_000L)
        r = read(r, "B", fields(20.0), at = 5_100L)
        val closed = close(r)
        assertNull(closed.decision)
        assertNoReceiptMoney(closed.rows)
        assertEquals(listOf(1, 2, 1, 2), evidence.map { it.revision })
        assertEquals(2, evidence.map { it.evidenceId }.toSet().size)
        assertTrue(evidence.all { it.scope == ReceiptScope.UNPROVEN })
    }

    // ---- the X/Y trace: a repeated $30 receipt must never record $50 -----------------------------------------------

    @Test
    fun `X-Y trace - X ten, then Y's unsplit twenty read after each drop, never more than ten in receipt money`() {
        // Arrangement 1: X proven alone; Y one offer whose card showed two drops.
        val xMoney = exitRows(read(region(drop("X", jobId = "JX")), "X", fields(10.0))).single().receiptMoney()
        val yOffer = listOf(RS.offer("oY", count = 2))
        val y1 = drop("Y1", jobId = "JY")
        val y2 = drop("Y2", jobId = "JY")
        val afterY1 = read(region(y1, offers = yOffer, jobTasks = listOf(y1, y2)), "Y1", RS.collapsed(20.0))
        val y1Money = exitRows(afterY1).sumOf { it.receiptMoney() }
        val afterY2 = read(region(y2, recent = listOf(y1.copy(completedAt = 4_000L)), offers = yOffer), "Y2", fields(20.0), at = 6_000L)
        val closed = close(afterY2)
        val total = xMoney + y1Money + closed.rows.sumOf { it.receiptMoney() } + closed.reprices.sumOf { it.dropRealizedPay }
        assertTrue("receipt money $total ≤ 10", total <= 10.0 + 1e-9)
        assertUnproven(afterY1, ReceiptProofGap.CARD_COUNT_NOT_ONE)

        // Arrangement 2: X and Y in one two-offer job.
        val shared = listOf(RS.offer("oX"), RS.offer("oY", count = 2))
        val x = drop("X")
        val xr = read(region(x, offers = shared, jobTasks = listOf(x, drop("Y1"), drop("Y2"))), "X", fields(10.0))
        val x2Money = exitRows(xr).sumOf { it.receiptMoney() }
        val yr = read(region(drop("Y2"), recent = listOf(x.copy(completedAt = 3_000L), drop("Y1", completedAt = 4_000L)), offers = shared),
            "Y2", fields(20.0), at = 6_000L)
        val closed2 = close(yr)
        val total2 = x2Money + closed2.rows.sumOf { it.receiptMoney() } + closed2.reprices.sumOf { it.dropRealizedPay }
        assertTrue("receipt money $total2 ≤ 10", total2 <= 10.0 + 1e-9)
    }

    // ---- the 2026-10-11 shape --------------------------------------------------------------------------------------

    @Test
    fun `10-11 shape - a four-offer job's final ten-forty read on a store-less subject places nothing`() {
        val offers = (1..4).map { RS.offer("o$it", payAmount = 6.0, hints = listOf("Store $it")) }
        val done = (1..4).map { drop("D$it", store = "Store $it", completedAt = 3_000L + it) }
        val subject = drop("D5", store = null)
        val base = region(subject, recent = done, offers = offers)
        val withReceipt = read(base, "D5", fields(10.40, base = 6.0))
        val closed = close(withReceipt)
        assertNull("no re-price at close", closed.decision)
        assertNoReceiptMoney(closed.rows)
        assertUnproven(withReceipt, ReceiptProofGap.OFFER_COUNT)

        // The estimate attribution is exactly the receipt-less job's.
        val plain = close(base)
        fun attribution(rows: List<DeliveryPayload>) =
            rows.associate { it.taskId to Triple(it.offerPayShare, it.offerPayAttribution, it.offerPayAttributedHash) }
        assertEquals(attribution(plain.rows), attribution(closed.rows))
    }
}

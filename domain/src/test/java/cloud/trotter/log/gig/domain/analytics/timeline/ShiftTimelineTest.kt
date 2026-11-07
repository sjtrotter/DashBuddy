package cloud.trotter.log.gig.domain.analytics.timeline

import cloud.trotter.log.gig.domain.analytics.OfferOutcome
import cloud.trotter.log.gig.domain.analytics.PayBasis
import cloud.trotter.log.gig.domain.model.offer.OfferQuoteBasis
import cloud.trotter.log.gig.domain.model.offer.OfferScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftTimelineTest {

    private fun offer(
        seq: Long,
        at: Long,
        outcome: OfferOutcome = OfferOutcome.ACCEPTED,
        hash: String = "h$seq",
        pay: Double? = 9.75,
        miles: Double? = 3.1,
        store: String? = "Five Guys",
        scope: OfferScope? = OfferScope.STANDALONE,
        basis: OfferQuoteBasis? = OfferQuoteBasis.TOTAL,
        linkedJobId: String? = null,
    ) = TimelineOffer(seq, hash, outcome, at, pay, miles, store, scope, basis, linkedJobId)

    private fun drop(
        seq: Long,
        at: Long,
        jobId: String = "J1",
        pay: Double? = 11.25,
        basis: String = PayBasis.DROP_SHARE,
        hashes: List<String>? = listOf("h1"),
        sole: String? = hashes?.singleOrNull(),
        store: String? = "Five Guys",
        cash: Double? = null,
    ) = TimelineDelivery(seq, jobId, store, at, pay, basis, cash, hashes, sole)

    private fun pickup(seq: Long, jobId: String = "J1", arrived: Long? = 1_200, confirmed: Long? = 1_500) =
        TimelinePickup(seq, jobId, "Five Guys", arrived, confirmed)

    private fun fold(
        offers: List<TimelineOffer> = emptyList(),
        pickups: List<TimelinePickup> = emptyList(),
        deliveries: List<TimelineDelivery> = emptyList(),
        running: Boolean = false,
    ) = ShiftTimeline.fold(offers, pickups, deliveries, running)

    private fun List<TimelineItem>.cards() = filterIsInstance<JobCard>()

    @Test fun `one offer and one drop is one card with its pay and milestones`() {
        val items = fold(listOf(offer(1, 1_000)), listOf(pickup(2)), listOf(drop(3, 2_000)))
        val card = items.single() as JobCard
        assertEquals("job:J1", card.key)
        assertEquals("Five Guys", card.stores)
        assertEquals(1_000L, card.firstOfferAt)
        assertEquals(OfferLine(9.75, 3.1), card.offerLine)
        assertTrue(card.addOns.isEmpty())
        assertEquals(11.25, card.paidTotal!!, 1e-9)
        assertFalse(card.paidTotalEstimated)
        assertEquals(PaySource.RECEIPT, card.deliveries.single().source)
        assertEquals(JobState.NORMAL, card.state)
        assertEquals(
            listOf(MilestoneKind.ACCEPTED_OFFERED, MilestoneKind.AT_STORE, MilestoneKind.PICKED_UP, MilestoneKind.DELIVERED),
            card.milestones.map { it.kind },
        )
        assertEquals(OfferLine(9.75, 3.1), card.milestones.first().offer)
        assertEquals(11.25, card.milestones.last().delivery!!.pay!!, 1e-9)
    }

    @Test fun `missing milestones are absent`() {
        val card = fold(listOf(offer(1, 1_000)), listOf(pickup(2, arrived = null)), listOf(drop(3, 2_000))).single() as JobCard
        assertEquals(listOf(MilestoneKind.ACCEPTED_OFFERED, MilestoneKind.PICKED_UP, MilestoneKind.DELIVERED), card.milestones.map { it.kind })
    }

    @Test fun `a stack is one card with two receipt-split delivery lines`() {
        val items = fold(
            listOf(offer(1, 1_000, store = "Five Guys & Chipotle")),
            deliveries = listOf(drop(3, 2_000, pay = 6.0), drop(4, 2_500, pay = 5.25, store = "Chipotle")),
        )
        val card = items.single() as JobCard
        assertEquals("Five Guys & Chipotle", card.stores)
        assertEquals(listOf(3L, 4L), card.deliveries.map { it.deliveryEventSequenceId })
        assertTrue(card.deliveries.all { it.source == PaySource.RECEIPT_SPLIT })
        assertEquals(11.25, card.paidTotal!!, 1e-9)
    }

    @Test fun `an incremental add-on is a delta line`() {
        val hashes = listOf("h1", "h2")
        val card = fold(
            listOf(offer(1, 1_000), offer(2, 1_400, pay = 4.0, miles = 1.8, scope = OfferScope.ADD_ON, basis = OfferQuoteBasis.INCREMENTAL)),
            deliveries = listOf(drop(5, 2_000, hashes = hashes), drop(6, 2_300, hashes = hashes)),
        ).single() as JobCard
        assertEquals(OfferLine(9.75, 3.1), card.offerLine)
        assertEquals(listOf(AddOnLine.Incremental(2, 4.0, 1.8)), card.addOns)
        assertEquals(listOf(1L, 2L), card.offerEventSequenceIds)
        assertEquals(AddOnLine.Incremental(2, 4.0, 1.8), card.milestones[1].addOn)
    }

    @Test fun `a total-basis add-on is a new-total line`() {
        val hashes = listOf("h1", "h2")
        val card = fold(
            listOf(offer(1, 1_000), offer(2, 1_400, pay = 13.75, miles = 4.9, scope = OfferScope.ADD_ON, basis = OfferQuoteBasis.TOTAL)),
            deliveries = listOf(drop(5, 2_000, hashes = hashes)),
        ).single() as JobCard
        assertEquals(listOf(AddOnLine.NewTotal(2, 13.75, 4.9)), card.addOns)
    }

    @Test fun `a pre-refold add-on with both columns null is a second offer line`() {
        val card = fold(
            listOf(offer(1, 1_000, scope = null, basis = null), offer(2, 1_400, pay = 4.0, scope = null, basis = null)),
            deliveries = listOf(drop(5, 2_000, hashes = null, sole = null)),
        ).let { items ->
            // Pre-refold rows carry no hash list and no sole hash for a two-offer job: the join falls to linkedJobId.
            assertEquals(3, items.size)
            fold(
                listOf(
                    offer(1, 1_000, scope = null, basis = null, linkedJobId = "J1"),
                    offer(2, 1_400, pay = 4.0, scope = null, basis = null, linkedJobId = "J1"),
                ),
                deliveries = listOf(drop(5, 2_000, hashes = null, sole = null)),
            ).single() as JobCard
        }
        assertEquals(OfferLine(9.75, 3.1), card.offerLine)
        assertEquals(listOf(AddOnLine.Offered(2, 4.0, 3.1)), card.addOns)
    }

    @Test fun `a delivery with no offer is a card with no offer line, ordered by its delivery`() {
        val items = fold(
            listOf(offer(1, 3_000, hash = "other")),
            deliveries = listOf(drop(2, 2_000, jobId = "J0", hashes = null, sole = null), drop(9, 4_000, jobId = "J9", hashes = listOf("other"))),
        )
        val first = items.first() as JobCard
        assertEquals("job:J0", first.key)
        assertNull(first.offerLine)
        assertNull(first.firstOfferAt)
        assertEquals("Five Guys", first.stores)
        assertEquals("job:J9", items[1].key)
    }

    @Test fun `a run of one declined offer is one collapsed row`() {
        val run = fold(listOf(offer(1, 1_000, OfferOutcome.DECLINED, pay = 6.5, miles = 4.1, store = "Chick-fil-A"))).single() as PassedRun
        assertEquals("run:1", run.key)
        assertEquals(1, run.declined)
        assertEquals(0, run.expired)
        assertEquals(PassedOffer(1, 1_000, "Chick-fil-A", 6.5, 4.1, OfferOutcome.DECLINED), run.offers.single())
    }

    @Test fun `declined and expired offers form one run whose counts equal its offers`() {
        val outcomes = listOf(OfferOutcome.DECLINED, OfferOutcome.TIMED_OUT, OfferOutcome.DECLINED, OfferOutcome.TIMED_OUT, OfferOutcome.DECLINED)
        val run = fold(outcomes.mapIndexed { i, o -> offer(i + 1L, 1_000L + i * 100, o) }).single() as PassedRun
        assertEquals(3, run.declined)
        assertEquals(2, run.expired)
        assertEquals(run.offers.size, run.declined + run.expired)
        assertEquals(1_000L, run.from)
        assertEquals(1_400L, run.to)
    }

    @Test fun `runs sit before the first job, between jobs and after the last`() {
        val items = fold(
            listOf(
                offer(1, 100, OfferOutcome.DECLINED),
                offer(2, 200, OfferOutcome.TIMED_OUT),
                offer(3, 300, hash = "a"),
                offer(4, 400, OfferOutcome.DECLINED),
                offer(5, 500, hash = "b"),
                offer(6, 600, OfferOutcome.TIMED_OUT),
            ),
            deliveries = listOf(drop(10, 350, jobId = "A", hashes = listOf("a")), drop(11, 550, jobId = "B", hashes = listOf("b"))),
        )
        assertEquals(listOf("run:1", "job:A", "run:4", "job:B", "run:6"), items.map { it.key })
        val first = items[0] as PassedRun
        assertEquals(1, first.declined)
        assertEquals(1, first.expired)
    }

    @Test fun `an add-on accepted between passed offers does not split their run`() {
        val hashes = listOf("a", "a2")
        val items = fold(
            listOf(
                offer(1, 100, hash = "a"),
                offer(2, 200, OfferOutcome.DECLINED),
                offer(3, 300, hash = "a2", scope = OfferScope.ADD_ON, basis = OfferQuoteBasis.INCREMENTAL),
                offer(4, 400, OfferOutcome.DECLINED),
            ),
            deliveries = listOf(drop(10, 500, jobId = "A", hashes = hashes)),
        )
        assertEquals(listOf("job:A", "run:2"), items.map { it.key })
        assertEquals(2, (items[1] as PassedRun).declined)
    }

    @Test fun `a manual delivery reads you entered`() {
        val card = fold(deliveries = listOf(drop(1, 1_000, jobId = "M", basis = PayBasis.MANUAL, pay = 8.0, hashes = null, cash = 5.0))).single() as JobCard
        assertEquals(PaySource.YOU_ENTERED, card.deliveries.single().source)
        assertEquals(8.0, card.paidTotal!!, 1e-9)
        assertEquals(5.0, card.cashTips!!, 1e-9)
        assertEquals(5.0, card.deliveries.single().cashTip!!, 1e-9)
    }

    @Test fun `an accepted offer with no delivery reads accepted while running and no delivery after`() {
        val live = fold(listOf(offer(1, 1_000)), running = true).single() as JobCard
        assertEquals("offer:1", live.key)
        assertNull(live.jobId)
        assertEquals(JobState.ACCEPTED_NO_DELIVERY_YET, live.state)
        assertNull(live.paidTotal)
        val ended = fold(listOf(offer(1, 1_000)), running = false).single() as JobCard
        assertEquals(JobState.NO_DELIVERY, ended.state)
    }

    @Test fun `a linked offer with only a pickup carries the job and its store milestones`() {
        val card = fold(listOf(offer(1, 1_000, linkedJobId = "J1")), listOf(pickup(2)), running = true).single() as JobCard
        assertEquals("job:J1", card.key)
        assertEquals(JobState.ACCEPTED_NO_DELIVERY_YET, card.state)
        assertEquals(listOf(MilestoneKind.ACCEPTED_OFFERED, MilestoneKind.AT_STORE, MilestoneKind.PICKED_UP), card.milestones.map { it.kind })
    }

    @Test fun `the hash join wins over linkedJobId and an ambiguous hash falls back only to a consistent link`() {
        val items = fold(
            listOf(offer(1, 1_000, hash = "x", linkedJobId = "B")),
            deliveries = listOf(drop(5, 2_000, jobId = "A", hashes = listOf("x")), drop(6, 3_000, jobId = "B", hashes = null, sole = null)),
        )
        assertEquals(listOf(1L), (items.cards().single { it.jobId == "A" }).offerEventSequenceIds)
        assertTrue(items.cards().single { it.jobId == "B" }.offerEventSequenceIds.isEmpty())

        val ambiguous = fold(
            listOf(offer(1, 1_000, hash = "x", linkedJobId = "C")),
            deliveries = listOf(drop(5, 2_000, jobId = "A", hashes = listOf("x")), drop(6, 3_000, jobId = "B", hashes = listOf("x"))),
        )
        assertEquals(listOf("offer:1", "job:A", "job:B"), ambiguous.map { it.key })
    }

    @Test fun `ordering ties fall to the sequence id`() {
        val items = fold(
            listOf(offer(7, 1_000, hash = "b"), offer(3, 1_000, hash = "a"), offer(5, 1_000, OfferOutcome.DECLINED)),
            deliveries = listOf(drop(10, 2_000, jobId = "B", hashes = listOf("b")), drop(11, 2_000, jobId = "A", hashes = listOf("a"))),
        )
        assertEquals(listOf("job:A", "run:5", "job:B"), items.map { it.key })
    }

    @Test fun `an offer-amount estimate marks its line and the card total`() {
        val card = fold(listOf(offer(1, 1_000)), deliveries = listOf(drop(2, 2_000, basis = PayBasis.OFFER_PAY, pay = 9.75))).single() as JobCard
        val line = card.deliveries.single()
        assertEquals(PaySource.OFFER_AMOUNT_EST, line.source)
        assertTrue(line.estimated)
        assertTrue(card.paidTotalEstimated)
    }

    @Test fun `a job with no priced delivery has no paid total`() {
        val card = fold(listOf(offer(1, 1_000)), deliveries = listOf(drop(2, 2_000, basis = PayBasis.NONE, pay = null))).single() as JobCard
        assertNull(card.paidTotal)
        assertEquals(PaySource.NO_PAY_FOUND, card.deliveries.single().source)
    }

    @Test fun `PaySource maps every pay basis`() {
        val cases = listOf(
            Triple(PayBasis.DROP_SHARE, 1, PaySource.RECEIPT),
            Triple(PayBasis.DROP_SHARE, 2, PaySource.RECEIPT_SPLIT),
            Triple(PayBasis.RECEIPT_TOTAL, 1, PaySource.RECEIPT),
            Triple(PayBasis.RECEIPT_TOTAL, 3, PaySource.RECEIPT),
            Triple(PayBasis.OFFER_PAY, 2, PaySource.OFFER_AMOUNT_EST),
            Triple(PayBasis.NONE, 1, PaySource.NO_PAY_FOUND),
            Triple(PayBasis.SUSPECT_FULL_RECEIPT, 2, PaySource.NO_PAY_FOUND),
            Triple(PayBasis.MANUAL, 1, PaySource.YOU_ENTERED),
            Triple(PayBasis.USER_CORRECTED, 1, PaySource.YOU_ENTERED),
            Triple("SOMETHING_NEW", 1, PaySource.NO_PAY_FOUND),
        )
        for ((basis, drops, expected) in cases) assertEquals(basis, expected, PaySource.of(basis, 5.0, drops))
        assertEquals(PaySource.NO_PAY_FOUND, PaySource.of(PayBasis.DROP_SHARE, null, 1))
        assertEquals(PaySource.YOU_ENTERED, PaySource.of(PayBasis.MANUAL, null, 1))
        assertTrue(PaySource.entries.single { it.estimated } == PaySource.OFFER_AMOUNT_EST)
    }
}

package cloud.trotter.log.gig.ui.main.analytics

import cloud.trotter.log.gig.domain.analytics.OfferOutcome
import cloud.trotter.log.gig.domain.analytics.PayBasis
import cloud.trotter.log.gig.domain.analytics.checks.ShiftCheck
import cloud.trotter.log.gig.domain.analytics.timeline.ShiftTimeline
import cloud.trotter.log.gig.domain.analytics.timeline.TimelineDelivery
import cloud.trotter.log.gig.domain.analytics.timeline.TimelineOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where a `row` link lands on the shift page: a job (its sheet opens), a passed offer (its run opens), a run. */
class ShiftPageModelTest {
    private fun offer(seq: Long, at: Long, outcome: OfferOutcome) =
        TimelineOffer(seq, "h$seq", outcome, at, 5.0, 2.0, "Store $seq", null, null, linkedJobId = null)

    private val items = ShiftTimeline.fold(
        offers = listOf(
            offer(1, 10, OfferOutcome.DECLINED),
            offer(2, 20, OfferOutcome.TIMED_OUT),
            offer(3, 30, OfferOutcome.ACCEPTED),
            offer(4, 40, OfferOutcome.ACCEPTED),
            offer(5, 50, OfferOutcome.DECLINED),
        ),
        pickups = emptyList(),
        deliveries = listOf(
            TimelineDelivery(9, "job-1", "Store 3", null, 45, 8.0, PayBasis.RECEIPT_TOTAL, null, listOf("h3", "h4"), null),
        ),
        running = false,
    )

    @Test fun `a job's offer or add-on lands on its card and opens its sheet`() {
        assertEquals(RowLanding("offer:3", null, "offer:3"), RowLanding.of(items, "offer:3"))
        assertEquals(RowLanding("offer:3", null, "offer:3"), RowLanding.of(items, "offer:4"))
    }

    @Test fun `a job link lands on the job's card whatever the card is keyed by`() {
        assertEquals(RowLanding("offer:3", null, "offer:3"), RowLanding.of(items, jobRowKey("job-1")))
        assertNull(RowLanding.of(items, jobRowKey("job-2")))
    }

    @Test fun `a check link lands on the offer with no delivery first, then the job with more accepts`() {
        val more = ShiftCheck.MoreAcceptsThanDeliveries("job-1", accepted = 2, delivered = 1)
        assertEquals(jobRowKey("job-1"), landingRowOf(listOf(more)))
        assertEquals("offer:7", landingRowOf(listOf(more, ShiftCheck.NoDelivery(7))))
        assertNull(landingRowOf(emptyList()))
    }

    // #1119 S2: a tie lands on the job it left unmatched, after the two older checks.
    @Test fun `a tie check lands on its job, after the no-delivery and more-accepts checks`() {
        val tie = ShiftCheck.OfferNotMatched("job-9", listOf(3L, 4L))
        assertEquals(jobRowKey("job-9"), landingRowOf(listOf(tie)))
        assertEquals(jobRowKey("job-1"), landingRowOf(listOf(tie, ShiftCheck.MoreAcceptsThanDeliveries("job-1", 2, 1))))
        assertEquals("offer:7", landingRowOf(listOf(tie, ShiftCheck.NoDelivery(7))))
    }

    @Test fun `a passed offer opens its run and lands on its row`() {
        assertEquals(RowLanding(passedRowKey(2), "run:1", null), RowLanding.of(items, "offer:2"))
        assertEquals(RowLanding(passedRowKey(5), "run:5", null), RowLanding.of(items, "offer:5"))
    }

    @Test fun `a run key lands on the run and an unknown row lands nowhere`() {
        assertEquals(RowLanding("run:1", null, null), RowLanding.of(items, "run:1"))
        assertNull(RowLanding.of(items, "offer:99"))
        assertNull(RowLanding.of(items, "nonsense"))
    }
}

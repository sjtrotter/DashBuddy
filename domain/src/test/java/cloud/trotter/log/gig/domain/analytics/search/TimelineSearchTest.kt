package cloud.trotter.log.gig.domain.analytics.search

import cloud.trotter.log.gig.domain.analytics.OfferOutcome
import cloud.trotter.log.gig.domain.analytics.PayBasis
import cloud.trotter.log.gig.domain.analytics.timeline.PassedRun
import cloud.trotter.log.gig.domain.analytics.timeline.ShiftTimeline
import cloud.trotter.log.gig.domain.analytics.timeline.TimelineDelivery
import cloud.trotter.log.gig.domain.analytics.timeline.TimelineOffer
import cloud.trotter.log.gig.domain.model.event.payload.OfferOutcomeResolution
import cloud.trotter.log.gig.domain.model.offer.OfferQuoteBasis
import cloud.trotter.log.gig.domain.model.offer.OfferScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineSearchTest {

    private fun offer(
        seq: Long,
        at: Long,
        store: String?,
        outcome: OfferOutcome = OfferOutcome.ACCEPTED,
        scope: OfferScope = OfferScope.STANDALONE,
        basis: OfferQuoteBasis = OfferQuoteBasis.TOTAL,
    ) = TimelineOffer(seq, "h$seq", outcome, at, 7.0, 3.0, store, scope, basis, linkedJobId = null)

    private fun drop(seq: Long, at: Long, jobId: String, store: String?, hashes: List<String>) =
        TimelineDelivery(seq, jobId, store, null, at, 6.0, PayBasis.DROP_SHARE, null, hashes, hashes.singleOrNull())

    /** A declined run, a stack, an add-on job, another run, and a delivery with no offer. */
    private val offers = listOf(
        offer(1, 100, "Chick-fil-A", OfferOutcome.DECLINED),
        offer(2, 200, "Wingstop", OfferOutcome.TIMED_OUT),
        offer(3, 300, "Five Guys & Chipotle"),
        offer(4, 400, "Sample Burgers"),
        offer(5, 450, "Wingstop", scope = OfferScope.ADD_ON, basis = OfferQuoteBasis.INCREMENTAL),
        offer(6, 500, "Wingstop", OfferOutcome.DECLINED),
        offer(7, 510, "Panda Express", OfferOutcome.DECLINED),
    )
    private val deliveries = listOf(
        drop(11, 350, "J1", "Five Guys", listOf("h3")),
        drop(12, 360, "J1", "Chipotle", listOf("h3")),
        drop(13, 470, "J2", "Sample Burgers", listOf("h4", "h5")),
        drop(14, 480, "J2", "Wingstop", listOf("h4", "h5")),
        drop(15, 600, "J3", "Sample Pizza", emptyList()),
    )
    private val items = ShiftTimeline.fold(offers, emptyList(), deliveries, running = false)

    private fun search(text: String, offers: List<TimelineOffer> = this.offers) =
        TimelineSearch.filter(items, offers, deliveries, StoreQuery.of(text))

    @Test fun `a stack is found by its second store, whole`() {
        val row = search("chipotle").single() as SearchRow.Job
        assertEquals("offer:3", row.key)
        assertEquals(2, row.card.deliveries.size)
    }

    @Test fun `an add-on's merchant selects its card`() {
        val rows = search("wingstop")
        val job = rows.filterIsInstance<SearchRow.Job>().single()
        assertEquals("offer:4", job.key)
        assertTrue(job.card.offerEventSequenceIds.contains(5L))
    }

    @Test fun `a delivery-only match selects its card`() {
        val row = search("pizza").single() as SearchRow.Job
        assertEquals("job:J3", row.key)
    }

    @Test fun `a passed offer inside a run comes out alone, in timeline order`() {
        // The timed-out Wingstop sits in the run with Chick-fil-A; the declined one in the run with Panda Express.
        val runs = items.filterIsInstance<PassedRun>()
        assertEquals(listOf(2, 2), runs.map { it.offers.size })
        val rows = search("wingstop")
        assertEquals(listOf("offer:2", "offer:4", "offer:6"), rows.map { it.key })
        assertEquals(OfferOutcome.TIMED_OUT, (rows[0] as SearchRow.Passed).offer.outcome)
        assertEquals(OfferOutcome.DECLINED, (rows[2] as SearchRow.Passed).offer.outcome)
    }

    @Test fun `a resolved offer still counts`() {
        val resolved = offers.map { if (it.eventSequenceId == 3L) it.copy(outcomeResolved = OfferOutcomeResolution.UNASSIGNED_ATTESTED) else it }
        val folded = ShiftTimeline.fold(resolved, emptyList(), emptyList(), running = false)
        val rows = TimelineSearch.filter(folded, resolved, emptyList(), StoreQuery.of("five guys"))
        assertEquals(listOf("offer:3"), rows.map { it.key })
    }

    @Test fun `a blank query or no match is empty`() {
        assertTrue(search("   ").isEmpty())
        assertTrue(search("taco").isEmpty())
    }
}

package cloud.trotter.dashbuddy.domain.analytics

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #975 / brief §5 — the "said no to ~$X" honesty predicates, and the typed offer-outcome vocabulary
 * the tab's filter and pill share.
 *
 * The declined sum itself is SQL (`SUM` skips nulls, so a #936 no-verdict decline never lands in it);
 * what has to be decided in code is whether that sum is a statement about MONEY or about MISSING
 * DATA. A window whose declines were all unpriced sums to `0.0`, and rendering it as "$0.00" would
 * read as "you skipped nothing of value" — a claim about the pay dressed up out of a gap in the
 * record (§9). These two predicates are what let the surface tell the two apart.
 */
class DecisionEconomicsTest {

    private fun decisions(declined: Int, declinedWithEstimate: Int, declinedEstNet: Double) =
        DecisionEconomics.EMPTY.copy(
            received = declined,
            declined = declined,
            declinedEstNet = declinedEstNet,
            declinedWithEstimate = declinedWithEstimate,
        )

    @Test
    fun `declines with no estimate at all are distinguishable from declines worth nothing`() {
        val unpriced = decisions(declined = 4, declinedWithEstimate = 0, declinedEstNet = 0.0)

        assertFalse(unpriced.hasDeclinedEstimates)
        assertFalse(unpriced.declinedEstimatesComplete)
        // The sum is structurally zero — the surface must NOT render it as a dollar figure.
        assertEquals(0.0, unpriced.declinedEstNet, 1e-9)
    }

    @Test
    fun `partial estimate coverage is reported as partial, so the figure reads as a floor`() {
        val partial = decisions(declined = 5, declinedWithEstimate = 2, declinedEstNet = 18.0)

        assertTrue(partial.hasDeclinedEstimates)
        assertFalse(partial.declinedEstimatesComplete)
    }

    @Test
    fun `full coverage is the only case the figure describes every decline`() {
        val full = decisions(declined = 3, declinedWithEstimate = 3, declinedEstNet = 27.5)

        assertTrue(full.hasDeclinedEstimates)
        assertTrue(full.declinedEstimatesComplete)
    }

    @Test
    fun `no declines is not complete coverage — there is nothing to be complete about`() {
        assertFalse(DecisionEconomics.EMPTY.declinedEstimatesComplete)
        assertFalse(DecisionEconomics.EMPTY.hasDeclinedEstimates)
        assertNull(DecisionEconomics.EMPTY.acceptanceRate)
    }

    @Test
    fun `offer outcomes round-trip through the stored AppEventType name, with no literals`() {
        assertEquals(AppEventType.OFFER_ACCEPTED.name, OfferOutcome.ACCEPTED.eventTypeName)
        assertEquals(AppEventType.OFFER_DECLINED.name, OfferOutcome.DECLINED.eventTypeName)
        assertEquals(AppEventType.OFFER_TIMEOUT.name, OfferOutcome.TIMED_OUT.eventTypeName)

        OfferOutcome.entries.forEach { outcome ->
            assertEquals(outcome, OfferOutcome.fromEventTypeName(outcome.eventTypeName))
        }
        // An outcome this build doesn't know fails NULL rather than throwing into the list read.
        assertNull(OfferOutcome.fromEventTypeName(AppEventType.OFFER_RECEIVED.name))
        assertNull(OfferOutcome.fromEventTypeName(""))
    }

    @Test
    fun `the ALL filter carries no outcome predicate, every other chip pins exactly one`() {
        assertNull(OfferFilter.ALL.outcome)
        assertEquals(OfferOutcome.ACCEPTED, OfferFilter.ACCEPTED.outcome)
        assertEquals(OfferOutcome.DECLINED, OfferFilter.DECLINED.outcome)
        assertEquals(OfferOutcome.TIMED_OUT, OfferFilter.TIMED_OUT.outcome)
        // One chip per outcome, plus ALL — so no outcome can be unreachable from the filter row.
        assertEquals(OfferOutcome.entries.size + 1, OfferFilter.entries.size)
    }
}

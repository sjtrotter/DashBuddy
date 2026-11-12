package cloud.trotter.log.gig.domain.analytics.timeline

import cloud.trotter.log.gig.domain.analytics.PayBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** #1599: a dasher's receipt match reads as its own source, never an estimate. */
class PaySourceTest {
    @Test fun `a matched receipt share is matched by you and not estimated`() { // non-vacuous
        val source = PaySource.of(PayBasis.RECEIPT_MATCHED, 10.25, 2)
        assertEquals(PaySource.MATCHED_BY_YOU, source)
        assertFalse(source.estimated)
    }
}

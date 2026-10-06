package cloud.trotter.dashbuddy.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayMixTest {
    private fun economics(recorded: Double, cash: Double = 0.0, unmatched: Double = 0.0, above: Double = 0.0) =
        PeriodEconomics.EMPTY.copy(
            totals = PeriodTotals.EMPTY.copy(earnings = recorded),
            grossEarnings = recorded + cash + unmatched - above,
            unattributedPay = unmatched,
            overAttributedPay = above,
        )

    private fun assertIdentity(mix: PayMix) {
        assertEquals(mix.gross, mix.basePay + mix.tips + mix.cashTips + mix.notItemized +
            mix.notMatched - mix.recordedAboveReported, 0.001)
        assertFalse(mix.unreconciled)
    }

    @Test
    fun `mixed fixture and desk acceptance figures decompose exactly`() {
        val fixtures = listOf(
            economics(557.72, unmatched = 17.85) to PayMixParts(231.30, 249.48, 0.0, 23, 19, 76.94, 3, 1),
            economics(3853.22, unmatched = 336.77) to PayMixParts(1236.89, 1195.80, 0.0, 204, 110, 1174.50, 65, 5),
            economics(100.0, cash = 5.0, unmatched = 12.0, above = 8.0) to PayMixParts(30.0, 40.0, 5.0, 8, 4, 20.0, 2, 1),
        )
        for ((e, p) in fixtures) {
            val mix = PayMix.of(e, p)
            assertIdentity(mix)
            assertEquals(e.totals.earnings, mix.recorded, 0.001)
            assertEquals(p.offerEstimatePay, mix.estimatedFromOffers, 0.001)
            assertEquals(p.paylessDeliveries, mix.paylessDeliveries)
            assertFalse(mix.notItemizedNegative)
        }
    }

    @Test
    fun `negative itemization is flagged and never floored`() {
        val mix = PayMix.of(economics(10.0), PayMixParts.EMPTY.copy(basePay = 8.0, tips = 5.0))
        assertEquals(-3.0, mix.notItemized, 0.001)
        assertTrue(mix.notItemizedNegative)
        assertIdentity(mix)
        val rounding = PayMix.of(economics(12.999), PayMixParts.EMPTY.copy(basePay = 8.0, tips = 5.0))
        assertEquals(-0.001, rounding.notItemized, 1e-9)
        assertFalse(rounding.notItemizedNegative)
        assertIdentity(rounding)
    }

    @Test
    fun `zero coverage still has recorded and unmatched parts`() {
        val mix = PayMix.of(economics(60.0, unmatched = 10.0),
            PayMixParts.EMPTY.copy(deliveries = 4, offerEstimatePay = 60.0, offerEstimateDeliveries = 3, paylessDeliveries = 1))
        assertFalse(mix.hasBreakdown)
        assertFalse(mix.breakdownComplete)
        assertEquals(60.0, mix.notItemized, 0.001)
        assertEquals(10.0, mix.notMatched, 0.001)
        assertIdentity(mix)
    }

    @Test
    fun `recorded above reported is a stated deduction`() {
        val mix = PayMix.of(economics(30.0, cash = 2.0, above = 8.0),
            PayMixParts.EMPTY.copy(basePay = 10.0, tips = 15.0, cashTips = 2.0))
        assertEquals(8.0, mix.recordedAboveReported, 0.001)
        assertEquals(5.0, mix.notItemized, 0.001)
        assertIdentity(mix)
    }

    @Test
    fun `inconsistent economics raises the reconciliation guard`() {
        assertTrue(PayMix.of(economics(10.0).copy(grossEarnings = 11.0), PayMixParts.EMPTY).unreconciled)
        assertFalse(PayMix.of(economics(10.0).copy(grossEarnings = 10.001), PayMixParts.EMPTY).unreconciled)
    }

    @Test
    fun `tip share includes cash and coverage is explicit`() {
        val parts = PayMixParts(60.0, 30.0, 10.0, 4, 4, 0.0, 0, 0)
        val mix = PayMix.of(economics(90.0, cash = 10.0), parts)
        assertEquals(0.4, mix.tipShare!!, 1e-9)
        assertTrue(mix.breakdownComplete)
        assertFalse(PayMix.of(economics(90.0, cash = 10.0), parts.copy(deliveriesWithBreakdown = 2)).breakdownComplete)
        assertIdentity(mix)
        assertIdentity(PayMix.EMPTY)
        assertNull(PayMix.EMPTY.tipShare)
    }

    /** Boundary checks kept from the pre-#1135 suite: an empty mix is not "complete", and a zero gross with tips has no share. */
    @Test
    fun `empty mix is not complete and zero gross yields no tip share`() {
        assertFalse(PayMix.EMPTY.breakdownComplete)
        assertFalse(PayMix.EMPTY.hasBreakdown)
        val zeroGross = PayMix.of(economics(0.0), PayMixParts(0.0, 12.0, 0.0, 1, 1, 0.0, 0, 0))
        assertNull("a rate with no denominator is not a fact", zeroGross.tipShare)
        assertTrue(zeroGross.notItemizedNegative)
    }

    /** A sub-cent negative not-itemized is rounding, not an anomaly (kept from the pre-#1135 suite). */
    @Test
    fun `a sub-cent negative not-itemized is rounding not an anomaly`() {
        val mix = PayMix.of(economics(100.0), PayMixParts(60.0, 40.001, 0.0, 2, 2, 0.0, 0, 0))
        assertFalse(mix.notItemizedNegative)
        assertIdentity(mix)
    }
}

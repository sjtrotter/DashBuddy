package cloud.trotter.dashbuddy.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #973 / brief §7.6 — the pay-mix residue rule (`bonuses & other = gross − base − tips − cash`), its
 * negative-remainder floor, and the coverage flags the Money tab's honesty copy branches on.
 *
 * Pure `:domain` math, no Compose and no DB: the interesting part is the *arithmetic contract* the
 * "what made up the gross" bar rests on, and it should be provable without either.
 */
class PayMixTest {

    private fun parts(
        basePay: Double = 0.0,
        tips: Double = 0.0,
        cashTips: Double = 0.0,
        deliveries: Int = 0,
        withBreakdown: Int = 0,
    ) = PayMixParts(
        basePay = basePay,
        tips = tips,
        cashTips = cashTips,
        deliveries = deliveries,
        deliveriesWithBreakdown = withBreakdown,
    )

    // ── The identity the whole card rests on ────────────────────────────────

    @Test
    fun `base plus tips plus bonuses equals gross`() {
        val mix = PayMix.of(
            gross = 100.0,
            parts = parts(basePay = 40.0, tips = 25.0, deliveries = 5, withBreakdown = 5),
        )

        assertEquals(35.0, mix.bonusesOther, 1e-9)
        assertEquals(mix.gross, mix.basePay + mix.tipsTotal + mix.bonusesOther, 1e-9)
    }

    @Test
    fun `the sum identity holds with cash tips in the mix`() {
        // Cash rides the tips segment's geometry but is carried separately so the legend can label it.
        val mix = PayMix.of(
            gross = 100.0,
            parts = parts(basePay = 40.0, tips = 25.0, cashTips = 10.0, deliveries = 5, withBreakdown = 5),
        )

        assertEquals(35.0, mix.tipsTotal, 1e-9)
        assertEquals(10.0, mix.cashTips, 1e-9)
        assertEquals(25.0, mix.bonusesOther, 1e-9)
        assertEquals(mix.gross, mix.basePay + mix.tipsTotal + mix.bonusesOther, 1e-9)
    }

    @Test
    fun `the sum identity holds for an all-bonus window`() {
        // Nothing itemized, so the residue IS the gross — arithmetically correct, which is exactly why
        // `hasBreakdown` exists: the UI must not render this as "100% bonuses".
        val mix = PayMix.of(gross = 60.0, parts = parts(deliveries = 3, withBreakdown = 0))

        assertEquals(60.0, mix.bonusesOther, 1e-9)
        assertEquals(mix.gross, mix.basePay + mix.tipsTotal + mix.bonusesOther, 1e-9)
        assertFalse(mix.hasBreakdown)
    }

    // ── The floor ──────────────────────────────────────────────────────────

    @Test
    fun `a negative remainder floors at zero and is flagged with its overflow`() {
        // The #701 over-attributed shape: captured per-delivery pay exceeds the reported total.
        val mix = PayMix.of(
            gross = 50.0,
            parts = parts(basePay = 40.0, tips = 18.0, deliveries = 4, withBreakdown = 4),
        )

        assertEquals(0.0, mix.bonusesOther, 1e-9)
        assertTrue(mix.partsExceedGross)
        assertEquals(8.0, mix.grossOverflow, 1e-9)
    }

    @Test
    fun `a sub-cent negative remainder is rounding, not an anomaly`() {
        val mix = PayMix.of(
            gross = 50.0,
            parts = parts(basePay = 30.0, tips = 20.001, deliveries = 2, withBreakdown = 2),
        )

        assertEquals(0.0, mix.bonusesOther, 1e-9)
        assertFalse("a tenth of a cent must not raise the review flag", mix.partsExceedGross)
        assertEquals(0.0, mix.grossOverflow, 1e-9)
    }

    @Test
    fun `an exactly-covered window leaves no bonus residue`() {
        val mix = PayMix.of(
            gross = 65.0,
            parts = parts(basePay = 40.0, tips = 25.0, deliveries = 5, withBreakdown = 5),
        )

        assertEquals(0.0, mix.bonusesOther, 1e-9)
        assertFalse(mix.partsExceedGross)
    }

    // ── Coverage ───────────────────────────────────────────────────────────

    @Test
    fun `coverage is complete only when every delivery itemized`() {
        val complete = PayMix.of(gross = 100.0, parts = parts(basePay = 60.0, deliveries = 4, withBreakdown = 4))
        val partial = PayMix.of(gross = 100.0, parts = parts(basePay = 60.0, deliveries = 4, withBreakdown = 2))

        assertTrue(complete.breakdownComplete)
        assertTrue(complete.hasBreakdown)
        assertFalse("a stacked-job window itemizes only its sole-drop rows", partial.breakdownComplete)
        assertTrue(partial.hasBreakdown)
    }

    @Test
    fun `an empty window has neither breakdown nor completeness`() {
        assertFalse(PayMix.EMPTY.hasBreakdown)
        assertFalse("zero of zero is not 'complete' — there is nothing to be complete about", PayMix.EMPTY.breakdownComplete)
        assertNull(PayMix.EMPTY.tipShare)
    }

    // ── The insight share ──────────────────────────────────────────────────

    @Test
    fun `tip share is tips plus cash over gross`() {
        val mix = PayMix.of(
            gross = 200.0,
            parts = parts(basePay = 80.0, tips = 100.0, cashTips = 14.0, deliveries = 8, withBreakdown = 8),
        )

        assertEquals(0.57, mix.tipShare!!, 1e-9)
    }

    @Test
    fun `tip share is null without a positive gross`() {
        val mix = PayMix.of(gross = 0.0, parts = parts(tips = 5.0, deliveries = 1, withBreakdown = 1))

        assertNull("a share with no denominator is not a fact", mix.tipShare)
    }
}

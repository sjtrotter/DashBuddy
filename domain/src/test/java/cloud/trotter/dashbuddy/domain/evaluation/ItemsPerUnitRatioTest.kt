package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #823 Phase 1 — the learned items:units ratio fold + seeds. Mirrors [ShopRate]'s discipline: a pure
 * incremental mean, out-of-band samples ignored, per-sample clamp to a sane band, per-platform seed.
 */
class ItemsPerUnitRatioTest {

    @Test
    fun `seed is the corpus midpoint and defaults per platform`() {
        assertEquals(0.78, ItemsPerUnitRatioSeeds.DOORDASH_CORPUS_SEED, 0.0)
        assertEquals(0.78, ItemsPerUnitRatioSeeds.seedFor(Platform.DoorDash), 0.0)
        // A platform with no bespoke entry resolves to the generic default (never a foreign seed).
        assertEquals(ItemsPerUnitRatioSeeds.DEFAULT, ItemsPerUnitRatioSeeds.seedFor(Platform.Uber), 0.0)
    }

    @Test
    fun `first in-band sample seeds the mean at its own clamped ratio`() {
        // 63 items shopped for a 79-unit offer → 0.797, in band.
        val (avg, n) = ItemsPerUnitRatio.fold(prevAvg = null, prevN = 0, units = 79, items = 63)
        assertEquals(63.0 / 79.0, avg!!, 1e-9)
        assertEquals(1, n)
    }

    @Test
    fun `the running mean is incremental and fold-order independent`() {
        // Two samples: 0.797 (63/79) then 0.71 (22/31); mean = (0.797 + 0.710)/2.
        var pair = ItemsPerUnitRatio.fold(null, 0, units = 79, items = 63)
        pair = ItemsPerUnitRatio.fold(pair.first, pair.second, units = 31, items = 22)
        val expected = (63.0 / 79.0 + 22.0 / 31.0) / 2.0
        assertEquals(expected, pair.first!!, 1e-9)
        assertEquals(2, pair.second)
    }

    @Test
    fun `the fielded H-E-B ratio is in-band and folds at its exact value`() {
        // The dev's H-E-B: 30 items shopped for a 64-unit offer → 0.46875, ABOVE the 0.3 floor
        // (F1: the floor must not clamp the sole real measurement, else it becomes the estimator).
        val (avg, n) = ItemsPerUnitRatio.fold(null, 0, units = 64, items = 30)
        assertEquals(0.46875, avg!!, 1e-9)
        assertEquals(1, n)
    }

    @Test
    fun `a degenerate below-floor sample is clamped to the floor before folding`() {
        // A stale mid-shop snapshot: 10 items for a 64-unit offer → 0.156, below MIN_RATIO → 0.3.
        val (avg, n) = ItemsPerUnitRatio.fold(null, 0, units = 64, items = 10)
        assertEquals(ItemsPerUnitRatio.MIN_RATIO, avg!!, 1e-9)
        assertEquals(1, n)
    }

    @Test
    fun `an above-band sample is clamped to the ceiling`() {
        // 40 items for 30 units → 1.33 → clamped to MAX_RATIO.
        val (avg, _) = ItemsPerUnitRatio.fold(null, 0, units = 30, items = 40)
        assertEquals(ItemsPerUnitRatio.MAX_RATIO, avg!!, 1e-9)
    }

    @Test
    fun `out-of-band samples (no units, too few items) fold nothing`() {
        assertNull(ItemsPerUnitRatio.fold(null, 0, units = 0, items = 30).first)
        assertEquals(0, ItemsPerUnitRatio.fold(null, 0, units = 0, items = 30).second)
        // Below MIN_ITEMS (a 1–2 item shop is noise): unchanged.
        val prior = 0.8 to 3
        assertEquals(prior, ItemsPerUnitRatio.fold(0.8, 3, units = 50, items = 2))
    }
}

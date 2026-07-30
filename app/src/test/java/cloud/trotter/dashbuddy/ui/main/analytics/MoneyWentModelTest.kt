package cloud.trotter.dashbuddy.ui.main.analytics

import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.PeriodTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #973 / brief §4.1 — "Where your money went", and specifically the **carried-over #659 coverage
 * guard**: the frozen fuel/non-fuel split renders only when it reconciles against the window's derived
 * operating cost, otherwise the card degrades to the combined car-cost form.
 *
 * These cases are the retired `WaterfallModelTest`'s, re-expressed against the new shape — 4-step
 * became "has a split" and 3-step became "no split" — so the guard's arithmetic is provably unchanged
 * by the redesign rather than merely believed to be. Compose-free, per the [MoneyWentModel] precedent.
 */
class MoneyWentModelTest {

    private fun economics(
        gross: Double,
        net: Double,
        fuel: Double? = null,
        nonFuel: Double? = null,
        miles: Double = 20.0,
    ): PeriodEconomics = PeriodEconomics(
        totals = PeriodTotals(earnings = net, miles = miles, deliveries = 3, jobs = 2, onlineDuration = 3_600_000L),
        grossEarnings = gross,
        netProfit = net,
        unattributedPay = 0.0,
        netPerHour = null,
        netPerMile = null,
        fuelCost = fuel,
        nonFuelCost = nonFuel,
    )

    // ── The three plain-words figures ──────────────────────────────────────

    @Test
    fun `the three lines reconcile — came in minus car costs equals what stayed`() {
        val split = MoneyWentModel.from(economics(gross = 20.0, net = 12.0))

        assertEquals(20.0, split.cameIn, 1e-9)
        assertEquals(8.0, split.carCosts, 1e-9)
        assertEquals(12.0, split.stayedWithYou, 1e-9)
        assertEquals(split.stayedWithYou, split.cameIn - split.carCosts, 1e-9)
    }

    // ── Coverage guard: the 3-segment (kept / gas / wear) form ─────────────

    @Test
    fun `full frozen coverage renders the gas and wear split`() {
        val split = MoneyWentModel.from(economics(gross = 20.0, net = 10.0, fuel = 6.0, nonFuel = 4.0))

        assertTrue(split.hasSplit)
        val gas = requireNotNull(split.gas)
        val wear = requireNotNull(split.wear)
        assertEquals(6.0, gas, 1e-9)
        assertEquals(4.0, wear, 1e-9)
        // The split must actually account for the car-cost figure the card states above it.
        assertEquals(split.carCosts, gas + wear, 1e-9)
    }

    @Test
    fun `coverage within tolerance still renders the split`() {
        // cost = 10.0, split sums to 9.7 -> inside the $0.50 absolute tolerance floor.
        assertTrue(MoneyWentModel.from(economics(gross = 20.0, net = 10.0, fuel = 5.8, nonFuel = 3.9)).hasSplit)
    }

    @Test
    fun `a zero window with a full zero split still reconciles`() {
        // Degenerate but honest: 0 == 0 within tolerance, so the split is still "trustworthy".
        val split = MoneyWentModel.from(economics(gross = 0.0, net = 0.0, fuel = 0.0, nonFuel = 0.0))

        assertTrue(split.hasSplit)
        assertEquals(0.0, split.carCosts, 1e-9)
    }

    // ── Coverage guard: the 2-segment (kept / car costs) degrade ───────────

    @Test
    fun `null fuel degrades to the combined car-cost form`() {
        val split = MoneyWentModel.from(economics(gross = 20.0, net = 10.0, fuel = null, nonFuel = 4.0))

        assertFalse(split.hasSplit)
        assertNull(split.gas)
        assertNull(split.wear)
        assertEquals(10.0, split.carCosts, 1e-9)
    }

    @Test
    fun `null non-fuel degrades to the combined car-cost form`() {
        assertFalse(MoneyWentModel.from(economics(gross = 20.0, net = 10.0, fuel = 6.0, nonFuel = null)).hasSplit)
    }

    @Test
    fun `partial coverage below tolerance degrades to the combined car-cost form`() {
        // cost = 10.0, split sums to only 5.0 -> a window mixing frozen and fallback rows (#659 F2).
        val split = MoneyWentModel.from(economics(gross = 20.0, net = 10.0, fuel = 3.0, nonFuel = 2.0))

        assertFalse("a partially-covered split must never be rendered as if it covered the window", split.hasSplit)
        assertEquals(10.0, split.carCosts, 1e-9)
    }

    @Test
    fun `an empty window degrades and states no rate`() {
        val split = MoneyWentModel.from(PeriodEconomics.EMPTY)

        assertFalse(split.hasSplit)
        assertEquals(0.0, split.carCosts, 1e-9)
        assertNull("no miles logged means no per-mile rate to claim", split.ratePerMile)
    }

    @Test
    fun `a negative derived cost degrades and keeps the signed figure`() {
        // The #662-F1 shape: gross < net -> the derived cost is negative. The 1% tolerance term goes
        // negative but the $0.50 floor rescues max(), so any real (non-negative) split fails the guard
        // and the honest signed cost renders — never a fabricated split.
        val split = MoneyWentModel.from(economics(gross = 20.0, net = 25.0, fuel = 1.0, nonFuel = 2.0))

        assertFalse(split.hasSplit)
        assertEquals(-5.0, split.carCosts, 1e-9)
    }

    // ── The disclosure's arithmetic ────────────────────────────────────────

    @Test
    fun `the disclosure rate is car costs over miles`() {
        val split = MoneyWentModel.from(economics(gross = 100.0, net = 44.0, miles = 100.0))

        assertEquals(0.56, split.ratePerMile!!, 1e-9)
    }

    @Test
    fun `the expanded per-mile split is only offered when the split covers the window`() {
        val covered = MoneyWentModel.from(economics(gross = 20.0, net = 10.0, fuel = 6.0, nonFuel = 4.0, miles = 20.0))
        val degraded = MoneyWentModel.from(economics(gross = 20.0, net = 10.0, fuel = 3.0, nonFuel = 2.0, miles = 20.0))

        assertEquals(0.30, covered.gasPerMile!!, 1e-9)
        assertEquals(0.20, covered.wearPerMile!!, 1e-9)
        assertNull(degraded.gasPerMile)
        assertNull(degraded.wearPerMile)
    }

    @Test
    fun `no miles means no per-mile split even under full coverage`() {
        val split = MoneyWentModel.from(economics(gross = 20.0, net = 10.0, fuel = 6.0, nonFuel = 4.0, miles = 0.0))

        assertTrue(split.hasSplit)
        assertNull(split.gasPerMile)
        assertNull(split.wearPerMile)
        assertNull(split.ratePerMile)
    }
}

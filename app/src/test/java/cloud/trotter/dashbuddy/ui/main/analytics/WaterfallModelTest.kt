package cloud.trotter.dashbuddy.ui.main.analytics

import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.PeriodTotals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #659 — the true-net waterfall's step-count decision (Gross → −Fuel → −Non-fuel → Net vs the
 * 3-step Gross → −Operating cost → Net fallback). Pure logic, no Compose: [WaterfallModel.from]
 * is exercised directly against [PeriodEconomics] shapes.
 */
class WaterfallModelTest {

    private fun economics(
        gross: Double,
        net: Double,
        fuel: Double? = null,
        nonFuel: Double? = null,
    ): PeriodEconomics = PeriodEconomics(
        totals = PeriodTotals(earnings = net, miles = 20.0, deliveries = 3, jobs = 2, onlineDuration = 3_600_000L),
        grossEarnings = gross,
        netProfit = net,
        unattributedPay = 0.0,
        netPerHour = null,
        netPerMile = null,
        fuelCost = fuel,
        nonFuelCost = nonFuel,
    )

    @Test
    fun `full frozen coverage renders 4 steps that sum to net`() {
        val steps = WaterfallModel.from(economics(gross = 20.0, net = 10.0, fuel = 6.0, nonFuel = 4.0))

        assertEquals(4, steps.size)
        assertEquals(
            listOf("Gross", "Fuel", "Non-fuel (wear, depreciation, fixed)", "Net"),
            steps.map { it.label },
        )
        assertEquals(
            listOf(WaterfallModel.Role.GROSS, WaterfallModel.Role.COST, WaterfallModel.Role.COST, WaterfallModel.Role.NET),
            steps.map { it.role },
        )
        // Gross - Fuel - NonFuel == Net (the waterfall must actually reconcile visually).
        val gross = steps[0].amount
        val fuel = steps[1].amount
        val nonFuel = steps[2].amount
        val net = steps[3].amount
        assertEquals(net, gross - fuel - nonFuel, 1e-9)
    }

    @Test
    fun `coverage within tolerance still renders 4 steps`() {
        // cost = 10.0, split sums to 9.7 -> within the 0.50 absolute tolerance floor.
        val steps = WaterfallModel.from(economics(gross = 20.0, net = 10.0, fuel = 5.8, nonFuel = 3.9))
        assertEquals(4, steps.size)
    }

    @Test
    fun `null fuel falls back to 3 steps`() {
        val steps = WaterfallModel.from(economics(gross = 20.0, net = 10.0, fuel = null, nonFuel = 4.0))

        assertEquals(3, steps.size)
        assertEquals(listOf("Gross", "Operating cost", "Net"), steps.map { it.label })
        assertEquals(10.0, steps[1].amount, 1e-9) // gross - net
    }

    @Test
    fun `null nonFuel falls back to 3 steps`() {
        val steps = WaterfallModel.from(economics(gross = 20.0, net = 10.0, fuel = 6.0, nonFuel = null))
        assertEquals(3, steps.size)
    }

    @Test
    fun `partial coverage below tolerance falls back to 3 steps`() {
        // cost = 10.0, split sums to only 5.0 -> a mixed frozen+fallback period (#659 F2 guard).
        val steps = WaterfallModel.from(economics(gross = 20.0, net = 10.0, fuel = 3.0, nonFuel = 2.0))

        assertEquals(3, steps.size)
        assertEquals(listOf("Gross", "Operating cost", "Net"), steps.map { it.label })
        assertEquals(10.0, steps[1].amount, 1e-9)
    }

    @Test
    fun `zero period falls back to 3 steps of zeros`() {
        val steps = WaterfallModel.from(PeriodEconomics.EMPTY)

        assertEquals(3, steps.size)
        assertEquals(listOf("Gross", "Operating cost", "Net"), steps.map { it.label })
        steps.forEach { assertEquals(0.0, it.amount, 1e-9) }
    }

    @Test
    fun `zero period with full zero split still reconciles to 4 steps`() {
        // Degenerate but honest: 0 == 0 within tolerance, so the split is still "trustworthy".
        val steps = WaterfallModel.from(economics(gross = 0.0, net = 0.0, fuel = 0.0, nonFuel = 0.0))

        assertEquals(4, steps.size)
        steps.forEach { assertEquals(0.0, it.amount, 1e-9) }
    }

    @Test
    fun `negative derived cost (reported under delivered) falls back to 3 steps with the signed cost`() {
        // The #662-F1 shape: gross < net → derived cost is negative. The 1% tolerance term goes
        // negative but the $0.50 floor rescues max(); any real (non-negative) split then fails the
        // guard, so the fallback renders — with the honest signed cost, never a fabricated split.
        val steps = WaterfallModel.from(economics(gross = 20.0, net = 25.0, fuel = 1.0, nonFuel = 2.0))

        assertEquals(3, steps.size)
        assertEquals(listOf("Gross", "Operating cost", "Net"), steps.map { it.label })
        assertEquals(-5.0, steps[1].amount, 1e-9)
    }
}

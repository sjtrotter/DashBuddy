package cloud.trotter.dashbuddy.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * #981 — the saved-plan artifact and its codec: a plan the driver committed to must survive a process
 * restart byte-for-byte, and a preference that has been corrupted must read as *no plan* rather than
 * crashing the Home screen or the Sunday worker open (the `LegStateCodec` fail-closed discipline).
 */
class WeeklyPlanStorageTest {

    private val weekStart: LocalDate = LocalDate.of(2026, 7, 27) // a Monday

    private fun plan(target: PlanTarget = PlanTarget.Hours(12)) = SavedWeeklyPlan(
        weekStart = weekStart,
        savedAtMillis = 1_753_000_000_000L,
        target = target,
        windows = listOf(
            SavedPlanWindow(4, 17, 21, 26.40, 6),
            SavedPlanWindow(5, 11, 13, 19.05, 4),
            SavedPlanWindow(1, 18, 20, null, 0),
        ),
        projectedKept = 143.70,
        randomKept = 110.0,
    )

    @Test
    fun `a saved plan round-trips through the codec unchanged`() {
        val saved = listOf(plan())
        assertEquals(saved, WeeklyPlanCodec.decode(WeeklyPlanCodec.encode(saved)))
    }

    @Test
    fun `both target kinds round-trip`() {
        val saved = listOf(plan(PlanTarget.Hours(20)), plan(PlanTarget.Dollars(325.50)).copy(weekStart = weekStart.minusWeeks(1)))
        val decoded = WeeklyPlanCodec.decode(WeeklyPlanCodec.encode(saved))
        assertEquals(PlanTarget.Hours(20), decoded[0].target)
        assertEquals(PlanTarget.Dollars(325.50), decoded[1].target)
    }

    @Test
    fun `a null, blank or malformed blob decodes as no saved plan`() {
        assertTrue(WeeklyPlanCodec.decode(null).isEmpty())
        assertTrue(WeeklyPlanCodec.decode("").isEmpty())
        assertTrue(WeeklyPlanCodec.decode("   ").isEmpty())
        assertTrue(WeeklyPlanCodec.decode("{not json").isEmpty())
        assertTrue(WeeklyPlanCodec.decode("[]").isEmpty())
    }

    @Test
    fun `an unknown target kind drops that entry rather than guessing one`() {
        val raw = WeeklyPlanCodec.encode(listOf(plan())).replace("\"hours\"", "\"furlongs\"")
        assertTrue(WeeklyPlanCodec.decode(raw).isEmpty())
    }

    @Test
    fun `a structurally impossible window is dropped, and the rest of the plan survives`() {
        val raw = WeeklyPlanCodec.encode(listOf(plan())).replace("\"dayIndex\":4", "\"dayIndex\":9")
        val decoded = WeeklyPlanCodec.decode(raw).single()
        assertEquals(2, decoded.windows.size)
        assertTrue(decoded.windows.none { it.dayIndex == 9 })
    }

    @Test
    fun `an inverted hour range is dropped`() {
        val raw = WeeklyPlanCodec.encode(
            listOf(plan().copy(windows = listOf(SavedPlanWindow(4, 17, 21, 20.0, 5)))),
        ).replace("\"endHourExclusive\":21", "\"endHourExclusive\":17")
        assertTrue(WeeklyPlanCodec.decode(raw).single().windows.isEmpty())
    }

    @Test
    fun `derived totals come from the frozen windows`() {
        val saved = plan()
        assertEquals(8, saved.totalHours) // 4 + 2 + 2, including the unpriced one
        assertEquals(33.70, saved.planWorth!!, 1e-9)
    }

    @Test
    fun `freezing a plan keeps the numbers the driver was shown`() {
        val samples = HourOfWeekSamples(
            (0 until 4).map { occ ->
                val coverage = MutableList(EarningsHeatmap.HOURS) { 0.0 }
                val net = MutableList(EarningsHeatmap.HOURS) { 0.0 }
                for (h in 17..18) {
                    coverage[h] = 1.0
                    net[h] = 30.0
                }
                SampledDay(LocalDate.of(2026, 1, 9).plusWeeks(occ.toLong()), 4, coverage, net)
            },
        )
        val live = WeeklyPlanner.plan(samples, PlanTarget.Hours(2))
        val frozen = SavedWeeklyPlan.from(live, weekStart, savedAtMillis = 42L)

        assertEquals(weekStart, frozen.weekStart)
        assertEquals(live.projectedKept, frozen.projectedKept, 1e-9)
        assertEquals(live.randomKept!!, frozen.randomKept!!, 1e-9)
        assertEquals(live.totalHours, frozen.totalHours)
        assertEquals(live.windows.map { it.startHour }, frozen.windows.map { it.startHour })
        // …and survives storage exactly as frozen.
        assertEquals(listOf(frozen), WeeklyPlanCodec.decode(WeeklyPlanCodec.encode(listOf(frozen))))
    }
}

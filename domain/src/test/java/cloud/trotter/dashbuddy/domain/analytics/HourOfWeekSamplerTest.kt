package cloud.trotter.dashbuddy.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * #981 — the per-occurrence decomposition the Weekly Plan's medians are taken over (brief §7.7).
 *
 * The properties that matter: it inherits [EarningsHeatmapCalculator]'s apportionment and union rules
 * exactly (so the plan and the Patterns grid can never disagree), it refuses to sample below the
 * coverage floor, and its sample COUNT is the number of separate days — the figure the evidence line
 * quotes.
 */
class HourOfWeekSamplerTest {

    private val zone: ZoneId = ZoneId.of("America/Chicago")

    private fun millis(date: String, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(LocalDate.parse(date), java.time.LocalTime.of(hour, minute))
            .atZone(zone).toInstant().toEpochMilli()

    /** 2026-07-03 is a Friday, 07-10 / 07-17 / 07-24 the Fridays after it. */
    private val fridays = listOf("2026-07-03", "2026-07-10", "2026-07-17", "2026-07-24")

    private fun fullHourSpans(dates: List<String>, startHour: Int, endHour: Int) =
        dates.map { SessionSpan(millis(it, startHour), millis(it, endHour)) }

    @Test
    fun `one occurrence per date, rate is that date's net over its coverage`() {
        val samples = HourOfWeekSampler.compute(
            spans = fullHourSpans(fridays.take(3), 17, 19),
            deliveries = listOf(
                DeliveryNet(millis("2026-07-03", 17, 30), 20.0),
                DeliveryNet(millis("2026-07-10", 17, 30), 30.0),
                DeliveryNet(millis("2026-07-17", 17, 30), 40.0),
            ),
            zone = zone,
        )

        // Friday = dayIndex 4. Each date: 1h of coverage in the 5pm bucket, one drop landing in it.
        val rates = samples.occurrenceRates(dayIndex = 4, startHour = 17, endHourExclusive = 18)
        assertEquals(listOf(20.0, 30.0, 40.0), rates)
        assertEquals(30.0, HourOfWeekSamples.median(rates)!!, 1e-9)
    }

    @Test
    fun `median is robust to an outlier the mean would follow`() {
        val samples = HourOfWeekSampler.compute(
            spans = fullHourSpans(fridays, 17, 18),
            deliveries = listOf(
                DeliveryNet(millis("2026-07-03", 17, 10), 20.0),
                DeliveryNet(millis("2026-07-10", 17, 10), 22.0),
                DeliveryNet(millis("2026-07-17", 17, 10), 24.0),
                DeliveryNet(millis("2026-07-24", 17, 10), 200.0),
            ),
            zone = zone,
        )
        val rates = samples.occurrenceRates(4, 17, 18)
        // Mean would be $66.50/hr — one freak night. The median stays with the typical Friday.
        assertEquals(23.0, HourOfWeekSamples.median(rates)!!, 1e-9)
    }

    @Test
    fun `an occurrence below the coverage floor is not a sample at all`() {
        val samples = HourOfWeekSampler.compute(
            // 12 minutes of presence, one $10 drop → a 50-an-hour artifact if it were sampled.
            spans = listOf(SessionSpan(millis("2026-07-03", 17), millis("2026-07-03", 17, 12))),
            deliveries = listOf(DeliveryNet(millis("2026-07-03", 17, 5), 10.0)),
            zone = zone,
        )
        assertTrue(samples.occurrenceRates(4, 17, 18).isEmpty())
        assertNull(samples.overallMedianRate)
    }

    @Test
    fun `the floor scales with the window length`() {
        // 5-9pm asked about; the driver was present 5-6:30pm only (1.5h of a 4h window = under half).
        val samples = HourOfWeekSampler.compute(
            spans = listOf(SessionSpan(millis("2026-07-03", 17), millis("2026-07-03", 18, 30))),
            deliveries = listOf(DeliveryNet(millis("2026-07-03", 17, 30), 30.0)),
            zone = zone,
        )
        assertTrue(samples.occurrenceRates(4, 17, 21).isEmpty())
        // The 1-hour question it CAN answer is still answered.
        assertEquals(1, samples.occurrenceRates(4, 17, 18).size)
    }

    @Test
    fun `concurrent overlapping spans are unioned, never double-counted`() {
        val samples = HourOfWeekSampler.compute(
            spans = listOf(
                SessionSpan(millis("2026-07-03", 17), millis("2026-07-03", 19)),
                SessionSpan(millis("2026-07-03", 18), millis("2026-07-03", 20)),
            ),
            deliveries = emptyList(),
            zone = zone,
        )
        val day = samples.days.single()
        // Three wall-clock hours of presence, not four.
        assertEquals(3.0, day.coverageHours.sum(), 1e-9)
    }

    @Test
    fun `an inverted or absurd span contributes nothing`() {
        val samples = HourOfWeekSampler.compute(
            spans = listOf(
                SessionSpan(millis("2026-07-03", 19), millis("2026-07-03", 17)),
                SessionSpan(0L, millis("2026-07-03", 19)),
            ),
            deliveries = emptyList(),
            zone = zone,
        )
        assertTrue(samples.days.all { it.coverageHours.sum() == 0.0 })
    }

    @Test
    fun `daysOnRecord counts distinct dates the driver was online at all`() {
        val samples = HourOfWeekSampler.compute(
            spans = fullHourSpans(fridays.take(3), 11, 12) + fullHourSpans(listOf("2026-07-04"), 11, 12),
            deliveries = emptyList(),
            zone = zone,
        )
        assertEquals(3, samples.daysOnRecord(4)) // Fridays
        assertEquals(1, samples.daysOnRecord(5)) // one Saturday
        assertEquals(0, samples.daysOnRecord(0)) // no Mondays
    }

    @Test
    fun `coverageOn and netOn read one real date, for grading`() {
        val samples = HourOfWeekSampler.compute(
            spans = listOf(SessionSpan(millis("2026-07-03", 17), millis("2026-07-03", 20))),
            deliveries = listOf(
                DeliveryNet(millis("2026-07-03", 17, 30), 25.0),
                DeliveryNet(millis("2026-07-03", 21, 30), 99.0), // outside the graded window
            ),
            zone = zone,
        )
        val date = LocalDate.parse("2026-07-03")
        assertEquals(2.0, samples.coverageOn(date, 17, 19), 1e-9)
        assertEquals(25.0, samples.netOn(date, 17, 19), 1e-9)
        assertEquals(0.0, samples.netOn(date.plusDays(1), 17, 19), 1e-9)
    }

    @Test
    fun `net lands in the hour of completion, matching the heatmap's own rule`() {
        val samples = HourOfWeekSampler.compute(
            spans = listOf(SessionSpan(millis("2026-07-03", 17), millis("2026-07-03", 19))),
            deliveries = listOf(DeliveryNet(millis("2026-07-03", 18, 5), 12.0)),
            zone = zone,
        )
        val day = samples.days.single()
        assertEquals(0.0, day.netDollars[17], 1e-9)
        assertEquals(12.0, day.netDollars[18], 1e-9)
    }

    @Test
    fun `the sampler and the heatmap agree on total coverage and total net`() {
        val spans = fullHourSpans(fridays, 16, 21) + fullHourSpans(listOf("2026-07-04", "2026-07-11"), 10, 14)
        val deliveries = listOf(
            DeliveryNet(millis("2026-07-03", 17, 10), 18.0),
            DeliveryNet(millis("2026-07-10", 18, 10), 22.0),
            DeliveryNet(millis("2026-07-04", 11, 10), 9.0),
        )
        val samples = HourOfWeekSampler.compute(spans, deliveries, zone)
        val heatmap = EarningsHeatmapCalculator.compute(spans, deliveries, zone)

        assertEquals(
            heatmap.cells.sumOf { it.coverageHours },
            samples.days.sumOf { it.coverageHours.sum() },
            1e-9,
        )
        assertEquals(
            heatmap.cells.sumOf { it.netDollars },
            samples.days.sumOf { it.netDollars.sum() },
            1e-9,
        )
    }

    @Test
    fun `allHourRates spans every worked hour, and the overall median is their middle`() {
        val samples = HourOfWeekSampler.compute(
            spans = listOf(SessionSpan(millis("2026-07-03", 17), millis("2026-07-03", 20))),
            deliveries = listOf(
                DeliveryNet(millis("2026-07-03", 17, 10), 10.0),
                DeliveryNet(millis("2026-07-03", 18, 10), 20.0),
                DeliveryNet(millis("2026-07-03", 19, 10), 30.0),
            ),
            zone = zone,
        )
        assertEquals(listOf(10.0, 20.0, 30.0), samples.allHourRates())
        assertEquals(20.0, samples.overallMedianRate!!, 1e-9)
    }

    @Test
    fun `median of an even sample is the mean of the two middles, and empty is null`() {
        assertEquals(15.0, HourOfWeekSamples.median(listOf(10.0, 20.0))!!, 1e-9)
        assertNull(HourOfWeekSamples.median(emptyList()))
    }

    @Test
    fun `empty record yields no days and no baseline`() {
        val samples = HourOfWeekSampler.compute(emptyList(), emptyList(), zone)
        assertTrue(samples.days.isEmpty())
        assertNull(samples.overallMedianRate)
        assertTrue(!samples.hasData)
    }
}

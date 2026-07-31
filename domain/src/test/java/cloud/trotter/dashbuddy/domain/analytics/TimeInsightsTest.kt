package cloud.trotter.dashbuddy.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #983 / brief §6 — the two composed Time-tab models: "your typical online hour"
 * ([HourComposition]) and the `docs/design/running-hourly-rate.md` §2a rate pair ([NetPerHourPair]).
 *
 * What matters here is the arithmetic that carries a claim:
 *  - the three hour segments partition ONE hour, with the residual absorbing everything unmeasured;
 *  - coverage is reported rather than smoothed (an untimed stop contributes no dwell, and the model
 *    says how many stops that was);
 *  - the doc's two denominators are what they say they are — engaged time excludes the dry gaps,
 *    online time includes them — so `whileWorking ≥ wholeShift` always holds;
 *  - a missing denominator yields null, never a `$0.00/hr` that would read as a measurement (#936).
 */
class TimeInsightsTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    private fun time(
        onlineMillis: Long = 4 * hour,
        deliveryMinutes: Double? = 120.0,
        deliveries: Int = 6,
        dropoffDwellMillis: Long = 12 * minute,
        dropoffsTimed: Int = 6,
        pickups: Int = 6,
        pickupDwellMillis: Long = 30 * minute,
        pickupsTimed: Int = 6,
    ) = TimeEconomics(
        sessions = 1,
        onlineMillis = onlineMillis,
        deliveryMinutes = deliveryMinutes,
        miles = 40.0,
        deliveryMiles = 30.0,
        deliveriesWithDeadline = 0,
        onTimeDeliveries = 0,
        avgDeadlineMarginMillis = null,
        deliveries = deliveries,
        dropoffDwellMillis = dropoffDwellMillis,
        dropoffsTimed = dropoffsTimed,
        pickups = pickups,
        pickupDwellMillis = pickupDwellMillis,
        pickupsTimed = pickupsTimed,
    )

    private fun gaps(count: Int = 4, totalMillis: Long = 24 * minute, withoutGap: Int = 2) =
        GapStats(
            count = count,
            totalMillis = totalMillis,
            medianMillis = if (count == 0) null else totalMillis / count,
            p90Millis = if (count == 0) null else totalMillis / count,
            longestMillis = if (count == 0) null else totalMillis / count,
            longGapCount = 0,
            completionsWithoutGap = withoutGap,
        )

    // ── HourComposition ─────────────────────────────────────────────────

    @Test
    fun `at stops is pickup dwell plus door dwell, waiting is the measured gaps`() {
        val composition = HourComposition.of(time(), gaps())

        assertEquals(42 * minute, composition.atStopsMillis)   // 30 store + 12 door
        assertEquals(24 * minute, composition.waitingMillis)
        // Rest = 4h online − 42m − 24m
        assertEquals(4 * hour - 42 * minute - 24 * minute, composition.restMillis)
        assertTrue(composition.hasData)
    }

    @Test
    fun `the three per-hour slices partition one hour`() {
        val composition = HourComposition.of(time(), gaps())
        val sum = composition.atStopsMillisPerHour + composition.waitingMillisPerHour +
            composition.restMillisPerHour

        // Rounding to whole millis can move the sum by at most 1 ms per segment.
        assertTrue("sum was $sum", sum in (hour - 3)..(hour + 3))
        assertEquals(1.0, composition.atStopsShare + composition.waitingShare + composition.restShare, 1e-9)
    }

    @Test
    fun `an untimed stop contributes no dwell and is stated as coverage`() {
        val composition = HourComposition.of(
            time(dropoffsTimed = 2, dropoffDwellMillis = 4 * minute, pickupsTimed = 3, pickupDwellMillis = 15 * minute),
            gaps(),
        )

        assertEquals(19 * minute, composition.atStopsMillis)  // only the timed stops
        assertEquals(5, composition.stopsTimed)
        assertEquals(12, composition.stops)
        assertFalse(composition.allStopsTimed)
        assertFalse(composition.noStopsTimed)
    }

    @Test
    fun `no stop timed at all leaves at-stops structurally zero and flags it`() {
        val composition = HourComposition.of(
            time(dropoffsTimed = 0, dropoffDwellMillis = 0, pickupsTimed = 0, pickupDwellMillis = 0),
            gaps(),
        )

        assertEquals(0L, composition.atStopsMillis)
        assertTrue(composition.noStopsTimed)
        // Everything unmeasured lands in the residual — never smoothed into the at-stops segment.
        assertEquals(4 * hour - 24 * minute, composition.restMillis)
    }

    @Test
    fun `measured segments exceeding the online span still partition one hour`() {
        // Incoherent data: 3h of dwell + 1h of gaps inside a 2h online span.
        val composition = HourComposition.of(
            time(onlineMillis = 2 * hour, pickupDwellMillis = 3 * hour, dropoffDwellMillis = 0),
            gaps(count = 1, totalMillis = hour),
        )

        assertEquals(0L, composition.restMillis)   // never negative
        assertEquals(1.0, composition.atStopsShare + composition.waitingShare + composition.restShare, 1e-9)
    }

    @Test
    fun `an empty window has no composition to render`() {
        val composition = HourComposition.of(TimeEconomics.EMPTY, GapStats.EMPTY)

        assertFalse(composition.hasData)
        assertEquals(0.0, composition.atStopsShare, 1e-9)
        assertEquals(0L, composition.restMillisPerHour)
    }

    // ── NetPerHourPair (the doc's active / scheduled denominators) ───────

    @Test
    fun `while working divides by engaged time, whole shift by every online minute`() {
        // 4h online, 2h of delivery partition deltas, 24m of that was dry waiting.
        val rates = NetPerHourPair.of(netProfit = 96.0, time = time(), gaps = gaps())

        assertEquals(2 * hour - 24 * minute, rates.workingMillis)
        assertEquals(4 * hour, rates.onlineMillis)
        assertEquals(96.0 / 1.6, rates.whileWorking!!, 1e-9)   // 96 min engaged = 1.6h
        assertEquals(24.0, rates.wholeShift!!, 1e-9)           // 96 / 4h
        assertTrue(rates.hasPair)
    }

    /** The doc's whole point: the shift rate can never beat the working rate, because it is the same
     *  money over a wider denominator. */
    @Test
    fun `while working is never below whole shift`() {
        val rates = NetPerHourPair.of(120.0, time(), gaps())
        assertTrue(rates.whileWorking!! >= rates.wholeShift!!)
    }

    @Test
    fun `with no gaps measured the two rates differ only by the unattributed tail`() {
        val rates = NetPerHourPair.of(80.0, time(), GapStats.EMPTY)

        assertEquals(2 * hour, rates.workingMillis)   // nothing subtracted
        assertEquals(0, rates.gapsSubtracted)
        assertEquals(40.0, rates.whileWorking!!, 1e-9)
        assertEquals(20.0, rates.wholeShift!!, 1e-9)
    }

    @Test
    fun `gaps exceeding the measured delivery time floor the denominator instead of inverting it`() {
        val rates = NetPerHourPair.of(50.0, time(deliveryMinutes = 10.0), gaps(count = 3, totalMillis = 2 * hour))

        assertEquals(0L, rates.workingMillis)
        assertNull(rates.whileWorking)      // no denominator ⇒ no rate (never a negative or infinite one)
        assertEquals(12.5, rates.wholeShift!!, 1e-9)
        assertFalse(rates.hasPair)
    }

    @Test
    fun `unmeasured delivery time yields a null working rate, not zero`() {
        val rates = NetPerHourPair.of(30.0, time(deliveryMinutes = null), gaps(count = 0, totalMillis = 0))

        assertTrue(rates.workingTimeUnmeasured)
        assertNull(rates.whileWorking)
        assertEquals(7.5, rates.wholeShift!!, 1e-9)
    }

    @Test
    fun `an empty window prices nothing`() {
        val rates = NetPerHourPair.of(0.0, TimeEconomics.EMPTY, GapStats.EMPTY)

        assertNull(rates.whileWorking)
        assertNull(rates.wholeShift)
        assertNull(rates.valueOf(30 * minute))
    }

    // ── the leak, in dollars ────────────────────────────────────────────

    @Test
    fun `the waiting leak is measured time priced at the while-working rate`() {
        val rates = NetPerHourPair.of(96.0, time(), gaps())
        val composition = HourComposition.of(time(), gaps())

        // 24 minutes of waiting at the while-working rate (96 / 1.6h = $60/hr) = $24.
        assertEquals(24.0, rates.valueOf(composition.waitingMillis)!!, 1e-9)
    }

    @Test
    fun `no waiting costs nothing, rather than reading as unmeasured`() {
        val rates = NetPerHourPair.of(96.0, time(), GapStats.EMPTY)
        assertEquals(0.0, rates.valueOf(0L)!!, 1e-9)
    }

    // ── the shared percentile convention ────────────────────────────────

    @Test
    fun `nearest rank never interpolates and never invents a value`() {
        val sorted = listOf(1L, 2L, 3L, 4L)

        assertEquals(2L, Percentiles.nearestRank(sorted, 0.50))   // not 2.5
        assertEquals(4L, Percentiles.nearestRank(sorted, 1.0))
        assertEquals(1L, Percentiles.nearestRank(sorted, 0.0))    // rank clamped to 1
        assertNull(Percentiles.nearestRank(emptyList(), 0.5))
    }
}

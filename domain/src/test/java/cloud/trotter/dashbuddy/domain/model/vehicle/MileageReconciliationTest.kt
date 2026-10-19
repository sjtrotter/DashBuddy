package cloud.trotter.dashbuddy.domain.model.vehicle

import org.junit.Assert.*
import org.junit.Test

class MileageReconciliationTest {
    private var sequence = 0L
    private fun reading(at: Long, miles: Int, gps: Double?, id: String = "civic") =
        VehicleOdometerReading(id, LastOdometerReading(miles, gps, at * 60_000), ++sequence)
    private val pair = listOf(reading(90, 52_329, 100.0), reading(210, 52_388, 157.3))
    private fun compare(rows: List<VehicleOdometerReading> = pair) = MileageReconciliation.latest(rows, "civic")

    @Test fun `the latest two readings make the span`() {
        val result = compare(pair.reversed())!!
        assertEquals(59, result.carMiles)
        assertEquals(57.3, result.trackedMiles, 0.00001)
    }
    @Test fun `equal timestamps order by sequence`() {
        val earlier = reading(210, 52_329, 100.0)
        val later = reading(210, 52_388, 157.3)
        val rows = listOf(earlier, later)
        assertEquals(59, compare(rows)!!.carMiles)
        assertEquals(57.3, compare(rows)!!.trackedMiles, 0.00001)
        assertEquals(compare(rows), compare(rows.reversed()))
        assertEquals(later.reading, VehicleOdometerReading.latestFor(rows, "civic"))
        assertNull(VehicleOdometerReading.latestFor(rows, "missing"))
    }
    @Test fun `the latest two win over an earlier pair`() {
        val rows = listOf(reading(90, 1_000, 100.0), reading(110, 1_010, 105.0),
            reading(150, 1_100, 175.0), reading(210, 1_159, 232.3))
        val result = compare(rows)!!
        assertEquals(59, result.carMiles)
        assertEquals(57.3, result.trackedMiles, 0.00001)
        assertEquals(150 * 60_000L, result.since)
    }
    @Test fun `weeks between readings still make the span`() {
        val earlier = reading(90, 1_000, 100.0)
        val later = reading(90 + 21 * 24 * 60L, 2_200, 1_000.0)
        assertEquals(ReconciledMileage(1_200, 900.0, earlier.reading.occurredAt), compare(listOf(earlier, later)))
    }
    @Test fun `since is the earlier reading's time`() {
        assertEquals(90 * 60_000L, compare()!!.since)
    }
    @Test fun `one reading is null`() {
        assertNull(compare(pair.take(1)))
        assertNull(compare(emptyList()))
    }
    @Test fun `a reading from another vehicle is ignored`() {
        assertNull(compare(listOf(pair[0], reading(220, 52_388, 157.3, "other"))))
        assertEquals(compare(), compare(pair + reading(220, 1, 0.0, "other")))
    }
    @Test fun `another vehicle read inside the span withholds the comparison`() {
        for (at in listOf(91L, 150L, 210L)) {
            assertNull(compare(pair + reading(at, 1_000, 130.0, "other")))
        }
    }
    @Test fun `another vehicle read outside the span does not`() {
        for (at in listOf(89L, 90L, 211L)) {
            assertEquals(compare(), compare(pair + reading(at, 1_000, 130.0, "other")))
        }
    }
    @Test fun `tracked uses the gps stamps not the typed miles`() {
        val result = compare(listOf(reading(90, 1, 100.0), reading(210, 1_000, 157.3)))!!
        assertEquals(999, result.carMiles)
        assertEquals(57.3, result.trackedMiles, 0.00001)
    }
    @Test fun `other miles is reported not flagged`() {
        val result = compare(listOf(reading(90, 1_000, 100.0), reading(210, 1_200, 158.0)))!!
        assertEquals(142.0, result.otherMiles, 0.0)
        assertEquals(ReconciledMileage(200, 58.0, 90 * 60_000L), result)
        assertEquals(-20.0, compare(listOf(reading(90, 1_000, 100.0), reading(210, 1_200, 320.0)))!!.otherMiles, 0.0)
    }
    @Test fun `missing or reset gps stamp is null`() {
        assertNull(compare(listOf(reading(90, 1_000, null), pair[1])))
        assertNull(compare(listOf(pair[0], reading(210, 52_388, null))))
        assertNull(compare(listOf(pair[0], reading(210, 52_388, 50.0))))
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(compare(listOf(reading(90, 52_329, invalid), pair[1])))
            assertNull(compare(listOf(pair[0], reading(210, 52_388, invalid))))
        }
    }
    @Test fun `a lower later reading is null`() {
        assertNull(compare(listOf(pair[0], reading(210, 52_270, 157.3))))
    }
    @Test fun `an older third reading is ignored`() {
        assertEquals(compare(), compare(listOf(pair[1], reading(80, 55_000, null), pair[0])))
    }
}

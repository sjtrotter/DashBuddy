package cloud.trotter.dashbuddy.domain.model.vehicle

import org.junit.Assert.*
import org.junit.Test

class MileageReconciliationTest {
    private fun reading(at: Long, miles: Int, gps: Double?, id: String = "civic") =
        VehicleOdometerReading(id, LastOdometerReading(miles, gps, at * 60_000))
    private val pair = listOf(reading(90, 52_329, 100.0), reading(210, 52_388, 157.3))
    private fun compare(rows: List<VehicleOdometerReading> = pair) = MileageReconciliation.between(rows, "civic", 100 * 60_000L, 200 * 60_000L)
    @Test fun `pair brackets the dash`() {
        val result = compare(pair + reading(80, 52_000, 0.0) + reading(220, 55_000, 500.0))!!
        assertEquals(59, result.carMiles)
        assertEquals(57.3, result.trackedMiles, 0.00001)
    }
    @Test fun `missing start reading is null`() { assertNull(compare(pair.drop(1))) }
    @Test fun `missing end reading is null`() { assertNull(compare(pair.take(1))) }
    @Test fun `reading from another vehicle is ignored`() {
        assertNull(compare(listOf(pair[0], reading(200, 52_388, 157.3, "other"))))
        assertEquals(compare(), compare(pair + reading(100, 1, 0.0, "other")))
    }
    @Test fun `tracked uses the gps stamps not the typed miles`() {
        val result = compare(listOf(reading(90, 1, 100.0), reading(210, 1_000, 157.3)))!!
        assertEquals(999, result.carMiles)
        assertEquals(57.3, result.trackedMiles, 0.00001)
    }
    @Test fun `other miles is reported not flagged`() {
        val result = compare(listOf(reading(90, 1_000, 100.0), reading(210, 1_200, 158.0)))!!
        assertEquals(142.0, result.otherMiles, 0.0)
        assertEquals(ReconciledMileage(200, 58.0), result)
        val check = OdometerReadingCheck.check(OdometerCheckInput(1_200, Vehicle("civic", "Civic", 0),
            LastOdometerReading(1_000, 100.0, 0), 158.0, 86_400_000))
        assertTrue(check is OdometerCheck.Ok)
    }
    @Test fun `missing or reset gps stamp is null`() {
        assertNull(compare(listOf(reading(90, 1_000, null), pair[1])))
        assertNull(compare(listOf(pair[0], reading(210, 52_388, null))))
        assertNull(compare(listOf(pair[0], reading(210, 52_388, 50.0))))
    }
    @Test fun `exact boundary readings bracket the dash`() {
        assertEquals(59, compare(listOf(reading(100, 52_329, 100.0), reading(200, 52_388, 157.3)))!!.carMiles)
    }
    @Test fun `a reading in the first ten minutes brackets the start`() {
        assertEquals(59, compare(listOf(reading(108, 52_329, 100.0), pair[1]))!!.carMiles)
        assertEquals(59, compare(listOf(reading(110, 52_329, 100.0), pair[1]))!!.carMiles)
    }
    @Test fun `a reading past the window does not`() {
        assertNull(compare(listOf(reading(111, 52_329, 100.0), pair[1])))
    }
    @Test fun `the nearest start reading wins`() {
        val rows = listOf(reading(98, 52_329, 100.0), reading(108, 52_350, 120.0), pair[1])
        assertEquals(59, compare(rows)!!.carMiles)
        assertEquals(57.3, compare(rows)!!.trackedMiles, 0.00001)
        // A closer reading after the start wins; an equal distance goes to the earlier.
        assertEquals(38, compare(listOf(reading(91, 52_329, 100.0), rows[1], pair[1]))!!.carMiles)
        assertEquals(59, compare(listOf(rows[0], reading(102, 52_350, 120.0), pair[1]))!!.carMiles)
    }
    @Test fun `a short dash never reuses its end reading as the start`() {
        val result = MileageReconciliation.between(
            listOf(reading(70, 52_329, 100.0), reading(107, 52_388, 157.3)),
            "civic", 100 * 60_000L, 105 * 60_000L,
        )!!
        assertEquals(59, result.carMiles)
        assertEquals(57.3, result.trackedMiles, 0.00001)
    }
    @Test fun `a lower end reading is null`() {
        assertNull(compare(listOf(pair[0], reading(210, 52_270, 157.3))))
    }

}

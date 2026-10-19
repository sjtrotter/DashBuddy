package cloud.trotter.dashbuddy.domain.model.vehicle

import org.junit.Assert.*
import org.junit.Test

class MileageReconciliationTest {
    private fun reading(at: Long, miles: Int, gps: Double?, id: String = "civic") =
        VehicleOdometerReading(id, LastOdometerReading(miles, gps, at))
    private val pair = listOf(reading(90, 52_329, 100.0), reading(210, 52_388, 157.3))
    private fun compare(rows: List<VehicleOdometerReading> = pair) = MileageReconciliation.between(rows, "civic", 100, 200)
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
}

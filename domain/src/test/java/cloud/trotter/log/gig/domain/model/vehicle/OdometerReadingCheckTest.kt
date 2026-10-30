package cloud.trotter.log.gig.domain.model.vehicle

import org.junit.Assert.*
import org.junit.Test

class OdometerReadingCheckTest {
    private val vehicle = Vehicle("civic", "Civic", 0)
    private val last = LastOdometerReading(52_140, 100.0, 0)
    private fun check(miles: Int? = 52_388, car: Vehicle? = vehicle, previous: LastOdometerReading? = last,
        gps: Double? = 184.0, now: Long = 86_400_000, readings: List<VehicleOdometerReading> = emptyList()) =
        OdometerReadingCheck.check(OdometerCheckInput(miles, car, previous, gps, now, readings))

    @Test fun `no vehicle returns NoVehicle`() { assertEquals(OdometerCheck.NoVehicle, check(car = null)) }
    @Test fun `empty or zero reading returns Empty`() {
        assertEquals(OdometerCheck.Empty, check(miles = null, previous = last))
        assertEquals(OdometerCheck.Empty, check(miles = 0, previous = last))
    }
    @Test fun `first reading for a vehicle returns First`() { assertEquals(OdometerCheck.First("Civic"), check(previous = null)) }
    @Test fun `first reading leaves an unnamed vehicle label to the UI`() {
        assertEquals(OdometerCheck.First(null), check(car = Vehicle("bike", null, 0, EBike()), previous = null))
    }
    @Test fun `a new vehicle is first before any digit`() {
        assertEquals(OdometerCheck.First("Civic"), check(miles = null, previous = null))
        assertEquals(OdometerCheck.First("Civic"), check(miles = 0, previous = null))
    }
    @Test fun `reading below last returns Lower`() { assertEquals(OdometerCheck.Lower(52_140), check(52_139)) }
    @Test fun `equal reading with no tracked miles is Ok with zero delta`() {
        assertEquals(OdometerCheck.Ok(0, 0), check(52_140, gps = 100.0))
    }
    @Test fun `delta under tracked minus slack returns FewerThanTracked`() {
        assertEquals(OdometerCheck.FewerThanTracked(12, 84.0, 0), check(52_152))
    }
    @Test fun `another vehicle read since the last reading disables the tracked check`() {
        val readings = listOf(VehicleOdometerReading("other", last.copy(occurredAt = 60_000), 1L))
        assertEquals(OdometerCheck.Ok(12, 0), check(52_152, readings = readings))
        assertEquals(OdometerCheck.Ok(1_050, 0), check(53_190))
        assertEquals(OdometerCheck.LargeJump(1_050, 0), check(53_190, readings = readings))
    }
    @Test fun `the tracked check excludes other vehicle readings outside the span and includes now`() {
        val now = 86_400_000L
        val outside = listOf(
            VehicleOdometerReading("other", last, 1L),
            VehicleOdometerReading("other", last.copy(occurredAt = now + 1), 2L),
            VehicleOdometerReading(vehicle.id, last.copy(occurredAt = now), 3L),
        )
        assertEquals(OdometerCheck.FewerThanTracked(12, 84.0, 0), check(52_152, now = now, readings = outside))
        val atNow = VehicleOdometerReading("other", last.copy(occurredAt = now), 4L)
        assertEquals(OdometerCheck.Ok(12, 0), check(52_152, now = now, readings = outside + atNow))
    }
    @Test fun `delta inside tracked slack is Ok`() { assertEquals(OdometerCheck.Ok(76, 0), check(52_216)) }
    @Test fun `slack uses the larger of five miles and ten percent`() {
        assertTrue(check(52_145, gps = 110.0) is OdometerCheck.Ok)
        assertTrue(check(52_144, gps = 110.0) is OdometerCheck.FewerThanTracked)
        assertTrue(check(52_230, gps = 200.0) is OdometerCheck.Ok)
        assertTrue(check(52_229, gps = 200.0) is OdometerCheck.FewerThanTracked)
    }
    @Test fun `extra digit returns LargeJump`() { assertEquals(OdometerCheck.LargeJump(469_260, 0), check(521_400)) }
    @Test fun `jump allowance scales with whole days elapsed`() {
        assertTrue(check(54_141, gps = 100.0, now = 86_400_001) is OdometerCheck.LargeJump)
        assertTrue(check(54_140, gps = 100.0, now = 86_400_001) is OdometerCheck.Ok)
        assertTrue(check(53_141, gps = 100.0, now = 86_400_000) is OdometerCheck.LargeJump)
        assertTrue(check(53_140, gps = 100.0, now = 0) is OdometerCheck.Ok)
    }
    @Test fun `null gps at either end skips the tracked check`() {
        assertTrue(check(52_140, gps = null) is OdometerCheck.Ok)
        assertTrue(check(52_140, previous = last.copy(gpsTotalMiles = null)) is OdometerCheck.Ok)
    }
    @Test fun `gps total below the last stamp skips the tracked check`() { assertTrue(check(52_140, gps = 90.0) is OdometerCheck.Ok) }
    @Test fun `Lower wins over FewerThanTracked`() { assertEquals(OdometerCheck.Lower(52_140), check(52_139, gps = 200.0)) }
}

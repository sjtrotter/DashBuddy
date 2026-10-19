package cloud.trotter.dashbuddy.domain.model.vehicle

import org.junit.Assert.*
import org.junit.Test

class OdometerReadingCheckTest {
    private val vehicle = Vehicle("civic", "Civic", 0)
    private val last = LastOdometerReading(52_140, 100.0, 0)
    private fun check(miles: Int? = 52_388, car: Vehicle? = vehicle, previous: LastOdometerReading? = last,
        gps: Double? = 184.0, now: Long = 86_400_000) =
        OdometerReadingCheck.check(OdometerCheckInput(miles, car, previous, gps, now))

    @Test fun `no vehicle returns NoVehicle`() { assertEquals(OdometerCheck.NoVehicle, check(car = null)) }
    @Test fun `empty or zero reading returns Empty`() {
        assertEquals(OdometerCheck.Empty, check(miles = null))
        assertEquals(OdometerCheck.Empty, check(miles = 0))
    }
    @Test fun `first reading for a vehicle returns First`() { assertEquals(OdometerCheck.First("Civic"), check(previous = null)) }
    @Test fun `reading below last returns Lower`() { assertEquals(OdometerCheck.Lower(52_140), check(52_139)) }
    @Test fun `equal reading with no tracked miles is Ok with zero delta`() {
        assertEquals(OdometerCheck.Ok(0, 0), check(52_140, gps = 100.0))
    }
    @Test fun `delta under tracked minus slack returns FewerThanTracked`() {
        assertEquals(OdometerCheck.FewerThanTracked(12, 84.0, 0), check(52_152))
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

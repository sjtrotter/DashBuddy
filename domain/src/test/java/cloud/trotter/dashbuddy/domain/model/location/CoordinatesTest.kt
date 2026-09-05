package cloud.trotter.dashbuddy.domain.model.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1057 round 2 — [Coordinates.distanceTo] must be TOTAL over every well-formed pair.
 *
 * The haversine intermediate `a` is mathematically in `[0, 1]`, but floating-point rounding pushes
 * it a hair past 1.0 on near-antipodal points (`1.0000000000000004`), and `asin(sqrt(a))` of that
 * is NaN — which used to flow straight into the odometer's persisted cumulative total, where no
 * later arithmetic can recover from it.
 */
class CoordinatesTest {

    @Test
    fun `an exactly antipodal pair yields a finite distance of about half the earth's circumference`() {
        val north = Coordinates(latitude = 45.0, longitude = 10.0)
        val antipode = Coordinates(latitude = -45.0, longitude = -170.0)

        val meters = north.distanceTo(antipode)

        assertTrue("distance is finite", meters.isFinite())
        // Half of 2πR at R = 6,371,000 m ≈ 20,015 km.
        assertEquals(20_015_086.0, meters, 1_000.0)
    }

    @Test
    fun `every near-antipodal pair around the globe yields a finite distance`() {
        var lon = -180.0
        while (lon <= 180.0) {
            val lat = (lon / 4.0).coerceIn(-89.9, 89.9)
            val here = Coordinates(latitude = lat, longitude = lon)
            val nearAntipode = Coordinates(
                latitude = -lat + 1e-13,
                longitude = if (lon <= 0.0) lon + 180.0 else lon - 180.0,
            )
            assertTrue(
                "finite distance at longitude $lon",
                here.distanceTo(nearAntipode).isFinite(),
            )
            lon += 0.5
        }
    }

    @Test
    fun `an ordinary short leg is unchanged by the clamp`() {
        val origin = Coordinates(latitude = 0.0, longitude = 0.0)
        val north = Coordinates(latitude = Math.toDegrees(150.0 / 6_371_000.0), longitude = 0.0)

        assertEquals(150.0, origin.distanceTo(north), 0.01)
    }
}

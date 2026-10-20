package cloud.trotter.dashbuddy.domain.model.vehicle

import org.junit.Assert.*
import org.junit.Test

class VehicleOdometerCapabilityTest {
    @Test fun `support and default visibility follow every vehicle type`() {
        val expected = mapOf(
            VehicleType.CAR to OdometerSupport.BUILT_IN,
            VehicleType.HYBRID to OdometerSupport.BUILT_IN,
            VehicleType.EV to OdometerSupport.BUILT_IN,
            VehicleType.MOTORCYCLE to OdometerSupport.BUILT_IN,
            VehicleType.SCOOTER to OdometerSupport.BUILT_IN,
            VehicleType.E_BIKE to OdometerSupport.OPTIONAL,
            VehicleType.BICYCLE to OdometerSupport.OPTIONAL,
        )
        assertEquals(VehicleType.entries.toSet(), expected.keys)
        expected.forEach { (type, support) ->
            assertEquals(type.name, support, type.odometerSupport)
            val vehicle = Vehicle(type.name, null, 1, type.defaultProfile())
            assertNull(vehicle.odometerShown)
            assertEquals(type.name, support == OdometerSupport.BUILT_IN, vehicle.showsOdometer)
            assertEquals(vehicle.showsOdometer, odometerShownFor(vehicle))
        }
    }

    @Test fun `explicit choices override every type both ways`() {
        VehicleType.entries.forEach { type ->
            val vehicle = Vehicle(type.name, null, 1, type.defaultProfile())
            for (shown in listOf(false, true)) {
                val overridden = vehicle.copy(odometerShown = shown)
                assertEquals(shown, overridden.showsOdometer)
                assertEquals(shown, odometerShownFor(overridden))
            }
        }
        assertFalse(Vehicle("scooter", null, 1,
            ScooterMoped(powertrain = Powertrain.ELECTRIC), odometerShown = false).showsOdometer)
        assertTrue(Vehicle("scooter", null, 1, ScooterMoped(powertrain = Powertrain.ELECTRIC)).showsOdometer)
    }

    @Test fun `no active vehicle keeps the odometer shown`() {
        assertTrue(odometerShownFor(null))
    }
}

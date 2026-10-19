package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleProfile
import org.junit.Assert.*
import org.junit.Test

class VehicleEconomyTest {
    @Test fun `class defaults and nullable overrides stay per car while shared inputs stay global`() {
        val base = UserEconomy(gasPricePerGallon = 4.25, avgMinutesPerMile = 3.0,
            phonePlanTotal = 120.0, phoneBusinessPercent = 40.0)
        val compact = VehicleEconomy.apply(base, VehicleProfile(vehicleClass = VehicleClass.COMPACT,
            mpg = 36.0, tireSetCost = 700.0, expectedAnnualMiles = 15_000.0))
        val truck = VehicleEconomy.apply(base, VehicleProfile(vehicleClass = VehicleClass.TRUCK))
        assertEquals(36.0, compact.vehicleMpg, 0.0)
        assertEquals(700.0, compact.tireSetCost, 0.0)
        assertEquals(15_000.0, compact.expectedAnnualMiles, 0.0)
        assertEquals(VehicleClass.TRUCK.defaultMpg, truck.vehicleMpg, 0.0)
        assertEquals(VehicleClass.TRUCK.tireSetCost, truck.tireSetCost, 0.0)
        assertEquals(4.25, truck.gasPricePerGallon, 0.0)
        assertEquals(3.0, truck.avgMinutesPerMile, 0.0)
        assertEquals(120.0, truck.phonePlanTotal, 0.0)
        assertEquals(40.0, truck.phoneBusinessPercent, 0.0)
    }
}

package cloud.trotter.dashbuddy.feature.setup.wizard.model

import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.model.vehicle.ElectricCar
import cloud.trotter.dashbuddy.domain.model.vehicle.FixedCosts
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import cloud.trotter.dashbuddy.domain.model.vehicle.WearProfile
import cloud.trotter.dashbuddy.feature.setup.wizard.vehicle.VehicleDraftState
import org.junit.Assert.assertEquals
import org.junit.Test

class WizardStateTest {
    @Test
    fun `vehicle projection retains EV consumption and applies the host tire override`() {
        val fixed = FixedCosts(insuranceDeltaPerMonth = 25.0)
        val vehicle = VehicleDraftState(
            profile = ElectricCar(kwhPer100Mi = 40.0,
                wear = WearProfile(tireSetCost = 800.0, tireLifetimeMi = 45_000.0), fixed = fixed),
            vehicleClass = VehicleClass.EV, kwhPer100Mi = 27.0,
            userSetFields = setOf(EconomyField.KWH_PER_100_MI),
        )
        val host = WizardState(tireSetCost = 900.0, userSetEconomyFields = setOf(EconomyField.TIRE_COST))

        // The complete result of the previous combined vehicle-and-host projection.
        assertEquals(ElectricCar(kwhPer100Mi = 27.0,
            wear = WearProfile(tireSetCost = 900.0, tireLifetimeMi = 45_000.0), fixed = fixed),
            host.toVehicleProfile(vehicle))
    }
}

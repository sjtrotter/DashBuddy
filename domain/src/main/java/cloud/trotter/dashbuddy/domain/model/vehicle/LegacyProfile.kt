package cloud.trotter.dashbuddy.domain.model.vehicle

import kotlinx.serialization.Serializable

/** v1 wire shape and legacy preference import only. */
@Serializable
data class LegacyProfile(
    val year: String? = null,
    val make: String? = null,
    val model: String? = null,
    val mpg: Double? = null,
    val vehicleClass: VehicleClass = VehicleClass.SEDAN,
    val fuelType: FuelType = FuelType.REGULAR,
    val tireSetCost: Double? = null,
    val tireLifetimeMi: Double? = null,
    val oilCost: Double? = null,
    val oilIntervalMi: Double? = null,
    val brakesCost: Double? = null,
    val brakesIntervalMi: Double? = null,
    val fluidsCost: Double? = null,
    val fluidsIntervalMi: Double? = null,
    val miscYearly: Double? = null,
    val miscYearlyMi: Double? = null,
    val includeDepreciation: Boolean? = null,
    val purchasePrice: Double? = null,
    val totalLifetimeMi: Double? = null,
    val insuranceDeltaPerMonth: Double? = null,
    val registrationDeltaPerYear: Double? = null,
    val expectedAnnualMiles: Double? = null,
)

fun LegacyProfile.toTyped(): VehicleProfile {
    val wear = WearProfile(tireSetCost, tireLifetimeMi, oilCost, oilIntervalMi, brakesCost, brakesIntervalMi,
        fluidsCost, fluidsIntervalMi, miscYearly = miscYearly, miscYearlyMi = miscYearlyMi)
    val fixed = FixedCosts(includeDepreciation, purchasePrice, totalLifetimeMi,
        insuranceDeltaPerMonth, registrationDeltaPerYear, expectedAnnualMiles)
    return when (vehicleClass) {
        VehicleClass.EV -> ElectricCar(wear = wear, fixed = fixed, year = year, make = make, model = model)
        VehicleClass.MOTORCYCLE -> Motorcycle(mpg, fuelType, wear, fixed, year, make, model)
        VehicleClass.E_BIKE -> EBike(wear = wear, fixed = fixed, year = year, make = make, model = model)
        else -> GasCar(requireNotNull(vehicleClass.gasClass), mpg, fuelType, wear, fixed, year, make, model)
    }
}

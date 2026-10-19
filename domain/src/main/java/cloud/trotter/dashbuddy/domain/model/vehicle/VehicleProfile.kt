package cloud.trotter.dashbuddy.domain.model.vehicle

import kotlinx.serialization.Serializable

/** Vehicle identity and costs. Null cost fields continue to follow the class preset. */
@Serializable
data class VehicleProfile(
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

package cloud.trotter.dashbuddy.domain.model.vehicle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Null overrides follow the selected type's estimate. Identity is retained on migration. */
@Serializable
sealed interface VehicleProfile {
    val wear: WearProfile
    val fixed: FixedCosts
    val year: String?
    val make: String?
    val model: String?
}

@Serializable
enum class GasCarClass {
    COMPACT, SEDAN, SUV, TRUCK, LUXURY;
    val defaultMpg: Double get() = VehiclePresets.cars.getValue(this).mpg
    val wear: WearProfile get() = VehiclePresets.cars.getValue(this).wear
    val fixed: FixedCosts get() = VehiclePresets.cars.getValue(this).fixed
}
@Serializable
enum class Powertrain { GAS, ELECTRIC }

@Serializable
data class WearProfile(
    val tireSetCost: Double? = null, val tireLifetimeMi: Double? = null,
    val oilCost: Double? = null, val oilIntervalMi: Double? = null,
    val brakesCost: Double? = null, val brakesIntervalMi: Double? = null,
    val fluidsCost: Double? = null, val fluidsIntervalMi: Double? = null,
    val chainCost: Double? = null, val chainLifeMi: Double? = null,
    val batteryCost: Double? = null, val batteryLifeMi: Double? = null,
    val beltCost: Double? = null, val beltLifeMi: Double? = null,
    val miscYearly: Double? = null, val miscYearlyMi: Double? = null,
)
@Serializable
data class FixedCosts(
    val includeDepreciation: Boolean? = null,
    val purchasePrice: Double? = null,
    val totalLifetimeMi: Double? = null,
    val insuranceDeltaPerMonth: Double? = null,
    val registrationDeltaPerYear: Double? = null,
    val expectedAnnualMiles: Double? = null,
)

@Serializable
@SerialName("gas_car")
data class GasCar(
    val vehicleClass: GasCarClass = GasCarClass.SEDAN,
    val mpg: Double? = null,
    val fuelType: FuelType = FuelType.REGULAR,
    override val wear: WearProfile = WearProfile(),
    override val fixed: FixedCosts = FixedCosts(),
    override val year: String? = null,
    override val make: String? = null,
    override val model: String? = null,
) : VehicleProfile

@Serializable
@SerialName("hybrid_car")
data class HybridCar(
    val vehicleClass: GasCarClass = GasCarClass.SEDAN,
    val mpg: Double? = null,
    val fuelType: FuelType = FuelType.REGULAR,
    override val wear: WearProfile = WearProfile(),
    override val fixed: FixedCosts = FixedCosts(),
    override val year: String? = null,
    override val make: String? = null,
    override val model: String? = null,
) : VehicleProfile

@Serializable
@SerialName("electric_car")
data class ElectricCar(
    val kwhPer100Mi: Double? = null,
    override val wear: WearProfile = WearProfile(),
    override val fixed: FixedCosts = FixedCosts(),
    override val year: String? = null,
    override val make: String? = null,
    override val model: String? = null,
) : VehicleProfile

@Serializable
@SerialName("motorcycle")
data class Motorcycle(
    val mpg: Double? = null,
    val fuelType: FuelType = FuelType.REGULAR,
    override val wear: WearProfile = WearProfile(),
    override val fixed: FixedCosts = FixedCosts(),
    override val year: String? = null,
    override val make: String? = null,
    override val model: String? = null,
) : VehicleProfile

@Serializable
@SerialName("scooter_moped")
data class ScooterMoped(
    val powertrain: Powertrain = Powertrain.GAS,
    val mpg: Double? = null,
    val kwhPer100Mi: Double? = null,
    val fuelType: FuelType = FuelType.REGULAR,
    override val wear: WearProfile = WearProfile(),
    override val fixed: FixedCosts = FixedCosts(),
    override val year: String? = null,
    override val make: String? = null,
    override val model: String? = null,
) : VehicleProfile

@Serializable
@SerialName("e_bike")
data class EBike(
    val whPerMile: Double? = null,
    override val wear: WearProfile = WearProfile(),
    override val fixed: FixedCosts = FixedCosts(),
    override val year: String? = null,
    override val make: String? = null,
    override val model: String? = null,
) : VehicleProfile

@Serializable
@SerialName("bicycle")
data class Bicycle(
    override val wear: WearProfile = WearProfile(),
    override val fixed: FixedCosts = FixedCosts(),
    override val year: String? = null,
    override val make: String? = null,
    override val model: String? = null,
) : VehicleProfile

/** Uniform structural updates keep the type; no irrelevant propulsion fields are invented. */
fun VehicleProfile.withCosts(wear: WearProfile = this.wear, fixed: FixedCosts = this.fixed): VehicleProfile = when (this) {
    is GasCar -> copy(wear = wear, fixed = fixed)
    is HybridCar -> copy(wear = wear, fixed = fixed)
    is ElectricCar -> copy(wear = wear, fixed = fixed)
    is Motorcycle -> copy(wear = wear, fixed = fixed)
    is ScooterMoped -> copy(wear = wear, fixed = fixed)
    is EBike -> copy(wear = wear, fixed = fixed)
    is Bicycle -> copy(wear = wear, fixed = fixed)
}

val VehicleProfile.legacyClass: VehicleClass get() = when (this) {
    is GasCar -> VehicleClass.valueOf(vehicleClass.name)
    is HybridCar -> VehicleClass.valueOf(vehicleClass.name)
    is ElectricCar -> VehicleClass.EV
    is Motorcycle, is ScooterMoped -> VehicleClass.MOTORCYCLE
    is EBike, is Bicycle -> VehicleClass.E_BIKE
}
val VehicleProfile.displayType: String get() = when (this) {
    is GasCar -> legacyClass.displayName
    is HybridCar -> "Hybrid"
    is ElectricCar -> "EV"
    is Motorcycle -> "Motorcycle"
    is ScooterMoped -> "Scooter or moped"
    is EBike -> "E-Bike"
    is Bicycle -> "Bicycle"
}
val VehicleProfile.gasMpg: Double? get() = when (this) {
    is GasCar -> mpg
    is HybridCar -> mpg
    is Motorcycle -> mpg
    is ScooterMoped -> mpg.takeIf { powertrain == Powertrain.GAS }
    else -> null
}
val VehicleProfile.gasFuel: FuelType? get() = when (this) {
    is GasCar -> fuelType
    is HybridCar -> fuelType
    is Motorcycle -> fuelType
    is ScooterMoped -> fuelType.takeIf { powertrain == Powertrain.GAS }
    else -> null
}
fun VehicleProfile.withGas(mpg: Double? = gasMpg, fuelType: FuelType = gasFuel ?: FuelType.REGULAR): VehicleProfile = when (this) {
    is GasCar -> copy(mpg = mpg, fuelType = fuelType)
    is HybridCar -> copy(mpg = mpg, fuelType = fuelType)
    is Motorcycle -> copy(mpg = mpg, fuelType = fuelType)
    is ScooterMoped -> if (powertrain == Powertrain.GAS) copy(mpg = mpg, fuelType = fuelType) else this
    else -> this
}

fun VehicleProfile.withIdentity(year: String? = this.year, make: String? = this.make, model: String? = this.model): VehicleProfile = when (this) {
    is GasCar -> copy(year = year, make = make, model = model)
    is HybridCar -> copy(year = year, make = make, model = model)
    is ElectricCar -> copy(year = year, make = make, model = model)
    is Motorcycle -> copy(year = year, make = make, model = model)
    is ScooterMoped -> copy(year = year, make = make, model = model)
    is EBike -> copy(year = year, make = make, model = model)
    is Bicycle -> copy(year = year, make = make, model = model)
}

enum class VehicleType(val displayName: String) {
    CAR("Car"), HYBRID("Hybrid"), EV("EV"), MOTORCYCLE("Motorcycle"),
    SCOOTER("Scooter or moped"), E_BIKE("E-bike"), BICYCLE("Bicycle");
    fun defaultProfile(): VehicleProfile = when (this) {
        CAR -> GasCar()
        HYBRID -> HybridCar()
        EV -> ElectricCar()
        MOTORCYCLE -> Motorcycle()
        SCOOTER -> ScooterMoped()
        E_BIKE -> EBike()
        BICYCLE -> Bicycle()
    }
}
val VehicleProfile.vehicleType: VehicleType get() = when (this) {
    is GasCar -> VehicleType.CAR
    is HybridCar -> VehicleType.HYBRID
    is ElectricCar -> VehicleType.EV
    is Motorcycle -> VehicleType.MOTORCYCLE
    is ScooterMoped -> VehicleType.SCOOTER
    is EBike -> VehicleType.E_BIKE
    is Bicycle -> VehicleType.BICYCLE
}
val VehicleProfile.hasCarIdentity: Boolean get() = this is GasCar || this is HybridCar

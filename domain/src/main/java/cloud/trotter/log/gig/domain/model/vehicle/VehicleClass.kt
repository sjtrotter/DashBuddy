package cloud.trotter.log.gig.domain.model.vehicle

import kotlinx.serialization.Serializable

/** Legacy preference and v1 wire class. New profiles use GasCarClass and their sealed type. */
@Serializable
enum class VehicleClass(val displayName: String) {
    COMPACT("Compact"), SEDAN("Sedan"), SUV("SUV"), TRUCK("Truck"), LUXURY("Luxury"),
    EV("EV"), MOTORCYCLE("Motorcycle"), E_BIKE("E-Bike");
    private val preset: VehiclePresets.Preset get() = when (this) {
        EV -> VehiclePresets.electricCar
        MOTORCYCLE -> VehiclePresets.motorcycle
        E_BIKE -> VehiclePresets.eBike
        else -> VehiclePresets.cars.getValue(requireNotNull(gasClass))
    }
    val defaultMpg: Double get() = preset.mpg
    val tireSetCost: Double get() = preset.wear.tireSetCost ?: 0.0
    val tireLifetimeMi: Double get() = preset.wear.tireLifetimeMi ?: 1.0
    val oilCost: Double get() = preset.wear.oilCost ?: 0.0
    val oilIntervalMi: Double get() = preset.wear.oilIntervalMi ?: 1.0
    val brakesCost: Double get() = preset.wear.brakesCost ?: 0.0
    val brakesIntervalMi: Double get() = preset.wear.brakesIntervalMi ?: 1.0
    val fluidsCost: Double get() = preset.wear.fluidsCost ?: 0.0
    val fluidsIntervalMi: Double get() = preset.wear.fluidsIntervalMi ?: 1.0
    val miscYearly: Double get() = preset.wear.miscYearly ?: 0.0
    val miscYearlyMi: Double get() = preset.wear.miscYearlyMi ?: 1.0
    val purchasePrice: Double get() = preset.fixed.purchasePrice!!
    val totalLifetimeMi: Double get() = preset.fixed.totalLifetimeMi!!
    val burnsFuel: Boolean get() = this != EV && this != E_BIKE
}

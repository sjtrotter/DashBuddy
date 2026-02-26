package cloud.trotter.dashbuddy.ui.main.setup.wizard.model

import cloud.trotter.dashbuddy.model.vehicle.FuelType

/**
 * Represents the volatile UI state of the Setup Wizard before it is committed to DataStore.
 */
data class WizardState(
    // Economy Variables
    val vehicleType: VehicleType = VehicleType.CAR,
    val vehicleYear: String = "",
    val vehicleMake: String = "",
    val vehicleModel: String = "",
    val vehicleTrim: String = "",
    val estimatedMpg: Float = 25.0f,

    // Gas Variables
    val fuelType: FuelType = FuelType.REGULAR,
    val isGasPriceAuto: Boolean = true,
    val gasPrice: Float = 3.50f,
    val isFetchingGasPrice: Boolean = false,
    val fetchedGasPrices: Map<FuelType, Float> = emptyMap(),

    // Strategy Variables
    val strategy: DashStrategy = DashStrategy.MANUAL,
    val allowShopping: Boolean = true,

    // Targets & Enforcement
    val enforceMinPayout: Boolean = false,
    val minPayout: Float = 5.0f,

    val enforceTargetHourly: Boolean = false,
    val targetHourly: Float = 20.0f,

    val enforceMaxDistance: Boolean = false,
    val maxDistance: Float = 10.0f,

    val maxItems: Float = 15.0f // Shopping
)
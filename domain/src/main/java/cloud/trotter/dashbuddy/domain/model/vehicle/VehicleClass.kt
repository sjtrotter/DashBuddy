package cloud.trotter.dashbuddy.domain.model.vehicle

/**
 * The vehicle category the Dasher uses. Each value carries IRS-aligned default
 * operating-cost constants so a user who accepts the wizard's defaults still
 * gets a reasonable cost-per-mile estimate without entering individual numbers.
 *
 * Defaults are approximate midpoints; the user can override any field via the
 * Economy wizard step or Personal Economy settings.
 */
enum class VehicleClass(
    val displayName: String,
    /** Default combined MPG (gas vehicles); zero/ignored for EV and non-car classes. */
    val defaultMpg: Double,

    // Maintenance (paired cost + interval)
    val tireSetCost: Double, val tireLifetimeMi: Double,
    val oilCost: Double, val oilIntervalMi: Double,
    val brakesCost: Double, val brakesIntervalMi: Double,
    val fluidsCost: Double, val fluidsIntervalMi: Double,
    val miscYearly: Double, val miscYearlyMi: Double,

    // Depreciation
    val purchasePrice: Double, val totalLifetimeMi: Double,
) {
    COMPACT(
        displayName = "Compact",
        defaultMpg = 32.0,
        tireSetCost = 600.0, tireLifetimeMi = 45_000.0,
        oilCost = 40.0, oilIntervalMi = 5_000.0,
        brakesCost = 350.0, brakesIntervalMi = 45_000.0,
        fluidsCost = 120.0, fluidsIntervalMi = 30_000.0,
        miscYearly = 400.0, miscYearlyMi = 12_000.0,
        purchasePrice = 22_000.0, totalLifetimeMi = 200_000.0,
    ),
    SEDAN(
        displayName = "Sedan",
        defaultMpg = 30.0,
        tireSetCost = 800.0, tireLifetimeMi = 40_000.0,
        oilCost = 60.0, oilIntervalMi = 5_000.0,
        brakesCost = 400.0, brakesIntervalMi = 40_000.0,
        fluidsCost = 150.0, fluidsIntervalMi = 30_000.0,
        miscYearly = 500.0, miscYearlyMi = 12_000.0,
        purchasePrice = 28_000.0, totalLifetimeMi = 200_000.0,
    ),
    SUV(
        displayName = "SUV",
        defaultMpg = 24.0,
        tireSetCost = 1_000.0, tireLifetimeMi = 35_000.0,
        oilCost = 70.0, oilIntervalMi = 5_000.0,
        brakesCost = 500.0, brakesIntervalMi = 35_000.0,
        fluidsCost = 200.0, fluidsIntervalMi = 30_000.0,
        miscYearly = 700.0, miscYearlyMi = 12_000.0,
        purchasePrice = 35_000.0, totalLifetimeMi = 220_000.0,
    ),
    TRUCK(
        displayName = "Truck",
        defaultMpg = 20.0,
        tireSetCost = 1_100.0, tireLifetimeMi = 35_000.0,
        oilCost = 80.0, oilIntervalMi = 5_000.0,
        brakesCost = 550.0, brakesIntervalMi = 35_000.0,
        fluidsCost = 250.0, fluidsIntervalMi = 30_000.0,
        miscYearly = 800.0, miscYearlyMi = 12_000.0,
        purchasePrice = 40_000.0, totalLifetimeMi = 250_000.0,
    ),
    LUXURY(
        displayName = "Luxury",
        defaultMpg = 26.0,
        tireSetCost = 1_500.0, tireLifetimeMi = 35_000.0,
        oilCost = 120.0, oilIntervalMi = 7_000.0,
        brakesCost = 800.0, brakesIntervalMi = 35_000.0,
        fluidsCost = 400.0, fluidsIntervalMi = 30_000.0,
        miscYearly = 1_500.0, miscYearlyMi = 12_000.0,
        purchasePrice = 55_000.0, totalLifetimeMi = 180_000.0,
    ),
    EV(
        displayName = "EV",
        defaultMpg = 0.0,
        tireSetCost = 1_000.0, tireLifetimeMi = 35_000.0,
        oilCost = 0.0, oilIntervalMi = 1.0,
        brakesCost = 400.0, brakesIntervalMi = 80_000.0,
        fluidsCost = 100.0, fluidsIntervalMi = 50_000.0,
        miscYearly = 400.0, miscYearlyMi = 12_000.0,
        purchasePrice = 40_000.0, totalLifetimeMi = 200_000.0,
    ),
    MOTORCYCLE(
        displayName = "Motorcycle",
        defaultMpg = 50.0,
        tireSetCost = 400.0, tireLifetimeMi = 15_000.0,
        oilCost = 50.0, oilIntervalMi = 4_000.0,
        brakesCost = 250.0, brakesIntervalMi = 25_000.0,
        fluidsCost = 100.0, fluidsIntervalMi = 20_000.0,
        miscYearly = 400.0, miscYearlyMi = 8_000.0,
        purchasePrice = 12_000.0, totalLifetimeMi = 80_000.0,
    ),
    E_BIKE(
        displayName = "E-Bike",
        defaultMpg = 0.0,
        tireSetCost = 0.0, tireLifetimeMi = 1.0,
        oilCost = 0.0, oilIntervalMi = 1.0,
        brakesCost = 0.0, brakesIntervalMi = 1.0,
        fluidsCost = 0.0, fluidsIntervalMi = 1.0,
        miscYearly = 0.0, miscYearlyMi = 1.0,
        purchasePrice = 0.0, totalLifetimeMi = 1.0,
    );

    val burnsFuel: Boolean get() = this != EV && this != E_BIKE
}

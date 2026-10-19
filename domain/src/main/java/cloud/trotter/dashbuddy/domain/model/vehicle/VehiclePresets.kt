package cloud.trotter.dashbuddy.domain.model.vehicle

import cloud.trotter.dashbuddy.domain.evaluation.TravelProfile

/** All vehicle, energy-consumption and travel presets are ESTIMATES for developer review.
 * These are editable assumptions, not measured costs. E-bike battery replacement is wear;
 * its depreciation estimate covers the rest of the bicycle, excluding that replacement reserve.
 */
object VehiclePresets {
    data class Preset(val mpg: Double, val wear: WearProfile, val fixed: FixedCosts, val travel: TravelProfile)
    const val CAR_MINUTES_PER_MILE = 2.5
    const val CAR_BASE_PICKUP_MINUTES = 7.0
    val carTravel = TravelProfile(CAR_MINUTES_PER_MILE, CAR_BASE_PICKUP_MINUTES)
    val motorTravel = TravelProfile(2.3, 5.0)
    val eBikeTravel = TravelProfile(5.0, 4.0)
    val bicycleTravel = TravelProfile(6.0, 4.0)
    const val HYBRID_MPG = 45.0
    const val HYBRID_BRAKE_LIFE_MULTIPLIER = 1.5
    // 33.7 kWh is one gallon of gasoline equivalent; multiply by 100 for kWh per 100 miles.
    const val MPGE_TO_KWH_PER_100_MI = 3370.0
    const val EV_KWH_PER_100_MI = 30.0
    const val SCOOTER_MPG = 90.0
    const val SCOOTER_KWH_PER_100_MI = 3.0
    const val EBIKE_WH_PER_MILE = 20.0
    val cars: Map<GasCarClass, Preset> = mapOf(
        GasCarClass.COMPACT to Preset(32.0, WearProfile(tireSetCost = 600.0, tireLifetimeMi = 45_000.0, oilCost = 40.0, oilIntervalMi = 5_000.0, brakesCost = 350.0, brakesIntervalMi = 45_000.0, fluidsCost = 120.0, fluidsIntervalMi = 30_000.0, miscYearly = 400.0, miscYearlyMi = 12_000.0), FixedCosts(true, 22_000.0, 200_000.0), carTravel),
        GasCarClass.SEDAN to Preset(30.0, WearProfile(tireSetCost = 800.0, tireLifetimeMi = 40_000.0, oilCost = 60.0, oilIntervalMi = 5_000.0, brakesCost = 400.0, brakesIntervalMi = 40_000.0, fluidsCost = 150.0, fluidsIntervalMi = 30_000.0, miscYearly = 500.0, miscYearlyMi = 12_000.0), FixedCosts(true, 28_000.0, 200_000.0), carTravel),
        GasCarClass.SUV to Preset(24.0, WearProfile(tireSetCost = 1_000.0, tireLifetimeMi = 35_000.0, oilCost = 70.0, oilIntervalMi = 5_000.0, brakesCost = 500.0, brakesIntervalMi = 35_000.0, fluidsCost = 200.0, fluidsIntervalMi = 30_000.0, miscYearly = 700.0, miscYearlyMi = 12_000.0), FixedCosts(true, 35_000.0, 220_000.0), carTravel),
        GasCarClass.TRUCK to Preset(20.0, WearProfile(tireSetCost = 1_100.0, tireLifetimeMi = 35_000.0, oilCost = 80.0, oilIntervalMi = 5_000.0, brakesCost = 550.0, brakesIntervalMi = 35_000.0, fluidsCost = 250.0, fluidsIntervalMi = 30_000.0, miscYearly = 800.0, miscYearlyMi = 12_000.0), FixedCosts(true, 40_000.0, 250_000.0), carTravel),
        GasCarClass.LUXURY to Preset(26.0, WearProfile(tireSetCost = 1_500.0, tireLifetimeMi = 35_000.0, oilCost = 120.0, oilIntervalMi = 7_000.0, brakesCost = 800.0, brakesIntervalMi = 35_000.0, fluidsCost = 400.0, fluidsIntervalMi = 30_000.0, miscYearly = 1_500.0, miscYearlyMi = 12_000.0), FixedCosts(true, 55_000.0, 180_000.0), carTravel),
    )
    val electricCar = Preset(0.0, WearProfile(tireSetCost = 1_000.0, tireLifetimeMi = 35_000.0, oilCost = 0.0, oilIntervalMi = 1.0, brakesCost = 400.0, brakesIntervalMi = 80_000.0, fluidsCost = 100.0, fluidsIntervalMi = 50_000.0, miscYearly = 400.0, miscYearlyMi = 12_000.0), FixedCosts(true, 40_000.0, 200_000.0), carTravel)
    val motorcycle = Preset(50.0, WearProfile(tireSetCost = 400.0, tireLifetimeMi = 15_000.0, oilCost = 50.0, oilIntervalMi = 4_000.0, brakesCost = 250.0, brakesIntervalMi = 25_000.0, fluidsCost = 100.0, fluidsIntervalMi = 20_000.0, miscYearly = 400.0, miscYearlyMi = 8_000.0, chainCost = 150.0, chainLifeMi = 15_000.0), FixedCosts(true, 12_000.0, 80_000.0), motorTravel)
    val scooter = Preset(SCOOTER_MPG, WearProfile(tireSetCost = 150.0, tireLifetimeMi = 8_000.0,
        brakesCost = 100.0, brakesIntervalMi = 10_000.0, beltCost = 80.0, beltLifeMi = 10_000.0),
        FixedCosts(true, 2_500.0, 30_000.0), motorTravel)
    val eBike = Preset(0.0, WearProfile(tireSetCost = 80.0, tireLifetimeMi = 3_000.0,
        brakesCost = 40.0, brakesIntervalMi = 4_000.0, chainCost = 40.0, chainLifeMi = 2_500.0,
        batteryCost = 500.0, batteryLifeMi = 15_000.0, miscYearly = 50.0, miscYearlyMi = 2_000.0),
        FixedCosts(true, 1_800.0, 20_000.0), eBikeTravel)
    val bicycle = Preset(0.0, WearProfile(tireSetCost = 60.0, tireLifetimeMi = 3_000.0,
        brakesCost = 30.0, brakesIntervalMi = 4_000.0, chainCost = 30.0, chainLifeMi = 2_500.0,
        miscYearly = 50.0, miscYearlyMi = 2_000.0), FixedCosts(true, 800.0, 15_000.0), bicycleTravel)
    fun forProfile(profile: VehicleProfile): Preset = when (profile) {
        is GasCar -> cars.getValue(profile.vehicleClass)
        is HybridCar -> cars.getValue(profile.vehicleClass).let { base -> base.copy(mpg = HYBRID_MPG,
            wear = base.wear.copy(brakesIntervalMi = base.wear.brakesIntervalMi!! * HYBRID_BRAKE_LIFE_MULTIPLIER)) }
        is ElectricCar -> electricCar
        is Motorcycle -> motorcycle
        is ScooterMoped -> scooter
        is EBike -> eBike
        is Bicycle -> bicycle
    }
}

package cloud.trotter.log.gig.domain.evaluation

import cloud.trotter.log.gig.domain.model.vehicle.*

/**
 * The values an economy editor holds before they are written: the vehicle's consumption, its wear and fixed costs
 * (resolved: an editor holds a value for every field), the shared settings, and which fields the user set.
 * [EconomyFieldGroups] writes a vehicle profile and the legacy preference mirror from it (#1364).
 */
data class EconomyDraft(
    val vehicleClass: VehicleClass = VehicleClass.SEDAN,
    val mpg: Double = vehicleClass.defaultMpg,
    val kwhPer100Mi: Double = VehiclePresets.EV_KWH_PER_100_MI,
    val whPerMile: Double = VehiclePresets.EBIKE_WH_PER_MILE,
    val wear: WearProfile = WearProfile(),
    val fixed: FixedCosts = FixedCosts(),
    val shared: SharedEconomy = SharedEconomy(),
    val userSetFields: Set<EconomyField> = emptySet(),
)

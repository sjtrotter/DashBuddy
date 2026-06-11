package cloud.trotter.dashbuddy.core.network.vehicle.efficiency.epa

import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass

/**
 * Maps an EPA `VClass` string (from `/ws/rest/vehicle/{id}`) to a DashBuddy
 * [VehicleClass]. The mapping is intentionally forgiving — lowercased contains-match
 * with priority-ordered checks so EPA can rename or add classes without breaking
 * production. Unmappable inputs return `null`; callers fall back to whatever default
 * makes sense (the wizard picks `SEDAN`).
 *
 * EPA's published VClass values (as of 2026):
 *   Compact Cars, Subcompact Cars, Minicompact Cars, Two Seaters,
 *   Midsize Cars, Large Cars, Midsize-Large Station Wagons, Small Station Wagons,
 *   Small Sport Utility Vehicle, Standard Sport Utility Vehicle,
 *   Small Pickup Trucks, Standard Pickup Trucks,
 *   Vans, Minivan, Special Purpose Vehicles
 *
 * EVs are detected via fuel type elsewhere ([EpaVehicleDataSource.mapFuelType]), not
 * via VClass — EPA doesn't have an "EV" size class. Bikes/motorcycles aren't covered
 * by EPA at all; the user picks those manually.
 */
fun mapEpaVClass(raw: String?): VehicleClass? {
    if (raw.isNullOrBlank()) return null
    // Locale.ROOT (#405): EPA wire string — same Turkish-I class.
    val s = raw.lowercase(java.util.Locale.ROOT)

    return when {
        // Pickups before SUVs because some "pickup utility" strings contain both
        "pickup" in s || "truck" in s -> VehicleClass.TRUCK
        "sport utility" in s || "suv" in s -> VehicleClass.SUV
        "van" in s -> VehicleClass.SUV   // vans/minivans closest to SUV cost profile
        // Compact-class detection
        "compact" in s || "subcompact" in s || "minicompact" in s || "two seater" in s ->
            VehicleClass.COMPACT
        // Midsize / large / wagons → SEDAN
        "midsize" in s || "large" in s || "station wagon" in s || "wagon" in s ->
            VehicleClass.SEDAN
        // Special purpose vehicles default to SEDAN (least likely to surprise)
        "special purpose" in s -> VehicleClass.SEDAN
        else -> null
    }
}

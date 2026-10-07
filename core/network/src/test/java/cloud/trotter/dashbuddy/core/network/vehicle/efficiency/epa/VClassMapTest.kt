package cloud.trotter.dashbuddy.core.network.vehicle.efficiency.epa

import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Table-driven test for [mapEpaVClass]. Covers all EPA `VClass` strings observed
 * as of 2026 plus edge cases (null/blank/unknown).
 */
class VClassMapTest {
    @Test fun `EPA class mappings`() {
        listOf(
            "Compact Cars" to VehicleClass.COMPACT,
            "Subcompact Cars" to VehicleClass.COMPACT,
            "Minicompact Cars" to VehicleClass.COMPACT,
            "Two Seaters" to VehicleClass.COMPACT,
            "compact cars" to VehicleClass.COMPACT,
            "Midsize Cars" to VehicleClass.SEDAN,
            "Large Cars" to VehicleClass.SEDAN,
            "Midsize-Large Station Wagons" to VehicleClass.SEDAN,
            "Small Station Wagons" to VehicleClass.SEDAN,
            "Small Sport Utility Vehicle" to VehicleClass.SUV,
            "Standard Sport Utility Vehicle" to VehicleClass.SUV,
            "Sport Utility Vehicle" to VehicleClass.SUV,
            "Vans" to VehicleClass.SUV,
            "Minivan" to VehicleClass.SUV,
            "Passenger Vans" to VehicleClass.SUV,
            "Small Pickup Trucks" to VehicleClass.TRUCK,
            "Standard Pickup Trucks" to VehicleClass.TRUCK,
            "Special Purpose Vehicles" to VehicleClass.SEDAN,
            null to null,
            "" to null,
            "   " to null,
            "Hovercraft Class 7" to null,
            "Some Future EPA Category" to null,
            "Pickup Utility Vehicle" to VehicleClass.TRUCK,
        ).forEach { (input, expected) -> assertEquals("VClass=<$input>", expected, mapEpaVClass(input)) }
    }
}

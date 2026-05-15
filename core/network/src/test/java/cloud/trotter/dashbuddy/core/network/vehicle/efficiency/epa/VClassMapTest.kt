package cloud.trotter.dashbuddy.core.network.vehicle.efficiency.epa

import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Table-driven test for [mapEpaVClass]. Covers all EPA `VClass` strings observed
 * as of 2026 plus edge cases (null/blank/unknown).
 */
class VClassMapTest {

    @Test
    fun `compact-family strings map to COMPACT`() {
        listOf(
            "Compact Cars",
            "Subcompact Cars",
            "Minicompact Cars",
            "Two Seaters",
            "compact cars", // lowercase still matches
        ).forEach { input ->
            assertEquals("Failed for: $input", VehicleClass.COMPACT, mapEpaVClass(input))
        }
    }

    @Test
    fun `midsize-large-wagon strings map to SEDAN`() {
        listOf(
            "Midsize Cars",
            "Large Cars",
            "Midsize-Large Station Wagons",
            "Small Station Wagons",
        ).forEach { input ->
            assertEquals("Failed for: $input", VehicleClass.SEDAN, mapEpaVClass(input))
        }
    }

    @Test
    fun `sport-utility strings map to SUV`() {
        listOf(
            "Small Sport Utility Vehicle",
            "Standard Sport Utility Vehicle",
            "Sport Utility Vehicle",
        ).forEach { input ->
            assertEquals("Failed for: $input", VehicleClass.SUV, mapEpaVClass(input))
        }
    }

    @Test
    fun `van strings map to SUV cost profile`() {
        listOf("Vans", "Minivan", "Passenger Vans").forEach { input ->
            assertEquals("Failed for: $input", VehicleClass.SUV, mapEpaVClass(input))
        }
    }

    @Test
    fun `pickup strings map to TRUCK`() {
        listOf(
            "Small Pickup Trucks",
            "Standard Pickup Trucks",
        ).forEach { input ->
            assertEquals("Failed for: $input", VehicleClass.TRUCK, mapEpaVClass(input))
        }
    }

    @Test
    fun `special purpose maps to SEDAN`() {
        assertEquals(VehicleClass.SEDAN, mapEpaVClass("Special Purpose Vehicles"))
    }

    @Test
    fun `null returns null`() {
        assertNull(mapEpaVClass(null))
    }

    @Test
    fun `blank returns null`() {
        assertNull(mapEpaVClass(""))
        assertNull(mapEpaVClass("   "))
    }

    @Test
    fun `unknown string returns null`() {
        assertNull(mapEpaVClass("Hovercraft Class 7"))
        assertNull(mapEpaVClass("Some Future EPA Category"))
    }

    @Test
    fun `pickup beats SUV when both substrings present`() {
        // Defensive: hypothetical EPA string with both keywords. Pickup wins.
        assertEquals(VehicleClass.TRUCK, mapEpaVClass("Pickup Utility Vehicle"))
    }
}

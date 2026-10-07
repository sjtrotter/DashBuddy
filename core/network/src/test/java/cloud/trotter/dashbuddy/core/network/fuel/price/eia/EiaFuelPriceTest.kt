package cloud.trotter.dashbuddy.core.network.fuel.price.eia

import cloud.trotter.dashbuddy.domain.model.location.Coordinates
import cloud.trotter.dashbuddy.domain.model.location.UserLocation
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.mock

class EiaFuelPriceTest {
    // EiaApi is never called: these tables exercise pure logic.
    private val fuel = EiaFuelPrice(mock<EiaApi>())

    @Test fun `state regions`() {
        listOf(
            "New York" to "R10",
            "Florida" to "R10",
            "Virginia" to "R10",
            "Ohio" to "R20",
            "Illinois" to "R20",
            "Minnesota" to "R20",
            "Texas" to "R30",
            "Louisiana" to "R30",
            "Colorado" to "R40",
            "Montana" to "R40",
            "California" to "R50",
            "Washington" to "R50",
            "Hawaii" to "R50",
            "Puerto Rico" to "NUS",
            "Unknown Territory" to "NUS",
            "california" to "R50",
            "CALIFORNIA" to "R50",
            "new york" to "R10",
        ).forEach { (state, region) -> assertEquals(state, region, fuel.mapStateToPaddRegion(state)) }
    }

    @Test fun `location fallback`() {
        val coords = Coordinates(0.0, 0.0)
        listOf(
            Triple("null location", null, "NUS"),
            Triple("null state", UserLocation(coordinates = coords, stateName = null), "NUS"),
            Triple("blank state", UserLocation(coordinates = coords, stateName = ""), "NUS"),
            Triple("valid state", UserLocation(coordinates = coords, stateName = "Oregon"), "R50"),
        ).forEach { (name, location, region) -> assertEquals(name, region, fuel.getRegionCode(location)) }
    }

    @Test fun `fuel series`() {
        listOf(
            Triple(FuelType.REGULAR, "R50", "EMM_EPMRU_PTE_R50_DPG"),
            Triple(FuelType.MIDGRADE, "NUS", "EMM_EPMMU_PTE_NUS_DPG"),
            Triple(FuelType.PREMIUM, "R10", "EMM_EPMPU_PTE_R10_DPG"),
            Triple(FuelType.DIESEL, "R30", "EMD_EPD2D_PTE_R30_DPG"),
            Triple(FuelType.ELECTRICITY, "NUS", null),
        ).forEach { (type, region, series) ->
            val name = "$type in $region"
            if (series == null) {
                assertThrows(name, IllegalArgumentException::class.java) { fuel.buildSeriesId(type, region) }
            } else {
                assertEquals(name, series, fuel.buildSeriesId(type, region))
            }
        }
    }
}

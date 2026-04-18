package cloud.trotter.dashbuddy.core.network.fuel.price.eia

import cloud.trotter.dashbuddy.domain.model.location.Coordinates
import cloud.trotter.dashbuddy.domain.model.location.UserLocation
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

class EiaFuelPriceTest {

    // EiaApi is never called in these tests — the methods under test are pure logic.
    private val mockApi = mock<EiaApi>()
    private lateinit var fuel: EiaFuelPrice

    @Before
    fun setUp() {
        fuel = EiaFuelPrice(mockApi)
    }

    // -------------------------------------------------------------------------
    // mapStateToPaddRegion — spot-check one state per PADD region
    // -------------------------------------------------------------------------

    @Test
    fun `East Coast states map to R10`() {
        assertEquals("R10", fuel.mapStateToPaddRegion("New York"))
        assertEquals("R10", fuel.mapStateToPaddRegion("Florida"))
        assertEquals("R10", fuel.mapStateToPaddRegion("Virginia"))
    }

    @Test
    fun `Midwest states map to R20`() {
        assertEquals("R20", fuel.mapStateToPaddRegion("Ohio"))
        assertEquals("R20", fuel.mapStateToPaddRegion("Illinois"))
        assertEquals("R20", fuel.mapStateToPaddRegion("Minnesota"))
    }

    @Test
    fun `Gulf Coast states map to R30`() {
        assertEquals("R30", fuel.mapStateToPaddRegion("Texas"))
        assertEquals("R30", fuel.mapStateToPaddRegion("Louisiana"))
    }

    @Test
    fun `Rocky Mountain states map to R40`() {
        assertEquals("R40", fuel.mapStateToPaddRegion("Colorado"))
        assertEquals("R40", fuel.mapStateToPaddRegion("Montana"))
    }

    @Test
    fun `West Coast states map to R50`() {
        assertEquals("R50", fuel.mapStateToPaddRegion("California"))
        assertEquals("R50", fuel.mapStateToPaddRegion("Washington"))
        assertEquals("R50", fuel.mapStateToPaddRegion("Hawaii"))
    }

    @Test
    fun `unrecognized state falls back to NUS`() {
        assertEquals("NUS", fuel.mapStateToPaddRegion("Puerto Rico"))
        assertEquals("NUS", fuel.mapStateToPaddRegion("Unknown Territory"))
    }

    @Test
    fun `state name matching is case-insensitive`() {
        assertEquals("R50", fuel.mapStateToPaddRegion("california"))
        assertEquals("R50", fuel.mapStateToPaddRegion("CALIFORNIA"))
        assertEquals("R10", fuel.mapStateToPaddRegion("new york"))
    }

    // -------------------------------------------------------------------------
    // getRegionCode — null / missing location
    // -------------------------------------------------------------------------

    private val dummyCoords = Coordinates(0.0, 0.0)

    @Test
    fun `null UserLocation falls back to NUS`() {
        assertEquals("NUS", fuel.getRegionCode(null))
    }

    @Test
    fun `UserLocation with null stateName falls back to NUS`() {
        val location = UserLocation(coordinates = dummyCoords, stateName = null)
        assertEquals("NUS", fuel.getRegionCode(location))
    }

    @Test
    fun `UserLocation with blank stateName falls back to NUS`() {
        val location = UserLocation(coordinates = dummyCoords, stateName = "")
        assertEquals("NUS", fuel.getRegionCode(location))
    }

    @Test
    fun `UserLocation with valid stateName returns correct region`() {
        val location = UserLocation(coordinates = dummyCoords, stateName = "Oregon")
        assertEquals("R50", fuel.getRegionCode(location))
    }

    // -------------------------------------------------------------------------
    // buildSeriesId
    // -------------------------------------------------------------------------

    @Test
    fun `buildSeriesId for REGULAR fuel produces correct string`() {
        assertEquals("EMM_EPMRU_PTE_R50_DPG", fuel.buildSeriesId(FuelType.REGULAR, "R50"))
    }

    @Test
    fun `buildSeriesId for MIDGRADE fuel produces correct string`() {
        assertEquals("EMM_EPMMU_PTE_NUS_DPG", fuel.buildSeriesId(FuelType.MIDGRADE, "NUS"))
    }

    @Test
    fun `buildSeriesId for PREMIUM fuel produces correct string`() {
        assertEquals("EMM_EPMPU_PTE_R10_DPG", fuel.buildSeriesId(FuelType.PREMIUM, "R10"))
    }

    @Test
    fun `buildSeriesId for DIESEL fuel produces correct string`() {
        assertEquals("EMD_EPD2D_PTE_R30_DPG", fuel.buildSeriesId(FuelType.DIESEL, "R30"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildSeriesId for ELECTRICITY throws IllegalArgumentException`() {
        fuel.buildSeriesId(FuelType.ELECTRICITY, "NUS")
    }
}

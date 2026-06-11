package cloud.trotter.dashbuddy.core.network.vehicle

import cloud.trotter.dashbuddy.core.network.vehicle.efficiency.epa.mapEpaVClass
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * #405 — EPA wire-string normalization must be locale-independent. Under a
 * Turkish default locale, bare lowercase() maps I → dotless ı and every
 * contains-match on a capital-I word silently fails.
 */
class VClassMapLocaleTest {

    private lateinit var original: Locale

    @Before
    fun saveLocale() {
        original = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun `EPA vehicle classes map correctly under a Turkish default locale`() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        // "MIDSIZE" / "MINICOMPACT" / "PICKUP" all contain capital I — the bug class.
        assertEquals(VehicleClass.SEDAN, mapEpaVClass("MIDSIZE CARS"))
        assertEquals(VehicleClass.COMPACT, mapEpaVClass("MINICOMPACT CARS"))
        assertEquals(VehicleClass.TRUCK, mapEpaVClass("SMALL PICKUP TRUCKS"))
    }
}

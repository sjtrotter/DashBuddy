package cloud.trotter.log.gig.core.location

import org.junit.Assert.assertEquals
import org.junit.Test

class GeocoderInputTest {
    @Test fun `rounds a fix to one decimal place`() {
        assertEquals(30.3, GeocoderInput.degrees(30.267153), 0.0)
        assertEquals(-97.7, GeocoderInput.degrees(-97.743057), 0.0)
        assertEquals(40.7, GeocoderInput.degrees(40.71), 0.0)
    }

    @Test fun `a rounded value moves at most half a step`() {
        listOf(0.0, 12.3456, -45.6789, 89.99, -179.95).forEach { value ->
            val rounded = GeocoderInput.degrees(value)
            assertEquals(value, rounded, 0.05 + 1e-9)
            assertEquals("$rounded has more than ${GeocoderInput.DECIMALS} decimal", rounded, GeocoderInput.degrees(rounded), 0.0)
        }
    }
}

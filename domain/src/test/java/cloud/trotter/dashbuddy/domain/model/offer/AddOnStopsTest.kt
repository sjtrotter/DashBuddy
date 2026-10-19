package cloud.trotter.dashbuddy.domain.model.offer

import org.junit.Assert.assertEquals
import org.junit.Test

class AddOnStopsTest {
    @Test fun `stop count minus new stores`() { assertEquals(2, AddOnStops.addedDropoffs(3, 1)) }
    @Test fun `floored at one`() { assertEquals(1, AddOnStops.addedDropoffs(2, 2)) }
    @Test fun `null is one`() { assertEquals(1, AddOnStops.addedDropoffs(null, 1)) }
    @Test fun `above the maximum is one`() {
        assertEquals(1, AddOnStops.addedDropoffs(AddOnStops.MAX_ADDON_STOPS + 1, 1))
    }
}

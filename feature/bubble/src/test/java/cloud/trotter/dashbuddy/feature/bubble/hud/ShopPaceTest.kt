package cloud.trotter.dashbuddy.feature.bubble.hud

import org.junit.Assert.*
import org.junit.Test

class ShopPaceTest {
    @Test fun `pace is shopped over elapsed minutes`() {
        assertEquals(14.0 / (620.0 / 60), ShopPace.itemsPerMinute(14, 1_000, 621_000)!!, 0.0001)
    }
    @Test fun `under a minute gives no pace`() {
        assertNull(ShopPace.itemsPerMinute(14, 1_000, 60_999))
        assertNull(ShopPace.itemsPerMinute(14, 1_000, 0))
        assertEquals(14.0, ShopPace.itemsPerMinute(14, 1_000, 61_000)!!, 0.0)
    }
    @Test fun `zero shopped gives zero pace`() {
        assertEquals(0.0, ShopPace.itemsPerMinute(0, 0, 60_000)!!, 0.0)
    }
    @Test fun `forty items or fewer draw one segment each`() {
        (1..40).forEach { assertEquals(it, ShopPace.segments(it)) }
    }
    @Test fun `over forty items draw one bar`() {
        assertNull(ShopPace.segments(41))
        assertNull(ShopPace.segments(1_000))
    }
}

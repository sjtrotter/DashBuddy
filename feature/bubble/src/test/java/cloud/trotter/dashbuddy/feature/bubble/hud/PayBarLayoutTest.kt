package cloud.trotter.dashbuddy.feature.bubble.hud

import org.junit.Assert.*
import org.junit.Test

class PayBarLayoutTest {
    @Test fun `proportional widths fill the bar`() {
        val (profit, car) = PayBarLayout.split(100f, 6f, 0.7f)
        assertEquals(70f, profit, 0.001f)
        assertEquals(30f, car, 0.001f)
        assertEquals(100f, profit + car, 0.001f)
    }
    @Test fun `tiny car segment keeps the floor`() {
        val (profit, car) = PayBarLayout.split(100f, 6f, 0.99f)
        assertEquals(94f, profit, 0.001f)
        assertEquals(6f, car, 0.001f)
        assertEquals(100f, profit + car, 0.001f)
    }
    @Test fun `tiny profit segment keeps the floor`() {
        val (profit, car) = PayBarLayout.split(100f, 6f, 0.01f)
        assertEquals(6f, profit, 0.001f)
        assertEquals(94f, car, 0.001f)
        assertEquals(100f, profit + car, 0.001f)
    }
    @Test fun `zero profit keeps the floor`() {
        val (profit, car) = PayBarLayout.split(100f, 6f, 0f)
        assertEquals(6f, profit, 0.001f)
        assertEquals(94f, car, 0.001f)
        assertEquals(100f, profit + car, 0.001f)
    }
    @Test fun `bar narrower than two floors splits evenly`() {
        val (profit, car) = PayBarLayout.split(10f, 6f, 0.9f)
        assertEquals(5f, profit, 0.001f)
        assertEquals(5f, car, 0.001f)
        assertEquals(10f, profit + car, 0.001f)
    }
}

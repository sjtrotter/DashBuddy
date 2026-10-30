package cloud.trotter.log.gig.feature.bubble.hud.goal

import org.junit.Assert.*
import org.junit.Test

class GoalBoundsTest {
    @Test fun `no goal can only increase and starts at five`() {
        assertFalse(GoalBounds.canDecrease(null))
        assertNull(GoalBounds.minus(null))
        assertTrue(GoalBounds.canIncrease(null))
        assertEquals(5, GoalBounds.plus(null))
    }

    @Test fun `five dollar steps clamp at five and nine ninety nine without clearing`() {
        assertEquals(145, GoalBounds.minus(150))
        assertEquals(155, GoalBounds.plus(150))
        assertFalse(GoalBounds.canDecrease(5))
        assertEquals(5, GoalBounds.minus(5))
        assertEquals(5, GoalBounds.minus(6))
        assertEquals(999, GoalBounds.plus(995))
        assertEquals(999, GoalBounds.plus(999))
        assertFalse(GoalBounds.canIncrease(999))
        assertEquals(994, GoalBounds.minus(999))
    }

    @Test fun `every supported dollar value stays in bounds`() {
        for (dollars in 5..999) {
            assertTrue(GoalBounds.minus(dollars)!! in 5..999)
            assertTrue(GoalBounds.plus(dollars) in 5..999)
            assertEquals(dollars > 5, GoalBounds.canDecrease(dollars))
            assertEquals(dollars < 999, GoalBounds.canIncrease(dollars))
        }
    }
}

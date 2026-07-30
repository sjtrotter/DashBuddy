package cloud.trotter.dashbuddy.domain.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #942: [focusedPlatform] is the one owner of "which dash is the UI talking about", extracted from
 * four hand-rolled copies (home dashboard, ratings screen, bubble follow-platform, live card
 * builder) in three modules. These pin the exact precedence those copies had, so the extraction is
 * provably behavior-preserving.
 */
class FocusedPlatformTest {

    @Test
    fun `the flow region's active platform wins`() {
        val state = AppState(
            regions = Regions(
                flow = FlowRegion(activePlatform = Platform.Uber),
                crossPlatform = CrossPlatformRegion(mostRecentActivityPlatform = Platform.DoorDash),
            ),
        )
        assertEquals(Platform.Uber, state.focusedPlatform())
    }

    @Test
    fun `falls back to most-recent activity on a neutral screen`() {
        val state = AppState(
            regions = Regions(
                crossPlatform = CrossPlatformRegion(mostRecentActivityPlatform = Platform.DoorDash),
            ),
        )
        assertEquals(Platform.DoorDash, state.focusedPlatform())
    }

    @Test
    fun `null when nothing has been recognized yet`() {
        assertNull(AppState().focusedPlatform())
    }
}

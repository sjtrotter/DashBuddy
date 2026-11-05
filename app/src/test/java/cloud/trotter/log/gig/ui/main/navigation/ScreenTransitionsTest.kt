package cloud.trotter.log.gig.ui.main.navigation

import cloud.trotter.log.gig.ui.main.navigation.ScreenTransition.BACK
import cloud.trotter.log.gig.ui.main.navigation.ScreenTransition.FADE_THROUGH
import cloud.trotter.log.gig.ui.main.navigation.ScreenTransition.FORWARD
import cloud.trotter.log.gig.ui.main.navigation.ScreenTransition.NONE
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1524: which motion a route change gets. Robolectric because [TopLevel] builds its tab icons. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenTransitionsTest {
    private val home = Screen.Dashboard.route
    private val analytics = Screen.Analytics.route
    private val playbook = Screen.Playbook.route
    private val session = Screen.SessionDetail.route
    private val settings = Screen.SettingsHome.route
    private val garage = Screen.Garage.route

    private fun motion(from: String?, to: String?, isPop: Boolean = false, reduced: Boolean = false) =
        screenTransition(from, to, isPop, reduced)

    @Test fun `tab to tab fades through both ways, push or pop`() {
        assertEquals(FADE_THROUGH, motion(home, analytics))
        assertEquals(FADE_THROUGH, motion(analytics, home))
        assertEquals(FADE_THROUGH, motion(analytics, playbook))
        assertEquals(FADE_THROUGH, motion(playbook, home, isPop = true))
    }

    @Test fun `tab to an inner screen slides forward`() {
        assertEquals(FORWARD, motion(analytics, session))
        assertEquals(FORWARD, motion(home, settings))
    }

    @Test fun `inner screen back to its tab slides back`() {
        assertEquals(BACK, motion(session, analytics, isPop = true))
        assertEquals(BACK, motion(settings, home, isPop = true))
    }

    @Test fun `inner to inner follows push and pop`() {
        assertEquals(FORWARD, motion(settings, garage))
        assertEquals(BACK, motion(garage, settings, isPop = true))
    }

    @Test fun `a null start route is not a tab root`() {
        assertEquals(FORWARD, motion(null, home))
        assertEquals(BACK, motion(null, home, isPop = true))
        assertEquals(FORWARD, motion(home, null))
    }

    @Test fun `reduced motion beats every other rule`() {
        for (isPop in listOf(false, true)) {
            assertEquals(NONE, motion(home, analytics, isPop, reduced = true))
            assertEquals(NONE, motion(analytics, session, isPop, reduced = true))
            assertEquals(NONE, motion(session, analytics, isPop, reduced = true))
            assertEquals(NONE, motion(settings, garage, isPop, reduced = true))
            assertEquals(NONE, motion(null, home, isPop, reduced = true))
        }
    }
}

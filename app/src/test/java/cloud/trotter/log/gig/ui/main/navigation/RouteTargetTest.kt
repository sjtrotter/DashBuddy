package cloud.trotter.log.gig.ui.main.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1447 — where an external route (a deep link) is delivered. Robolectric because
 * `Screen.SessionDetail.route(id)` encodes through `android.net.Uri`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RouteTargetTest {

    @Test fun `every allowlisted route maps to a target`() {
        for (route in Screen.allRoutes) assertNotNull(route, routeTarget(route))
    }

    @Test fun `the plan route opens the plan in Playbook, fresh`() {
        assertEquals(RouteTargets.WeeklyPlan, routeTarget(Screen.WeeklyPlan.route))
        assertEquals(RouteTarget.Tab(TopLevel.PLAYBOOK, Screen.WeeklyPlan.route, fresh = true), RouteTargets.WeeklyPlan)
    }

    @Test fun `settings and add vehicle push over the current screen`() {
        assertEquals(RouteTarget.Push(Screen.SettingsHome.route), routeTarget(Screen.SettingsHome.route))
        assertEquals(RouteTarget.Push(Screen.VehicleAdd.route), routeTarget(Screen.VehicleAdd.route))
    }

    @Test fun `a tab root restores its tab`() {
        for (tab in TopLevel.entries) {
            assertEquals(RouteTarget.Tab(tab, child = null, fresh = false), routeTarget(tab.route))
        }
    }

    @Test fun `an unknown or argument route is dropped`() {
        assertNull(routeTarget("not/a/route"))
        assertNull(routeTarget(Screen.SessionDetail.route("x")))
        assertNull(routeTarget(Screen.SessionDetail.route))
        assertNull(routeTarget(Screen.Wizard.route))
    }
}

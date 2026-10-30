package cloud.trotter.log.gig.ui.main

import android.content.Intent
import cloud.trotter.log.gig.ui.main.navigation.Screen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #693 — the bubble's "Vehicle" just-in-time action deep-links into the main app via
 * [MainActivity.routeIntent]. Proves the intent targets [MainActivity], carries the requested route
 * in [MainActivity.EXTRA_ROUTE], and sets NEW_TASK (the bubble overlay lives in a separate task).
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityRouteIntentTest {

    @Test
    fun `garage and add are deep link targets while parameterized edit is excluded`() {
        assertTrue(Screen.Garage.route in Screen.allRoutes)
        assertTrue(Screen.VehicleAdd.route in Screen.allRoutes)
        assertFalse(Screen.VehicleEdit.route in Screen.allRoutes)
        assertFalse(Screen.VehicleEdit.route("vehicle-id") in Screen.allRoutes)
        assertEquals("settings/garage/vehicle/vehicle-id", Screen.VehicleEdit.route("vehicle-id"))
    }

    @Test
    fun `routeIntent targets MainActivity with the route extra and NEW_TASK`() {
        val context = RuntimeEnvironment.getApplication()

        val intent = MainActivity.routeIntent(context, Screen.EconomySettings.route)

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(Screen.EconomySettings.route, intent.getStringExtra(MainActivity.EXTRA_ROUTE))
        assertTrue(
            "expected FLAG_ACTIVITY_NEW_TASK",
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }
}

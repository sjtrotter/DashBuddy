package cloud.trotter.dashbuddy.ui.main

import android.content.Intent
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
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

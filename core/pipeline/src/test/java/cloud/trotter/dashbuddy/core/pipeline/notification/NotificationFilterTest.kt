package cloud.trotter.dashbuddy.core.pipeline.notification

import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.settings.PlatformPreferences
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #356 — notification gating must track the shared enabled-platforms state
 * WITHOUT a process restart. The old filter cached the preference once via
 * runBlocking on the hot path and its refresh hook had no callers, so a
 * settings toggle never reached it.
 */
class NotificationFilterTest {

    private class FakePrefs(
        initial: Set<Platform>,
    ) : PlatformPreferences {
        override val enabledPlatforms = MutableStateFlow(initial)
        override val enabledPackages: StateFlow<Set<String>>
            get() = MutableStateFlow(
                enabledPlatforms.value.mapNotNull { it.packageName }.toSet()
            )
    }

    private fun raw(packageName: String) = RawNotificationData(
        title = "t",
        text = "x",
        tickerText = null,
        bigText = null,
        packageName = packageName,
        postTime = 1_000L,
        isClearable = true,
    )

    @Test
    fun `gating follows the shared state without restart or refresh call`() {
        val prefs = FakePrefs(initial = setOf(Platform.DoorDash))
        val filter = NotificationFilter(prefs)
        val doordashPackage = Platform.DoorDash.packageName!!

        assertTrue(filter.isRelevant(raw(doordashPackage)))

        // Toggle the platform off — the very next gate check must see it.
        prefs.enabledPlatforms.value = emptySet()
        assertFalse(filter.isRelevant(raw(doordashPackage)))

        // And back on.
        prefs.enabledPlatforms.value = setOf(Platform.DoorDash)
        assertTrue(filter.isRelevant(raw(doordashPackage)))
    }

    @Test
    fun `unwatched packages are always irrelevant`() {
        val filter = NotificationFilter(FakePrefs(initial = Platform.entries.toSet()))
        assertFalse(filter.isRelevant(raw("com.some.random.app")))
    }
}

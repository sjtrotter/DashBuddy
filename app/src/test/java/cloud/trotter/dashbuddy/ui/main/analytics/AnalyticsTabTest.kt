package cloud.trotter.dashbuddy.ui.main.analytics

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #428 Half A: [AnalyticsTab]'s tab label moved from a hand-authored `String` field to a
 * `@StringRes` id ([AnalyticsTab.labelRes]) resolved at the Compose layer
 * (`AnalyticsScreen.tabOptions()`), which pairs each [AnalyticsTab] with its resolved label and
 * keys `AppSegmented` selection off the enum via that pairs list — never a raw string comparison
 * against the resolved label. But `AppSegmented` itself is string-keyed (`opt == selected`,
 * `onSelect(opt)`), and `tabOptions().firstOrNull { it.label == label }` does the reverse lookup
 * by the RESOLVED label string, not by resource id — so the real invariant is that the RESOLVED
 * labels are pairwise distinct; two distinct resource ids that happen to resolve to the same
 * text would still mis-select. This test resolves each [AnalyticsTab.labelRes] through a real
 * Android `Context` (Robolectric — this test module already runs it, see
 * `MainActivityRouteIntentTest`) and asserts the resolved strings are distinct (the
 * `setTab switches to Decisions...` case in [AnalyticsViewModelTest] already covers the
 * ViewModel-level round-trip proof that `setTab` resolves by enum identity).
 */
@RunWith(RobolectricTestRunner::class)
class AnalyticsTabTest {

    @Test
    fun `every tab resolves to its own distinct label`() {
        val context = RuntimeEnvironment.getApplication()
        val resolvedLabels = AnalyticsTab.entries.map { context.getString(it.labelRes) }
        assertEquals(
            "each AnalyticsTab must resolve to a distinct label (AppSegmented is string-keyed)",
            AnalyticsTab.entries.size,
            resolvedLabels.distinct().size,
        )
    }
}

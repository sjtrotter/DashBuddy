package cloud.trotter.dashbuddy.ui.main.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #428 Half A: [AnalyticsTab]'s tab label moved from a hand-authored `String` field to a
 * `@StringRes` id ([AnalyticsTab.labelRes]) resolved at the Compose layer
 * (`AnalyticsScreen.tabOptions()`), which pairs each [AnalyticsTab] with its resolved label and
 * keys `AppSegmented` selection off the enum via that pairs list — never a raw string comparison
 * against the resolved label. This test guards the data half of that mechanism: every tab must
 * own a DISTINCT resource id, since a duplicate would make two entries in `tabOptions()` share a
 * label (harmless for the pairs-list lookup used here, but a smell worth catching before it masks
 * a real content bug — the `setTab switches to Decisions...` case in [AnalyticsViewModelTest]
 * already covers the ViewModel-level round-trip proof that `setTab` resolves by enum identity).
 */
class AnalyticsTabTest {

    @Test
    fun `every tab has its own distinct label resource`() {
        val labelResIds = AnalyticsTab.entries.map { it.labelRes }
        assertEquals(
            "each AnalyticsTab must own a distinct labelRes",
            AnalyticsTab.entries.size,
            labelResIds.distinct().size,
        )
    }
}
